// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.concurrency.SensitiveProgressWrapper
import com.intellij.find.FindBundle
import com.intellij.find.impl.CandidateFilter.Source
import com.intellij.openapi.application.readActionUndispatched
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.Cancellation
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.jobToIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.ScanningWorkTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.newvfs.CacheAvoidingVirtualFile
import com.intellij.util.TimeoutUtil
import com.intellij.util.indexing.UnindexedFilesUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<FindWorkQueue>()

/**
 * One queued unit of work of a search: a [Candidate] file, or a [Walk] item of [ScopeWalker].
 * A restarted run (a write action interrupted its read action, or a retry) processes the same item object again.
 */
internal sealed class WorkItem {
  /**
   * The file this item claimed in [CandidateFilter]: the file to scan of a [Candidate], the directory of a [Walk].
   * Written once, right where the claim key is added; a restarted run sees it and keeps the admission decision.
   */
  @JvmField
  @Volatile
  var claimed: VirtualFile? = null

  /** A file that passed [CandidateFilter.admitOnProducer] for its [source]. */
  class Candidate(val file: VirtualFile, val source: Source) : WorkItem()

  /** A walk item of [ScopeWalker]; see [ScopeWalker.expand] for the payload types. */
  class Walk(val payload: Any) : WorkItem()
}

/**
 * An internal failure of a search, carried through the coroutines of [FindWorkQueue].
 * It is NOT a [CancellationException], so a plain cancellation or an [Error] of a scan fails the search instead of
 * cancelling one worker silently; [unwrap] gives back the original throwable with its type.
 */
internal class FindAbort(cause: Throwable) : RuntimeException(cause) {
  /**
   * @return the original throwable; the throwables suppressed on this abort (e.g. the failure of another worker)
   * are added to it, unwrapped too
   */
  fun unwrap(): Throwable {
    val layers = ArrayList<FindAbort>()
    var cause: Throwable = this
    while (cause is FindAbort) {
      layers.add(cause)
      cause = cause.cause ?: break
    }
    for (layer in layers) {
      for (suppressed in layer.suppressed) {
        val original = if (suppressed is FindAbort) suppressed.unwrap() else suppressed
        if (original !== cause) {
          cause.addSuppressed(original)
        }
      }
    }
    return cause
  }
}

/**
 * The work queue of one search, and the one place that defines the life of a [WorkItem].
 *
 * **Phases.** A search runs its [phase]s one after another; each phase has its own channel of [WorkItem]s, its own
 * [workerCount] worker coroutines, and a producer: the body of [phase], on the search coroutine. A phase returns only when
 * every item of it finished, so the next phase starts on a drained queue ([FindSearchRun] relies on that ordering).
 *
 * **Close rule.** A walk item adds more items from its worker ([offer]), so the end of a phase is not known when the producer
 * returns. The channel closes when `producerDone && pending == 0`, checked at both events: when the producer is done, and when
 * an item finishes. [offer] counts an item as pending before the item is visible, so the channel never closes under it.
 *
 * **Processing.** A worker processes an item with [processItem] in one read action that yields to write actions
 * ([readActionUndispatched]), under a [SensitiveProgressWrapper] of [searchIndicator] that a write action cancels
 * (registered in [ScanningWorkTracker]); one item = one blocking call on one thread, nothing suspends inside.
 *
 * **Outcomes of an item** (the one classify point is `classify`):
 * - [processItem] returns true: the item finished.
 * - [processItem] returns false: the search stops (see below).
 * - A write action: the read action restarts with the same item.
 * - Another [ProcessCanceledException] (the scanning cancellation monitor cancelled the wrapper, or a hook threw its own):
 *   retried in place with the same item while the search is live; when [searchIndicator] is cancelled, it fails the search
 *   as `FindAbort(pce)`, which [FindInProjectTask.findUsages] unwraps to a cancel.
 * - Any other throwable, a plain [CancellationException] or an [Error] included: fails the search as [FindAbort]. The scopes
 *   cancel the other workers and the producer; [FindAbort.unwrap] gives the original throwable back to the caller.
 *   A failure of the producer itself is wrapped the same way ([run], [phase]).
 * - A cancel of the search coroutine (an outside cancel) is no item outcome: it passes as the [CancellationException] it is.
 *
 * **Claims persist.** A restarted or retried run processes the same item object. The admission decision of an item is
 * recorded on the item ([WorkItem.claimed], set by [CandidateFilter]) right where its claim key is added, and a key is never
 * released; so a restart keeps the claim, skips the checks, and no other item can claim the file in between.
 *
 * **Stop.** When [processItem] returns false, [isStopped] becomes true and the channel is cancelled: queued items are dropped,
 * in-flight items finish (their [offer]s return false), the phase returns false, and the later phases do not run.
 */
internal class FindWorkQueue(
  private val searchIndicator: ProgressIndicator,
  private val workerCount: Int,
  private val processItem: (WorkItem) -> Boolean,
) {
  /** True after [processItem] returned false; the later phases do not run. */
  @Volatile
  var isStopped: Boolean = false
    private set

  @Volatile
  private var phase: Phase? = null

  private val dispatcher = Dispatchers.IO.limitedParallelism(workerCount)
  private val tracker = ScanningWorkTracker.getInstance()

  /**
   * Runs the producer [body] of a search; its own failure is wrapped as [FindAbort], except a cancellation of the search
   * coroutine (an outside cancel), which is rethrown as is.
   */
  suspend fun <T> run(body: suspend () -> T): T {
    try {
      return body()
    }
    catch (e: Throwable) {
      abortOnProducerFailure(e)
    }
  }

  /**
   * Runs one phase: starts the workers, runs [produce] on this coroutine, and returns when every item finished.
   *
   * @return false when the search is stopped
   */
  suspend fun phase(produce: suspend () -> Unit): Boolean {
    if (isStopped) return false
    coroutineScope {
      val phase = Phase()
      this@FindWorkQueue.phase = phase
      repeat(workerCount) {
        launch(dispatcher) { work(phase) }
      }
      try {
        produce()
      }
      catch (e: Throwable) {
        // wrapped before it leaves the scope, which may replace a CancellationException by a copy with a recovered stack trace
        abortOnProducerFailure(e)
      }
      phase.producerDone()
    }
    return !isStopped
  }

  private suspend fun abortOnProducerFailure(e: Throwable): Nothing {
    if (e is FindAbort) throw e
    // a cancel of the search (an outside cancel, or a worker failure that cancelled this scope) is not a producer failure:
    currentCoroutineContext().ensureActive()
    throw FindAbort(e)
  }

  /**
   * Queues an item into the running phase. Never suspends: a walk worker calls it inside its read action.
   *
   * @return false when the search is stopped
   */
  fun offer(item: WorkItem): Boolean {
    if (isStopped) return false
    val phase = checkNotNull(phase) { "no phase runs" }
    if (phase.offer(item)) return true
    // UNLIMITED channel, and it closes only with no pending item, so a failed send means a stop cancelled it
    check(isStopped) { "an item was offered after its phase closed" }
    return false
  }

  /** Runs a blocking producer step under an indicator that unwraps to [searchIndicator] and is canceled with this coroutine. */
  suspend fun <T> producerStep(body: () -> T): T {
    return jobToIndicator(currentCoroutineContext().job, SensitiveProgressWrapper(searchIndicator), body)
  }

  private suspend fun work(phase: Phase) {
    try {
      for (item in phase.channel) {
        if (isStopped) return // the stopping worker may not have cancelled the channel yet
        if (!process(item)) {
          isStopped = true
          phase.channel.cancel()
          return
        }
        phase.itemFinished()
      }
    }
    catch (e: CancellationException) {
      // a cancel of the search is not ours to swallow:
      currentCoroutineContext().ensureActive()
      // the channel of a stopped search was cancelled: queued items drop, and this worker exits normally
      if (!isStopped) throw e
    }
  }

  /** @return false when the search must stop */
  private suspend fun process(item: WorkItem): Boolean {
    var retries = 0
    while (true) {
      try {
        return readActionUndispatched {
          underSearchIndicator {
            classify { processItem(item) }
          }
        }
      }
      catch (e: ProcessCanceledException) {
        // not a write action (that one restarts inside the read action): a cancel of the indicator wrapper by the scanning
        // cancellation monitor, or a hook's own PCE
        currentCoroutineContext().ensureActive()
        if (searchIndicator.isCanceled) throw FindAbort(e)
        retries++
        LOG.debug { "Retrying a work item ($retries) after $e" }
        yield()
      }
    }
  }

  private fun <T> underSearchIndicator(body: () -> T): T {
    val readJob = checkNotNull(Cancellation.currentJob()) { "no read job in the read-action body" }
    val wrapper = SensitiveProgressWrapper(searchIndicator)
    return tracker.trackReadAction(wrapper) {
      jobToIndicator(readJob, wrapper, body)
    }
  }

  /** The one outcome point of an item: a [ProcessCanceledException] restarts or retries it, any other throwable fails the search. */
  private inline fun <T> classify(body: () -> T): T {
    try {
      return body()
    }
    catch (e: ProcessCanceledException) {
      throw e
    }
    catch (e: Throwable) {
      // a plain CancellationException too: in a worker coroutine it would cancel this worker silently
      throw FindAbort(e)
    }
  }

  /** One channel of a phase and its close rule. */
  internal class Phase {
    val channel: Channel<WorkItem> = Channel(Channel.UNLIMITED)

    private val pending = AtomicInteger()

    @Volatile
    private var producerDone = false

    /** @return false when the channel is closed or cancelled */
    fun offer(item: WorkItem): Boolean {
      pending.incrementAndGet() // before the item is visible, so the channel never closes under it
      if (channel.trySend(item).isSuccess) return true
      pending.decrementAndGet()
      return false
    }

    fun itemFinished() {
      if (pending.decrementAndGet() == 0 && producerDone) {
        channel.close()
      }
    }

    fun producerDone() {
      producerDone = true
      if (pending.get() == 0) {
        channel.close()
      }
    }
  }
}

/**
 * One "Find in Path" search run: produces the candidates of [FindInProjectTask.findUsages] in three phases into a
 * [FindWorkQueue] (priority files, searcher files, the walk of the scope), and processes each item on a worker:
 * a [WorkItem.Candidate] is admitted by [CandidateFilter.admitOnWorker] and scanned by [FileScanner], a [WorkItem.Walk] is
 * expanded by [ScopeWalker.expand] with the [ScopeWalker.WalkPlan] that the walk phase built.
 */
internal class FindSearchRun(
  private val project: Project,
  private val filesToScanInitially: Set<VirtualFile>,
  private val searchers: SearcherSet,
  private val candidateFilter: CandidateFilter,
  private val scopeWalker: ScopeWalker,
  private val fileScanner: FileScanner,
  private val indicator: ProgressIndicator,
  private val searchStartedAtNs: Long,
) : ScopeWalker.Sink {
  private val queue = FindWorkQueue(indicator, UnindexedFilesUpdater.getNumberOfScanningThreads(), ::processItem)

  //progress fraction = scanned / queued candidates:
  private val queuedCandidates = AtomicInteger()
  private val scannedCandidates = AtomicInteger()
  private val lastFractionUpdateNs = AtomicLong(System.nanoTime())

  /** The plan of the walk phase; written by its producer before it offers the first walk item, read by the workers. */
  @Volatile
  private var walkPlan: ScopeWalker.WalkPlan? = null

  //statistics of the walk, for the debug log; counted only when it is enabled:
  private val otherFilesCount = AtomicInteger()
  private val otherFilesTransientCount = AtomicInteger()
  private val otherFilesCacheAvoidingCount = AtomicInteger()

  /**
   * Runs the search on the calling thread, which produces; the workers run on a scanning dispatcher.
   * When every phase completed, the fraction ends at 1, and the scan observers get
   * [com.intellij.find.FindInProjectSearchEngine.ScanObserver.nonIndexedScanCompleted] if the walk covered the non-indexable files
   * ([ScopeWalker.WalkPlan.nonIndexableWalked]).
   *
   * @return false when the search stopped
   * @throws FindAbort on an internal failure; [FindAbort.unwrap] gives the original throwable
   */
  fun runBlocking(): Boolean = runBlockingCancellable {
    queue.run { produce() }
  }

  private suspend fun produce(): Boolean {
    indicator.isIndeterminate = false
    indicator.text = FindBundle.message("progress.text.scanning.indexed.files")

    //phase 1, before any searcher runs: check the files already on the user's screen, so their usages surface at once
    // (a searcher collects all its candidates before it returns, which can take long on a large project);
    // they drain before the searcher files, as they must complete first to fill the result cap
    val priorityCompleted = queue.phase {
      queue.producerStep {
        for (file in FileEditorManager.getInstance(project).openFiles) {
          addCandidate(file, Source.PRIORITY)
        }
        for (file in filesToScanInitially) {
          addCandidate(file, Source.PRIORITY)
        }
      }
    }
    if (!priorityCompleted) return stoppedIn("priority")
    LOG.debug {
      "Search phase priority: scanned ${candidateFilter.admittedCount(Source.PRIORITY)} files after ${elapsedMs()} ms"
    }

    //phase 2: the searchers (=index); the cheap collected answer is queued first, so it does not wait behind a slow stream:
    var collectedCount = 0
    val searcherCompleted = queue.phase {
      queue.producerStep {
        val collectedFiles = collectFiles()
        collectedCount = collectedFiles.size
        for (file in collectedFiles.sortedWith(SEARCH_RESULT_FILE_COMPARATOR)) {
          enqueueCandidate(file, Source.SEARCHER)
        }
        streamFromSearchers()
      }
    }
    if (!searcherCompleted) return stoppedIn("searcher")
    //every searcher returned, so its coverage is known:
    val skipNonIndexableFilesWalk = searchers.nonIndexableCovered()
    LOG.debug {
      "Search phase searcher: collected $collectedCount files, scanned ${candidateFilter.admittedCount(Source.SEARCHER)} " +
      "searcher files after ${elapsedMs()} ms; skip non-indexable walk: $skipNonIndexableFilesWalk"
    }

    //phase 3: the files of the scope by bruteforce, which may load files from disk -- so only after the searcher files drained:
    indicator.text = FindBundle.message("progress.text.scanning.non.indexed.files")
    val walkCompleted = queue.phase {
      queue.producerStep {
        val plan = scopeWalker.roots(skipNonIndexableFilesWalk)
        walkPlan = plan
        for (payload in plan.items) {
          addItem(payload)
        }
      }
    }
    if (!walkCompleted) return stoppedIn("walk")
    LOG.debug {
      "Search phase walk: processed ${otherFilesCount.get()} non-indexed files: ${otherFilesTransientCount.get()} transient, " +
      "${otherFilesCacheAvoidingCount.get()} cache-avoiding; admitted ${candidateFilter.admittedCount(Source.WALK)} walked, " +
      "${candidateFilter.admittedCount(Source.EXTENSION)} extension files after ${elapsedMs()} ms"
    }

    //a skipped walk of the non-indexable files is not a full scan, so the observers are not told it completed:
    if (walkPlan?.nonIndexableWalked == true) {
      queue.producerStep { searchers.nonIndexedScanCompleted() }
    }
    if (indicator.isRunning) {
      indicator.fraction = 1.0
    }
    return true
  }

  private fun stoppedIn(phase: String): Boolean {
    LOG.debug { "Search stopped in the $phase phase" }
    return false
  }

  private fun elapsedMs(): Long = TimeoutUtil.getDurationMillis(searchStartedAtNs)

  /** @return candidate files found by the non-streaming searchers that pass [CandidateFilter.admitOnProducer] */
  private fun collectFiles(): List<VirtualFile> {
    val resultFiles = ArrayList<VirtualFile>()
    for (searcher in searchers.collecting()) {
      searchers.process(searcher) { file ->
        if (candidateFilter.admitOnProducer(file, Source.SEARCHER)) {
          synchronized(resultFiles) {
            resultFiles.add(file)
          }
        }
        true
      }
    }
    return resultFiles
  }

  /**
   * Runs the streaming searchers one by one on this thread, while the workers check the files they feed.
   * A fed file is queued when it passes [CandidateFilter.admitOnProducer], so the files collected before are skipped.
   * A stop of the search makes the searcher's processor return false.
   */
  private fun streamFromSearchers() {
    for (searcher in searchers.streaming()) {
      if (queue.isStopped) return
      searchers.process(searcher) { file -> addCandidate(file, Source.SEARCHER) }
    }
  }

  override fun addItem(payload: Any): Boolean = queue.offer(WorkItem.Walk(payload))

  override fun addCandidate(file: VirtualFile, source: Source): Boolean {
    if (queue.isStopped) return false
    return !candidateFilter.admitOnProducer(file, source) || enqueueCandidate(file, source)
  }

  private fun enqueueCandidate(file: VirtualFile, source: Source): Boolean {
    queuedCandidates.incrementAndGet() // before the item is visible, so the fraction never exceeds 1
    return queue.offer(WorkItem.Candidate(file, source))
  }

  /** Processes one item on a worker, under its read action. @return false when the search must stop */
  private fun processItem(item: WorkItem): Boolean {
    return when (item) {
      is WorkItem.Candidate -> scan(item)
      is WorkItem.Walk -> {
        scopeWalker.expand(checkNotNull(walkPlan) { "a walk item before the walk plan" }, item, this)
        true
      }
    }
  }

  private fun scan(candidate: WorkItem.Candidate): Boolean {
    val toScan = candidateFilter.admitOnWorker(candidate)
    var result = true
    if (toScan != null) {
      result = fileScanner.process(toScan)
      if ((candidate.source == Source.WALK || candidate.source == Source.EXTENSION) && LOG.isDebugEnabled) {
        countOtherFile(toScan)
      }
    }
    scannedCandidates.incrementAndGet()
    updateFraction()
    return result
  }

  private fun countOtherFile(file: VirtualFile) {
    otherFilesCount.incrementAndGet()
    if (file is CacheAvoidingVirtualFile) {
      if (file.isCached) {
        otherFilesCacheAvoidingCount.incrementAndGet()
      }
      else {
        otherFilesTransientCount.incrementAndGet()
      }
    }
  }

  private fun updateFraction() {
    val nowNs = System.nanoTime()
    val lastNs = lastFractionUpdateNs.get()
    if (nowNs - lastNs < PROGRESS_FRACTION_UPDATE_INTERVAL_NS || !lastFractionUpdateNs.compareAndSet(lastNs, nowNs)) return
    if (indicator.isRunning) {
      indicator.fraction = scannedCandidates.get().toDouble() / maxOf(1, queuedCandidates.get())
    }
  }
}

private val SEARCH_RESULT_FILE_COMPARATOR: Comparator<VirtualFile> =
  compareBy<VirtualFile> { (it as? VirtualFileWithId)?.id ?: 0 }
    .thenBy { it.name } // in case files without id are also searched
    .thenBy { it.path }

/** The progress fraction is updated on the same time budget as the progress text of [FileScanner]. */
private val PROGRESS_FRACTION_UPDATE_INTERVAL_NS: Long = TimeUnit.MILLISECONDS.toNanos(100)

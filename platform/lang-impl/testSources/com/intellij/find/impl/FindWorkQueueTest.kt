// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.find.impl.CandidateFilter.Source
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.DelicateCoroutinesApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.Collections.synchronizedList
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds

/**
 * Pins [FindWorkQueue]: the close rule of a phase, the batches of a worker, the stop, how a failure leaves the queue, and that
 * a claim persists on its [WorkItem] across a read-action restart.
 */
@TestApplication
class FindWorkQueueTest {
  // close rule

  @Test
  fun `the channel closes when the producer is done after the last item finished`() {
    val phase = FindWorkQueue.Phase()
    assertThat(phase.offer(item("a"))).isTrue()
    phase.walksFinished(1)
    assertThat(isClosed(phase)).describedAs("closed before the producer is done").isFalse()

    phase.producerDone()

    assertThat(isClosed(phase)).isTrue()
  }

  @Test
  fun `the channel closes when the last item finished after the producer is done`() {
    val phase = FindWorkQueue.Phase()
    assertThat(phase.offer(item("a"))).isTrue()
    phase.producerDone()
    assertThat(isClosed(phase)).describedAs("closed with a pending item").isFalse()

    phase.walksFinished(1)

    assertThat(isClosed(phase)).isTrue()
  }

  @Test
  fun `an item offered by an in-flight item keeps the channel open`() {
    val phase = FindWorkQueue.Phase()
    assertThat(phase.offer(item("parent"))).isTrue()
    phase.producerDone()
    assertThat(phase.offer(item("child"))).isTrue()

    phase.walksFinished(1)
    assertThat(isClosed(phase)).describedAs("closed with the child pending").isFalse()
    phase.walksFinished(1)

    assertThat(isClosed(phase)).isTrue()
  }

  @Test
  fun `a queued candidate does not keep the channel open and is received after the close`() {
    val phase = FindWorkQueue.Phase()
    val candidate = WorkItem.Candidate(LightVirtualFile("a.txt", "text"), Source.SEARCHER)
    assertThat(phase.offer(candidate)).isTrue()

    phase.producerDone()

    assertThat(isClosed(phase)).describedAs("a candidate offers nothing, so it does not hold the close").isTrue()
    assertThat(phase.channel.tryReceive().getOrNull()).isSameAs(candidate)
  }

  @Test
  fun `a second close and a close after a cancel are no-ops`() {
    val idle = FindWorkQueue.Phase()
    idle.producerDone()
    idle.producerDone()
    assertThat(isClosed(idle)).isTrue()
    assertThat(idle.offer(item("late"))).describedAs("offer after the close").isFalse()

    val cancelled = FindWorkQueue.Phase()
    assertThat(cancelled.offer(item("a"))).isTrue()
    cancelled.channel.cancel()
    cancelled.walksFinished(1)
    cancelled.producerDone()
    assertThat(isClosed(cancelled)).isTrue()
  }

  // stop

  @Test
  fun `a stop drops the queued items and lets the in-flight item finish`(): Unit = timeoutRunBlocking(30.seconds) {
    val events = synchronizedList(ArrayList<String>())
    val inFlightStarted = CountDownLatch(1)
    lateinit var queue: FindWorkQueue
    queue = FindWorkQueue(ProgressIndicatorBase(), 2) { item ->
      when (val payload = payload(item)) {
        "in-flight" -> {
          events.add("in-flight started")
          inFlightStarted.countDown()
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
          while (!queue.isStopped && System.nanoTime() < deadline) {
            ProgressManager.checkCanceled() // a stop does not cancel the in-flight item
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
          }
          events.add("in-flight finished")
          true
        }
        "stop" -> {
          check(inFlightStarted.await(20, TimeUnit.SECONDS)) { "the in-flight item did not start" }
          events.add("stop")
          false
        }
        else -> {
          events.add("queued $payload")
          true
        }
      }
    }

    val completed = queue.phase {
      for (payload in listOf("in-flight", "stop", "q1", "q2")) {
        queue.offer(item(payload))
      }
    }

    assertThat(completed).isFalse()
    assertThat(queue.isStopped).isTrue()
    assertThat(events.toList()).containsExactly("in-flight started", "stop", "in-flight finished")
    assertThat(queue.offer(item("after the stop"))).isFalse()
    assertThat(queue.phase { error("a phase after the stop ran") }).isFalse()
  }

  // batches

  @Test
  fun `ready items run in fewer read actions than items, and the phase returns after the last batch`() {
    val payloads = (0 until 50).map { "i$it" }

    val run = runOnOneWorker(payloads) { true }

    assertThat(run.completed).isTrue()
    assertThat(run.runs).containsExactlyElementsOf(payloads)
    assertThat(run.indicators).doesNotContainNull()
    val readActions = Collections.newSetFromMap(IdentityHashMap<ProgressIndicator, Boolean>()).apply { addAll(run.indicators) }
    assertThat(readActions.size).describedAs("read actions (indicator wrappers) for ${payloads.size} items").isLessThan(payloads.size)
  }

  @Test
  fun `a write action in a batch runs the interrupted item again, and not the items finished before it`() {
    val bRuns = AtomicInteger()

    val run = runOnOneWorker(listOf("a", "b", "c", "d")) { payload ->
      if (payload == "b" && bRuns.incrementAndGet() == 1) {
        // a pending write action cancels this read action, which runs the unfinished item again after the write
        ApplicationManager.getApplication().executeOnPooledThread { WriteAction.runAndWait<RuntimeException> {} }
        spinUntil { false }
        error("the write action did not cancel the read action")
      }
      true
    }

    assertThat(run.completed).isTrue()
    assertThat(run.runs).containsExactly("a", "b", "b", "c", "d")
  }

  @Test
  fun `a false of an item in a batch skips the later items of the batch and stops the phase`() {
    val run = runOnOneWorker(listOf("a", "b", "c", "d")) { payload -> payload != "b" }

    assertThat(run.completed).isFalse()
    assertThat(run.runs).containsExactly("a", "b")
  }

  @Test
  fun `a retried ProcessCanceledException in a batch retries only that item`() {
    val bRuns = AtomicInteger()

    val run = runOnOneWorker(listOf("a", "b", "c", "d")) { payload ->
      if (payload == "b" && bRuns.incrementAndGet() == 1) {
        throw ProcessCanceledException() // a hook's own PCE, with the search live
      }
      true
    }

    assertThat(run.completed).isTrue()
    assertThat(run.runs).containsExactly("a", "b", "b", "c", "d")
  }

  // failures

  @Test
  fun `a plain CancellationException of an item fails the search and keeps its type`() {
    val thrown = CancellationException("plain")
    assertThat(workerFailure(thrown)).isSameAs(thrown)
  }

  @Test
  fun `an Error of an item fails the search and keeps its type`() {
    val thrown = AssertionError("assert")
    assertThat(workerFailure(thrown)).isSameAs(thrown)
  }

  @Test
  fun `a plain CancellationException of the producer fails the search and keeps its type`() {
    val thrown = CancellationException("searcher")
    val queue = FindWorkQueue(ProgressIndicatorBase(), 2) { true }

    val abort = timeoutRunBlocking {
      try {
        queue.run { queue.phase { throw thrown } }
        null
      }
      catch (e: FindAbort) {
        e
      }
    }

    assertThat(abort?.unwrap()).isSameAs(thrown)
  }

  @Test
  fun `unwrap keeps the suppressed failures, unwrapped`() {
    val first = IllegalStateException("first")
    val second = AssertionError("second")
    val abort = FindAbort(first)
    abort.addSuppressed(FindAbort(second))

    assertThat(FindAbort(abort).unwrap()).isSameAs(first)
    assertThat(first.suppressed).containsExactly(second)
  }

  // claims

  @Test
  fun `a claim persists across a write-action restart, so the checks run once and the scan twice`(): Unit = timeoutRunBlocking(30.seconds) {
    val coverageChecks = AtomicInteger()
    val filter = CandidateFilter({ true }, { false }, { true }, { true }, { coverageChecks.incrementAndGet(); false }, true, { null })
    val file = LightVirtualFile("a.txt", "text")
    val item = WorkItem.Candidate(file, Source.WALK)
    val runs = AtomicInteger()
    val queue = FindWorkQueue(ProgressIndicatorBase(), 1) { workItem ->
      val toScan = filter.admitOnWorker(workItem as WorkItem.Candidate)
      if (runs.incrementAndGet() == 1) {
        // a pending write action cancels this read action, which runs the item again after the write
        ApplicationManager.getApplication().executeOnPooledThread { WriteAction.runAndWait<RuntimeException> {} }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
          ProgressManager.checkCanceled()
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        error("the write action did not cancel the read action")
      }
      toScan === file
    }

    val completed = queue.phase { queue.offer(item) }

    assertThat(completed).isTrue()
    assertThat(runs.get()).describedAs("runs of the item").isEqualTo(2)
    assertThat(coverageChecks.get()).describedAs("coverage checks").isEqualTo(1)
    assertThat(item.claimed).isSameAs(file)
  }

  private fun workerFailure(thrown: Throwable): Throwable? {
    val queue = FindWorkQueue(ProgressIndicatorBase(), 2) { throw thrown }
    val abort = timeoutRunBlocking {
      try {
        queue.run { queue.phase { queue.offer(item("failing")) } }
        null
      }
      catch (e: FindAbort) {
        e
      }
    }
    return abort?.unwrap()
  }

  private class BatchRun(val completed: Boolean, val runs: List<String>, val indicators: List<ProgressIndicator?>)

  /**
   * Runs one phase of [payloads] on one worker with a batch budget that does not run out. The first item waits until every item
   * is queued, so the rest are ready when it finishes and form its batch. Records each run of an item and its indicator.
   */
  private fun runOnOneWorker(payloads: List<String>, body: (String) -> Boolean): BatchRun = timeoutRunBlocking(30.seconds) {
    val runs = synchronizedList(ArrayList<String>())
    val indicators = synchronizedList(ArrayList<ProgressIndicator?>())
    val allQueued = AtomicBoolean()
    val queue = FindWorkQueue(ProgressIndicatorBase(), 1, batchBudgetNs = TimeUnit.MINUTES.toNanos(1)) { item ->
      val payload = payload(item)
      runs.add(payload)
      indicators.add(ProgressManager.getGlobalProgressIndicator())
      if (payload == payloads.first()) {
        check(spinUntil { allQueued.get() }) { "the items were not queued" }
      }
      body(payload)
    }

    val completed = queue.phase {
      for (payload in payloads) {
        queue.offer(item(payload))
      }
      allQueued.set(true)
    }
    BatchRun(completed, runs.toList(), indicators.toList())
  }

  /** Waits in a read action for [condition], cancellable by a write action; @return false after 20 s. */
  private fun spinUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (System.nanoTime() < deadline) {
      if (condition()) return true
      ProgressManager.checkCanceled()
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
    }
    return false
  }

  private fun item(payload: String): WorkItem = WorkItem.Walk.File(LightVirtualFile(payload))

  private fun payload(item: WorkItem): String = (item as WorkItem.Walk.File).file.name

  @OptIn(DelicateCoroutinesApi::class)
  private fun isClosed(phase: FindWorkQueue.Phase): Boolean = phase.channel.isClosedForSend
}

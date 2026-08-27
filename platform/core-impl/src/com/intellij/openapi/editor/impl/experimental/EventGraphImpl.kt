// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocText
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph
import com.intellij.openapi.editor.experimental.Version
import com.intellij.openapi.editor.impl.DocTextImpl
import java.util.Arrays
import java.util.BitSet
import java.util.Collections
import java.util.PriorityQueue

/**
 * One stored run: the public [Event] plus its graph links. The run covers the lvs
 * `[lvStart, lvStart + event.length())`. [parents] belong to the first unit; every
 * later unit has the one implicit parent `lv - 1`.
 */
internal class StoredRun(
  val event: Event,
  val lvStart: LV,
  val parents: IntArray,
) {
  fun lvEnd(): LV = lvStart + event.length()
}

/** One per-agent index entry: the seqs `[seqStart, seqStart + length)` start at [lvStart]. */
internal class AgentRun(val seqStart: Int, val length: Int, val lvStart: LV)

/**
 * The shared append-only storage behind [EventGraphImpl] values, run-length encoded:
 * one slot per [StoredRun], not per character.
 *
 * Successive graphs of one linear chain share one store; each graph sees the run
 * prefix that covers its first `size` lvs. When a graph that is not the tip appends,
 * the store copies that prefix into a new store, so the old chain stays untouched.
 *
 * Thread safety: appends and the per-agent id index synchronize on this store. Reads
 * of committed run slots do not synchronize. That is safe because a slot is written
 * before the graph that covers it is constructed, and a graph reaches another thread
 * only through a safe publication of that graph value (the same reasoning as in
 * [DocTextImpl]).
 */
internal class EventStore private constructor(
  @Volatile private var runs: Array<StoredRun?>,
  private var committedRuns: Int,
  private var committedLvs: Int,
  private val agentIndex: HashMap<Agent, ArrayList<AgentRun>>,
) {

  fun runAt(index: Int): StoredRun {
    val run = runs[index]
    checkCommittedSlot(run, index)
    return run!!
  }

  /** The lv of the unit ([agent], [seq]) when it sits below [lvLimit], or -1. */
  fun lvOfSeq(agent: Agent, seq: Int, lvLimit: LV): LV {
    synchronized(this) {
      val list = agentIndex[agent] ?: return -1
      // The list is sorted by seqStart and the ranges do not overlap: find the floor entry.
      var lo = 0
      var hi = list.size - 1
      var floor: AgentRun? = null
      while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val entry = list[mid]
        if (entry.seqStart <= seq) {
          floor = entry
          lo = mid + 1
        }
        else {
          hi = mid - 1
        }
      }
      if (floor == null || seq >= floor.seqStart + floor.length || floor.lvStart >= lvLimit) {
        return -1
      }
      return floor.lvStart + (seq - floor.seqStart)
    }
  }

  /** Whether any unit id in `[seq, seq + length)` of [agent] already sits below [lvLimit]. */
  fun overlaps(agent: Agent, seq: Int, length: Int, lvLimit: LV): Boolean {
    synchronized(this) {
      val list = agentIndex[agent] ?: return false
      // The ranges are sorted by seqStart and do not overlap, so the ends are sorted
      // too: binary search for the first entry that ends after seq.
      var lo = 0
      var hi = list.size
      while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (list[mid].seqStart + list[mid].length <= seq) {
          lo = mid + 1
        }
        else {
          hi = mid
        }
      }
      // Walk the contiguous block of entries that intersect `[seq, seq + length)`.
      var i = lo
      while (i < list.size && list[i].seqStart < seq + length) {
        if (list[i].lvStart < lvLimit) {
          return true
        }
        i++
      }
      return false
    }
  }

  /** The next free seq of [agent] among the runs below [lvLimit]. */
  fun nextSeq(agent: Agent, lvLimit: LV): Int {
    synchronized(this) {
      val list = agentIndex[agent] ?: return 0
      // The ranges sort by seqStart and do not overlap, so the ends ascend: the last
      // visible entry has the greatest end. The scan usually stops at the first entry.
      for (i in list.indices.reversed()) {
        val entry = list[i]
        if (entry.lvStart < lvLimit) {
          return entry.seqStart + entry.length
        }
      }
      return 0
    }
  }

  /**
   * Appends [run] after the prefix of [expectedRuns] runs covering [expectedLvs] lvs, and
   * returns the store that holds the result: this store when it is the tip, or a fresh
   * prefix copy otherwise.
   */
  fun appendAt(expectedLvs: Int, expectedRuns: Int, run: StoredRun): EventStore {
    if (tryAppend(expectedLvs, run)) {
      return this
    }
    val copy = copyPrefix(expectedRuns, expectedLvs)
    val appended = copy.tryAppend(expectedLvs, run)
    checkDivergedAppend(appended)
    return copy
  }

  private fun tryAppend(expectedLvs: Int, run: StoredRun): Boolean {
    synchronized(this) {
      if (committedLvs != expectedLvs) {
        return false
      }
      var runs = this.runs
      if (committedRuns == runs.size) {
        runs = runs.copyOf(maxOf(INITIAL_CAPACITY, runs.size * 2))
      }
      runs[committedRuns] = run
      this.runs = runs // volatile write publishes the resized array and its elements
      committedRuns++
      committedLvs += run.event.length()
      indexAgentRun(run)
      return true
    }
  }

  private fun indexAgentRun(run: StoredRun) {
    val event = run.event
    val list = agentIndex.getOrPut(event.agent()) { ArrayList() }
    val entry = AgentRun(event.seq(), event.length(), run.lvStart)
    // Keep the list sorted by seqStart. An append usually goes to the end.
    var i = list.size
    while (i > 0 && list[i - 1].seqStart > entry.seqStart) {
      i--
    }
    list.add(i, entry)
  }

  private fun copyPrefix(runCount: Int, lvs: Int): EventStore {
    val runs = this.runs // one volatile read; the prefix slots are immutable once written
    val newRuns = arrayOfNulls<StoredRun>(maxOf(INITIAL_CAPACITY, runCount * 2))
    System.arraycopy(runs, 0, newRuns, 0, runCount)
    val copy = EventStore(newRuns, runCount, lvs, HashMap())
    for (i in 0 until runCount) {
      copy.indexAgentRun(newRuns[i]!!)
    }
    return copy
  }

  private fun checkCommittedSlot(run: StoredRun?, index: Int) {
    require(run != null) {
      "The run slot $index is not committed"
    }
  }

  private fun checkDivergedAppend(appended: Boolean) {
    require(appended) {
      "An append into a fresh prefix copy failed"
    }
  }

  companion object {
    private const val INITIAL_CAPACITY = 16

    fun empty(): EventStore {
      return EventStore(arrayOfNulls(INITIAL_CAPACITY), 0, 0, HashMap())
    }
  }
}

/**
 * See [EventGraph]. An immutable view over an [EventStore] prefix: [runCount] runs
 * covering the lvs `[0, size)`.
 */
internal class EventGraphImpl private constructor(
  private val store: EventStore,
  private val runCount: Int,
  private val size: Int,
  private val version: VersionImpl,
) : EventGraph {

  override fun size(): Int {
    return size
  }

  override fun runCount(): Int {
    return runCount
  }

  override fun version(): Version {
    return version
  }

  fun versionImpl(): VersionImpl {
    return version
  }

  override fun append(event: Event, parents: Version): EventGraph {
    return appendImpl(event, VersionImpl.implOf(parents))
  }

  fun appendImpl(event: Event, parents: VersionImpl): EventGraphImpl {
    checkVersionOfThisGraph(parents)
    checkNewIds(event)
    val run = StoredRun(event, size, parents.lvs)
    val newStore = store.appendAt(size, runCount, run)
    val newLastLv = size + event.length() - 1
    val newVersion = VersionImpl(advanceFrontier(version.lvs, parents.lvs, newLastLv))
    return EventGraphImpl(newStore, runCount + 1, size + event.length(), newVersion)
  }

  /** Appends the [event] run at this graph's own frontier. */
  fun appendAtTip(event: Event): EventGraphImpl {
    return appendImpl(event, version)
  }

  /** The next free seq of [agent] in this graph. */
  fun nextSeqFor(agent: Agent): Int {
    return store.nextSeq(agent, size)
  }

  override fun mergeFrom(other: EventGraph): EventGraph {
    return mergeFromImpl(implOf(other)).graph
  }

  /**
   * The union of the two graphs, joined by unit ids, plus the version of [other]
   * re-expressed in the result graph's lvs. A run of [other] that this graph knows
   * in part contributes only its unknown suffix, as a new run.
   */
  fun mergeFromImpl(other: EventGraphImpl): MergeResult {
    var graph = this

    fun remap(otherLv: LV): LV {
      val run = other.runAt(otherLv)
      val event = run.event
      val lv = graph.store.lvOfSeq(event.agent(), event.seq() + (otherLv - run.lvStart), graph.size)
      checkRemapped(lv, otherLv)
      return lv
    }

    for (i in 0 until other.runCount) {
      val run = other.store.runAt(i)
      val event = run.event
      val length = event.length()
      // Count the leading units this graph already has. A parent always precedes its
      // child, so under causal delivery the known part is always a prefix.
      var known = 0
      while (known < length) {
        val destLv = graph.store.lvOfSeq(event.agent(), event.seq() + known, graph.size)
        if (destLv < 0) {
          break
        }
        val destRun = graph.runAt(destLv)
        known += minOf(length - known, destRun.lvEnd() - destLv)
      }
      if (known >= length) {
        continue
      }
      val parents = if (known == 0) {
        val remapped = IntArray(run.parents.size) { j -> remap(run.parents[j]) }
        remapped.sort()
        remapped
      }
      else {
        intArrayOf(graph.store.lvOfSeq(event.agent(), event.seq() + known - 1, graph.size))
      }
      graph = graph.appendImpl(suffixOf(event, known), VersionImpl(parents))
    }

    val remappedVersion = IntArray(other.version.lvs.size) { i -> remap(other.version.lvs[i]) }
    remappedVersion.sort()
    return MergeResult(graph, VersionImpl(remappedVersion))
  }

  override fun replay(version: Version): DocText {
    val versionImpl = VersionImpl.implOf(version)
    checkVersionOfThisGraph(versionImpl)
    val text = StringBuilder()
    EgWalkerReplay.replay(this, versionImpl, StringBuilderSink(text))
    return DocText.createText(text)
  }

  // ------------------------------------------------------------------- queries for the replay

  /** The run that covers [lv]. */
  fun runAt(lv: LV): StoredRun {
    checkLv(lv)
    var lo = 0
    var hi = runCount - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (store.runAt(mid).lvStart <= lv) {
        lo = mid
      }
      else {
        hi = mid - 1
      }
    }
    return store.runAt(lo)
  }

  fun parentsOf(lv: LV): IntArray {
    val run = runAt(lv)
    return if (lv == run.lvStart) run.parents else intArrayOf(lv - 1)
  }

  fun isDeleteAt(lv: LV): Boolean {
    return runAt(lv).event is Event.Delete
  }

  fun posAt(lv: LV): Int {
    val run = runAt(lv)
    val event = run.event
    return if (event is Event.Insert) event.pos() + (lv - run.lvStart) else event.pos()
  }

  fun charAt(lv: LV): Char {
    val run = runAt(lv)
    val insert = run.event as? Event.Insert
    checkInsertAt(insert, lv)
    return insert!!.content()[lv - run.lvStart]
  }

  /**
   * The tie-break order for concurrent insertions: by agent, then by seq.
   * The reference implementation calls this `lvCmp`.
   */
  fun compareEvents(lvA: LV, lvB: LV): Int {
    val runA = runAt(lvA)
    val runB = runAt(lvB)
    val byAgent = runA.event.agent().compareTo(runB.event.agent())
    if (byAgent != 0) {
      return byAgent
    }
    val seqA = runA.event.seq() + (lvA - runA.lvStart)
    val seqB = runB.event.seq() + (lvB - runB.lvStart)
    return seqA.compareTo(seqB)
  }

  /** The paper's `Events(V)`: [version] and all its ancestors, as a set of lvs. */
  fun eventsOf(version: VersionImpl): BitSet {
    val seen = BitSet(size)
    val stack = ArrayDeque<Int>()
    for (lv in version.lvs) {
      stack.addLast(lv)
    }
    while (stack.isNotEmpty()) {
      val lv = stack.removeLast()
      if (seen.get(lv)) {
        continue
      }
      // The units before lv in the same run are its chain of ancestors: mark them at once.
      val run = runAt(lv)
      seen.set(run.lvStart, lv + 1)
      for (parent in run.parents) {
        if (!seen.get(parent)) {
          stack.addLast(parent)
        }
      }
    }
    return seen
  }

  /**
   * The lvs only in the history of [a] and only in the history of [b].
   * Both results are ascending. A per-unit port of `diff` from the reference
   * implementation's causal-graph library.
   */
  fun diff(a: IntArray, b: IntArray): Diff {
    val flags = HashMap<Int, Int>()
    val queue = PriorityQueue<Int>(11, Collections.reverseOrder())
    var numShared = 0

    fun enqueue(lv: LV, flag: Int) {
      val current = flags[lv]
      if (current == null) {
        queue.add(lv)
        flags[lv] = flag
        if (flag == FLAG_SHARED) {
          numShared++
        }
      }
      else if (flag != current && current != FLAG_SHARED) {
        flags[lv] = FLAG_SHARED
        numShared++
      }
    }

    for (lv in a) {
      enqueue(lv, FLAG_A)
    }
    for (lv in b) {
      enqueue(lv, FLAG_B)
    }

    val aOnly = ArrayList<Int>()
    val bOnly = ArrayList<Int>()
    while (queue.size > numShared) {
      val lv = queue.poll()
      val flag = flags[lv]!!
      when (flag) {
        FLAG_SHARED -> numShared--
        FLAG_A -> aOnly.add(lv)
        else -> bOnly.add(lv)
      }
      for (parent in parentsOf(lv)) {
        enqueue(parent, flag)
      }
    }

    // The queue pops in descending order; the results must ascend.
    aOnly.reverse()
    bOnly.reverse()
    return Diff(aOnly.toIntArray(), bOnly.toIntArray())
  }

  /**
   * Finds the common ancestor of the versions [a] and [b], and splits the region above
   * it into [Conflict.conflictLvs] (units in the history of [a], or of both) and
   * [Conflict.newLvs] (units only in the history of [b]). Both results ascend.
   *
   * A per-unit port of `findConflicting` from the reference implementation's
   * causal-graph library: a max-first walk over version points; paths merge when they
   * name the same version, and the walk stops when one point survives -- the ancestor.
   */
  fun findConflicting(a: IntArray, b: IntArray): Conflict {
    val conflictLvs = ArrayList<Int>()
    val newLvs = ArrayList<Int>()
    val queue = PriorityQueue(11, POINT_MAX_FIRST)
    queue.add(Point(descending(a), FLAG_A))
    queue.add(Point(descending(b), FLAG_B))
    val commonAncestor: IntArray = run {
      while (true) {
        val point = queue.poll()
        var flag = point.flag
        if (point.v.isEmpty()) {
          // The walk reached the root: there is no common history below this point.
          return@run IntArray(0)
        }
        // Merge the queued points that name the same version.
        while (queue.isNotEmpty() && queue.peek().v.contentEquals(point.v)) {
          if (queue.poll().flag != flag) {
            flag = FLAG_SHARED
          }
        }
        if (queue.isEmpty()) {
          return@run ascending(point.v)
        }
        // Shatter a merger point; the head unit is processed below.
        for (i in 1 until point.v.size) {
          queue.add(Point(intArrayOf(point.v[i]), flag))
        }
        val head = point.v[0]
        // Consume the points whose head is the same unit.
        while (queue.isNotEmpty() && queue.peek().v.isNotEmpty() && queue.peek().v[0] == head) {
          val same = queue.poll()
          if (same.flag != flag) {
            flag = FLAG_SHARED
          }
          for (i in 1 until same.v.size) {
            queue.add(Point(intArrayOf(same.v[i]), same.flag))
          }
        }
        if (queue.isEmpty()) {
          // The head is the sole survivor: the ancestor, and the walk stops below it.
          return@run intArrayOf(head)
        }
        if (flag == FLAG_B) {
          newLvs.add(head)
        }
        else {
          conflictLvs.add(head)
        }
        queue.add(Point(descending(parentsOf(head)), flag))
      }
      @Suppress("UNREACHABLE_CODE")
      IntArray(0)
    }
    // The walk emits in descending order; the results must ascend.
    conflictLvs.reverse()
    newLvs.reverse()
    return Conflict(commonAncestor, conflictLvs.toIntArray(), newLvs.toIntArray())
  }

  // ------------------------------------------------------------------------------------ checks

  private fun checkVersionOfThisGraph(version: VersionImpl) {
    val lvs = version.lvs
    require(lvs.isEmpty() || lvs[lvs.size - 1] < size) {
      "The version $version does not belong to a graph of size $size"
    }
  }

  private fun checkNewIds(event: Event) {
    require(!store.overlaps(event.agent(), event.seq(), event.length(), size)) {
      "An event id of $event is already in the graph"
    }
  }

  private fun checkLv(lv: LV) {
    require(lv in 0 until size) {
      "The lv $lv is out of the graph of size $size"
    }
  }

  private fun checkRemapped(lv: LV, otherLv: LV) {
    require(lv >= 0) {
      "The unit at the other graph's lv $otherLv is not in the merged graph"
    }
  }

  private fun checkInsertAt(insert: Event.Insert?, lv: LV) {
    require(insert != null) {
      "The lv $lv is not an insert"
    }
  }

  private fun suffixOf(event: Event, from: Int): Event {
    if (from == 0) {
      return event
    }
    return when (event) {
      is Event.Insert -> Event.createInsert(
        event.agent(),
        event.seq() + from,
        event.pos() + from,
        event.content().subSequence(from, event.length()),
      )
      is Event.Delete -> Event.createDelete(
        event.agent(),
        event.seq() + from,
        event.pos(),
        event.length() - from,
      )
    }
  }

  private class StringBuilderSink(private val text: StringBuilder) : EgWalkerReplay.Sink {
    override fun insert(pos: Int, character: Char) {
      text.insert(pos, character)
    }

    override fun delete(pos: Int) {
      text.deleteCharAt(pos)
    }
  }

  internal class MergeResult(val graph: EventGraphImpl, val remappedOtherVersion: VersionImpl)

  internal class Diff(val aOnly: IntArray, val bOnly: IntArray)

  internal class Conflict(val commonAncestor: IntArray, val conflictLvs: IntArray, val newLvs: IntArray)

  /** A version under the walk of [findConflicting]: the lvs sorted descending, plus the flag. */
  private class Point(val v: IntArray, val flag: Int)

  companion object {
    private const val FLAG_A = 0
    private const val FLAG_B = 1
    private const val FLAG_SHARED = 2

    /** Orders the walk points of [findConflicting]: the greatest version first. */
    private val POINT_MAX_FIRST = Comparator<Point> { p1, p2 ->
      val a = p1.v
      val b = p2.v
      val common = minOf(a.size, b.size)
      for (i in 0 until common) {
        if (a[i] != b[i]) {
          return@Comparator b[i] - a[i]
        }
      }
      if (a.size != b.size) {
        return@Comparator b.size - a.size
      }
      p2.flag - p1.flag
    }

    private fun descending(lvs: IntArray): IntArray = lvs.sortedArrayDescending()

    private fun ascending(lvs: IntArray): IntArray = lvs.sortedArray()

    fun empty(): EventGraphImpl {
      return EventGraphImpl(EventStore.empty(), 0, 0, VersionImpl.ROOT)
    }

    fun implOf(graph: EventGraph): EventGraphImpl {
      require(graph is EventGraphImpl) {
        "Foreign EventGraph implementation: ${graph.javaClass.name}"
      }
      return graph
    }

    /** The new frontier after an append: `(frontier - parents) + newLastLv`. */
    private fun advanceFrontier(frontier: IntArray, parents: IntArray, newLastLv: LV): IntArray {
      var kept = 0
      for (lv in frontier) {
        if (Arrays.binarySearch(parents, lv) < 0) {
          kept++
        }
      }
      val result = IntArray(kept + 1)
      var i = 0
      for (lv in frontier) {
        if (Arrays.binarySearch(parents, lv) < 0) {
          result[i] = lv
          i++
        }
      }
      result[i] = newLastLv // the new lv is greater than every existing lv, so the order holds
      return result
    }
  }
}

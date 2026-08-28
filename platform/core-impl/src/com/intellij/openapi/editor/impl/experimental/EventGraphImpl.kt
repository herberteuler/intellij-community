// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocText
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph
import com.intellij.openapi.editor.experimental.Version
import java.util.Arrays
import java.util.BitSet
import java.util.Collections
import java.util.PriorityQueue

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

  override fun append(event: Event, parents: Version): EventGraph {
    return appendImpl(event, VersionImpl.implOf(parents))
  }

  override fun mergeFrom(other: EventGraph): EventGraph {
    return mergeFromImpl(implOf(other)).graph
  }

  override fun replay(version: Version): DocText {
    val versionImpl = VersionImpl.implOf(version)
    checkVersionOfThisGraph(versionImpl)
    val text = StringBuilder()
    EgWalkerReplay.replay(this, versionImpl, StringBuilderSink(text))
    return DocText.createText(text)
  }

  fun versionImpl(): VersionImpl {
    return version
  }

  /** Appends the [event] run at this graph's own frontier. */
  fun appendAtTip(event: Event): EventGraphImpl {
    return appendImpl(event, version)
  }

  /** The next free seq of [agent] in this graph. */
  fun nextSeqFor(agent: Agent): Int {
    return store.nextSeq(agent, size)
  }

  /**
   * The union of the two graphs, joined by unit ids, plus the version of [other]
   * re-expressed in the result graph's lvs. A run of [other] that this graph knows
   * in part contributes only its unknown suffix, as a new run.
   *
   * The merge is not atomic. It appends run by run, so a rejected run leaves the earlier
   * ones in the shared store with no graph above them. The caller keeps its old value, so
   * the text stays correct. The orphans cost one prefix copy at the next append.
   */
  fun mergeFromImpl(other: EventGraphImpl): MergeResult {
    var graph = this

    fun remap(otherLv: LV): LV {
      val run = other.runAt(otherLv)
      val event = run.event
      val lv = graph.store.lvOfSeq(
        agent = event.agent(),
        seq = event.seq() + (otherLv - run.lvStart),
        lvLimit = graph.size,
      )
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
        val destLv = graph.store.lvOfSeq(
          agent = event.agent(),
          seq = event.seq() + known,
          lvLimit = graph.size,
        )
        if (destLv < 0) {
          break
        }
        val destRun = graph.runAt(destLv)
        val overlap = minOf(length - known, destRun.lvEnd() - destLv)
        checkSameOverlap(destRun, destLv, event, known, overlap)
        known += overlap
      }
      if (known >= length) {
        continue
      }
      val parents = if (known == 0) {
        val remapped = IntArray(run.parents.size) { j: Int ->
          remap(run.parents[j])
        }
        remapped.sort()
        remapped
      } else {
        val lv = graph.store.lvOfSeq(
          agent = event.agent(),
          seq = event.seq() + known - 1,
          lvLimit = graph.size,
        )
        intArrayOf(lv)
      }
      graph = graph.appendImpl(suffixOf(event, known), VersionImpl(parents))
    }

    val remappedVersion = IntArray(other.version.lvs.size) { i: Int ->
      remap(other.version.lvs[i])
    }
    remappedVersion.sort()
    return MergeResult(graph, VersionImpl(remappedVersion))
  }

  fun appendImpl(event: Event, parents: VersionImpl): EventGraphImpl {
    checkVersionOfThisGraph(parents)
    checkLvSpace(event)
    checkNewIds(event)
    val run = StoredRun(event, size, parents.lvs)
    val newStore = store.appendAt(size, runCount, run)
    val newLastLv = size + event.length() - 1
    val newVersion = VersionImpl(advanceFrontier(version.lvs, parents.lvs, newLastLv))
    return EventGraphImpl(newStore, runCount + 1, size + event.length(), newVersion)
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
      } else {
        hi = mid - 1
      }
    }
    return store.runAt(lo)
  }

  fun parentsOf(lv: LV): IntArray {
    val run = runAt(lv)
    return if (lv == run.lvStart) {
      run.parents
    } else {
      intArrayOf(lv - 1)
    }
  }

  fun isDeleteAt(lv: LV): Boolean {
    return runAt(lv).event is Event.Delete
  }

  /**
   * The lv after the last unit of the run that covers [lv]. Every unit of a run after the
   * first has the one implicit parent `lv - 1`, so a walk can consume a whole run at once.
   */
  fun runEndOf(lv: LV): LV {
    return runAt(lv).lvEnd()
  }

  fun posAt(lv: LV): Int {
    val run = runAt(lv)
    return unitPos(run.event, lv - run.lvStart)
  }

  fun charAt(lv: LV): Char {
    val run = runAt(lv)
    val insert = run.event as? Event.Insert
    require(insert != null) {
      "The lv $lv is not an insert"
    }
    return insert.content()[lv - run.lvStart]
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
        } else {
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

  private fun checkLvSpace(event: Event) {
    require(event.length() <= Int.MAX_VALUE - size) {
      "The graph unit space overflows: size $size + run length ${event.length()}"
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

  /**
   * Fails when the [overlap] units that [event] names from [offset] differ from the units
   * the merged graph already holds from [destLv], inside [destRun].
   *
   * One (agent, seq) pair names one operation forever. A difference means two branches
   * minted the same id, which the agent contract of
   * [com.intellij.openapi.editor.experimental.DocBranch] forbids: a branch that edits
   * concurrently must come from `fork(agent)`. Without this check the merge keeps one
   * operation, drops the other, and reports nothing. The two branches then disagree, and
   * a merge stops giving the same text in both directions.
   *
   * The check samples the two ends of the overlap, so it costs O(1) and adds no lookup:
   * [destRun] is already in hand. It does not see a difference that sits only strictly
   * inside a long overlap. A full content compare would make every merge cost the
   * document size, and a merge must cost the size of the concurrent region.
   */
  private fun checkSameOverlap(destRun: StoredRun, destLv: LV, event: Event, offset: Int, overlap: Int) {
    checkSameUnit(destRun, destLv, event, offset)
    if (overlap > 1) {
      checkSameUnit(destRun, destLv + overlap - 1, event, offset + overlap - 1)
    }
  }

  private fun checkSameUnit(destRun: StoredRun, destLv: LV, event: Event, offset: Int) {
    val destEvent = destRun.event
    val destOffset = destLv - destRun.lvStart
    require((destEvent is Event.Delete) == (event is Event.Delete)) {
      idClash(event, offset, "the operation kind")
    }
    require(unitPos(destEvent, destOffset) == unitPos(event, offset)) {
      idClash(event, offset, "the position")
    }
    if (destEvent is Event.Insert && event is Event.Insert) {
      require(destEvent.content()[destOffset] == event.content()[offset]) {
        idClash(event, offset, "the inserted character")
      }
    }
  }

  private fun idClash(event: Event, offset: Int, difference: String): String {
    return "Two events share the id (${event.agent()}, ${event.seq() + offset}) and differ in $difference. " +
           "One agent authored both; a branch that edits concurrently must come from fork(agent)."
  }

  /** The position of the unit at [offset] inside the run of [event]. */
  private fun unitPos(event: Event, offset: Int): Int {
    // A delete removes at one position repeatedly; an insert walks forward.
    return if (event is Event.Insert) event.pos() + offset else event.pos()
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

  internal class MergeResult(val graph: EventGraphImpl, val remappedOtherVersion: VersionImpl)

  internal class Diff(val aOnly: IntArray, val bOnly: IntArray)

  internal class Conflict(val commonAncestor: IntArray, val conflictLvs: IntArray, val newLvs: IntArray)

  private class StringBuilderSink(private val text: StringBuilder) : EgWalkerReplay.Sink {
    override fun insert(pos: Int, character: Char) {
      text.insert(pos, character)
    }

    override fun delete(pos: Int) {
      text.deleteCharAt(pos)
    }
  }

  /** A version under the walk of [findConflicting]: the lvs sorted descending, plus the flag. */
  private class Point(val v: IntArray, val flag: Int)

  companion object {
    fun empty(): EventGraphImpl {
      return EventGraphImpl(
        store = EventStore.empty(),
        runCount = 0,
        size = 0,
        version = VersionImpl.ROOT,
      )
    }

    fun implOf(graph: EventGraph): EventGraphImpl {
      require(graph is EventGraphImpl) {
        "Foreign EventGraph implementation: ${graph.javaClass.name}"
      }
      return graph
    }

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

    private fun descending(lvs: IntArray): IntArray {
      return lvs.sortedArrayDescending()
    }

    private fun ascending(lvs: IntArray): IntArray {
      return lvs.sortedArray()
    }
  }
}

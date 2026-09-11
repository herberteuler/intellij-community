// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocText
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph
import com.intellij.openapi.editor.experimental.Version
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
   * The cost is the size of the CHANGE, not the size of either history. A [VersionSummary]
   * holds one integer per agent, so the two graphs compare their histories without reading
   * a run, and only the runs that go past this graph are ever loaded.
   *
   * The merge is not atomic. It appends run by run, so a rejected run leaves the earlier
   * ones in the shared store with no graph above them. The caller keeps its old value, so
   * the text stays correct. The orphans cost one prefix copy at the next append.
   */
  fun mergeFromImpl(other: EventGraphImpl): MergeResult {
    var graph = this

    fun remap(otherLv: LV): LV {
      val run = other.runAt(otherLv)
      val lv = graph.store.lvOfSeq(
        agent = run.event.agent(),
        seq = run.seqAt(otherLv),
        lvLimit = graph.size,
      )
      checkRemapped(lv, otherLv)
      return lv
    }

    // The delta: what this graph knows, then only the runs of the other graph that go
    // past it. Neither step reads a run the two graphs share, so a merge costs the
    // CHANGE and not the session. The reference does this with summarizeVersion and
    // intersectWithSummary.
    val summary = store.summarizeVersion(size)
    checkSharedIds(other, summary)
    for (lvStart in other.store.newRunStarts(summary, other.size)) {
      val run = other.runAt(lvStart)
      val event = run.event
      // This graph holds the seqs [0, endSeq) of the agent, so what it already has of
      // this run is a leading piece, and this is how long that piece is.
      val known = (summary.endSeq(event.agent()) - event.seq()).coerceIn(0, event.length())
      val parents = if (known == 0) {
        val runParents = run.runParents()
        val remapped = IntArray(runParents.size) { j: Int ->
          remap(runParents[j])
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
      graph = graph.appendImpl(event.suffixFrom(known), VersionImpl(parents))
    }

    val remappedVersion = IntArray(other.version.lvs.size) { i: Int ->
      remap(other.version.lvs[i])
    }
    remappedVersion.sort()
    return MergeResult(graph, size, VersionImpl(remappedVersion))
  }

  private fun appendImpl(event: Event, parents: VersionImpl): EventGraphImpl {
    checkVersionOfThisGraph(parents)
    checkLvSpace(event)
    checkNextSeq(event)
    val run = StoredRun(event, size, parents.lvs)
    val newStore = store.appendAt(size, runCount, run)
    val newLastLv = size + event.length() - 1
    return EventGraphImpl(newStore, runCount + 1, size + event.length(), version.advancedBy(parents, newLastLv))
  }

  // ------------------------------------------------------------------- queries for the replay

  /** The run that covers [lv]. */
  fun runAt(lv: LV): StoredRun {
    return runByIndex(runIndexOf(lv))
  }

  /** The index of the run that covers [lv]. An index counts runs, and an lv counts units. */
  fun runIndexOf(lv: LV): Int {
    checkLv(lv)
    var lo = 0
    var hi = runCount - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (store.runAt(mid).startsAtOrBefore(lv)) {
        lo = mid
      } else {
        hi = mid - 1
      }
    }
    return lo
  }

  /** The run stored at [index], which must be below [runCount]. */
  fun runByIndex(index: Int): StoredRun {
    return store.runAt(index)
  }

  fun parentsOf(lv: LV): Frontier {
    return runAt(lv).parentsOf(lv)
  }

  fun isDeleteAt(lv: LV): Boolean {
    return runAt(lv).isDelete
  }

  /**
   * The lv after the last unit of the run that covers [lv]. Every unit of a run after the
   * first has the one implicit parent `lv - 1`, so a walk can consume a whole run at once.
   */
  fun runEndOf(lv: LV): LV {
    return runAt(lv).lvEnd()
  }

  /** The offset that the unit [lv] edits, in the document at its own parent version. */
  fun offsetAt(lv: LV): Int {
    return runAt(lv).offsetAt(lv)
  }

  /** The characters that the [count] units from [lv] insert, which must be one insert run. */
  fun fragmentAt(lv: LV, count: Int): CharSequence {
    return runAt(lv).fragmentFrom(lv, count)
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
    return runA.seqAt(lvA).compareTo(runB.seqAt(lvB))
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
      for (parent in run.runParents()) {
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
  fun diff(a: Frontier, b: Frontier): Diff {
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
      } else if (flag != current && current != FLAG_SHARED) {
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
  fun findConflicting(a: Frontier, b: Frontier): Conflict {
    val conflictLvs = ArrayList<Int>()
    val newLvs = ArrayList<Int>()
    val queue = PriorityQueue(11, POINT_MAX_FIRST)
    queue.add(Point(descending(a), FLAG_A))
    queue.add(Point(descending(b), FLAG_B))
    val commonAncestor: Frontier = run {
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
    return Conflict(VersionImpl(commonAncestor), conflictLvs.toIntArray(), newLvs.toIntArray())
  }

  // ------------------------------------------------------------------------------------ checks

  private fun checkVersionOfThisGraph(version: VersionImpl) {
    require(version.unitSpan() <= size) {
      "The version $version does not belong to a graph of size $size"
    }
  }

  /**
   * Fails when [event] does not continue the seqs of its agent.
   *
   * The seqs of one agent must ascend and leave no gap. That rejects a reused id, which is
   * the rule one (agent, seq) pair names one unit forever. It also buys the delta merge:
   * a graph then holds exactly the seqs `[0, nextSeq)` of each agent, so a
   * [VersionSummary] is one integer per agent instead of a set of ranges.
   *
   * Each agent owns its own seq space, so two agents interleave freely.
   */
  private fun checkNextSeq(event: Event) {
    val expected = nextSeqFor(event.agent())
    require(event.seq() == expected) {
      "The event $event does not continue the seqs of its agent: expected $expected"
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
   * Fails when the two graphs disagree about an id they both hold.
   *
   * One (agent, seq) pair names one operation forever. A difference means two branches
   * minted the same id, which the agent contract of
   * [com.intellij.openapi.editor.experimental.DocBranch] forbids: a branch that edits
   * concurrently must come from `fork(agent)`. Without this check the merge keeps one
   * operation, drops the other, and reports nothing. The two branches then disagree, and
   * a merge stops giving the same text in both directions.
   *
   * Both graphs hold the seqs `[0, endSeq)` of an agent, so the shared range of an agent is
   * `[0, min(endSeq, endSeq))`. The check SAMPLES its two ends, so it costs two lookups per
   * shared agent and nothing per run. It does not see a difference that sits strictly inside
   * the shared range. A full compare would make every merge cost the whole shared history,
   * and a merge must cost the size of the change.
   */
  private fun checkSharedIds(other: EventGraphImpl, summary: VersionSummary) {
    val otherSummary = other.store.summarizeVersion(other.size)
    for (agent in otherSummary.agents()) {
      val sharedEnd = minOf(summary.endSeq(agent), otherSummary.endSeq(agent))
      if (sharedEnd == 0) {
        continue
      }
      checkSameId(other, agent, 0)
      if (sharedEnd > 1) {
        checkSameId(other, agent, sharedEnd - 1)
      }
    }
  }

  /** Fails when the unit ([agent], [seq]) is a different operation in the two graphs. */
  private fun checkSameId(other: EventGraphImpl, agent: Agent, seq: Int) {
    val lv = store.lvOfSeq(agent, seq, size)
    val otherLv = other.store.lvOfSeq(agent, seq, other.size)
    require(lv >= 0 && otherLv >= 0) {
      "The id ($agent, $seq) is inside the shared range, but one graph does not hold it"
    }
    val run = runAt(lv)
    val otherRun = other.runAt(otherLv)
    require(run.isDelete == otherRun.isDelete) {
      idClash(agent, seq, "the operation kind")
    }
    require(run.offsetAt(lv) == otherRun.offsetAt(otherLv)) {
      idClash(agent, seq, "the position")
    }
    // The kind check passed, so a run that is not a delete is an insert in both graphs.
    if (!run.isDelete) {
      require(run.charAt(lv) == otherRun.charAt(otherLv)) {
        idClash(agent, seq, "the inserted character")
      }
    }
  }

  /** The graph as a text diagram. See [EventGraphDiagram] for the notation and its limits. */
  override fun toString(): String {
    return EventGraphDiagram.render(this)
  }

  private fun idClash(agent: Agent, seq: Int, difference: String): String {
    return "Two events share the id ($agent, $seq) and differ in $difference. " +
           "One agent authored both; a branch that edits concurrently must come from fork(agent)."
  }

  /**
   * The union graph, plus the two questions a caller asks about it. [sourceSize] is the
   * size of the graph the merge started from, so the result can answer them itself.
   */
  internal class MergeResult(
    val graph: EventGraphImpl,
    private val sourceSize: Int,
    private val remappedOtherVersion: VersionImpl,
  ) {
    /** Whether the other graph brought nothing: its whole history was already here. */
    fun addsNothing(): Boolean {
      return graph.size == sourceSize
    }

    /**
     * Whether the source history sits inside the other one. The other branch's text is
     * then already the merged text, so no replay is needed.
     */
    fun isFastForward(): Boolean {
      return graph.version == remappedOtherVersion
    }

    /**
     * States the two answers and the size of the union, and NOT the graph. A graph prints a
     * whole box diagram, which no message wants inside another one.
     */
    override fun toString(): String {
      return "MergeResult(units=${graph.size}, runs=${graph.runCount}, " +
             "addsNothing=${addsNothing()}, fastForward=${isFastForward()})"
    }
  }

  internal class Diff(val aOnly: LvList, val bOnly: LvList) {
    /** Whether the two versions name the same event set, so no item changes state. */
    fun isEmpty(): Boolean {
      return aOnly.isEmpty() && bOnly.isEmpty()
    }

    override fun toString(): String {
      return "Diff(aOnly=${aOnly.listedForMessage()}, bOnly=${bOnly.listedForMessage()})"
    }
  }

  /**
   * The region above the common ancestor, split by which side holds it.
   *
   * [commonAncestor] is a version, while [conflictLvs] and [newLvs] are lists of every
   * unit to walk. A real type marks the difference here, because the two are used side by
   * side and a swap would replay the wrong thing.
   */
  internal class Conflict(val commonAncestor: VersionImpl, val conflictLvs: LvList, val newLvs: LvList) {
    override fun toString(): String {
      return "Conflict(ancestor=$commonAncestor, conflict=${conflictLvs.listedForMessage()}, " +
             "new=${newLvs.listedForMessage()})"
    }
  }

  private class StringBuilderSink(private val text: StringBuilder) : EgWalkerReplay.Sink {
    override fun insert(effectPos: Int, fragment: CharSequence) {
      text.insert(effectPos, fragment)
    }

    override fun delete(effectPos: Int, count: Int) {
      text.delete(effectPos, effectPos + count)
    }

    override fun toString(): String {
      return text.toString()
    }
  }

  /** A version under the walk of [findConflicting]: the lvs sorted descending, plus the flag. */
  private class Point(val v: Frontier, val flag: Int) {
    override fun toString(): String {
      return "Point(${v.listedForMessage()}, ${flagName(flag)})"
    }
  }

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

    /** The side a walk flag names, as a word. */
    private fun flagName(flag: Int): String {
      return when (flag) {
        FLAG_A -> "a"
        FLAG_B -> "b"
        else -> "shared"
      }
    }

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

    private fun descending(lvs: Frontier): Frontier {
      return lvs.sortedArrayDescending()
    }

    private fun ascending(lvs: Frontier): Frontier {
      return lvs.sortedArray()
    }
  }
}

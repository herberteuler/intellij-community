// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent

/**
 * The shared append-only storage behind [EventGraphImpl] values, run-length encoded:
 * one slot per [StoredRun], not per character.
 *
 * Successive graphs of one linear chain share one store; each graph sees the run
 * prefix that covers its first `size` lvs. When a graph that is not the tip appends,
 * the store copies that prefix into a new store, so the old chain stays untouched.
 *
 * Thread safety: appends and the per-agent id index synchronize on this store. Reads of
 * committed run slots do not synchronize. Three facts make that safe. A slot is written
 * before the graph that covers it is constructed. A graph reaches another thread only
 * through a safe publication of that graph value. [runs] is volatile, so a reader cannot
 * observe an array from before that publication, and the array only grows. A committed
 * slot then keeps its index and its value forever.
 *
 * [com.intellij.openapi.editor.impl.DocTextImpl] uses a different argument for its line
 * set. That field is a benign cache: a racy reader sees `null` and recomputes. A run slot
 * has no recompute path, so it needs the publication edge above.
 */
internal class EventStore private constructor(
  @Volatile private var runs: Array<StoredRun?>,
  private var committedRuns: Int,
  private var committedLvs: Int,
  private val agentIndex: HashMap<Agent, ArrayList<AgentRun>>,
) {

  fun runAt(index: Int): StoredRun {
    val run = runs[index]
    require(run != null) {
      "The run slot $index is not committed"
    }
    return run
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
        } else {
          hi = mid - 1
        }
      }
      if (floor == null || !floor.covers(seq) || !floor.isVisible(lvLimit)) {
        return -1
      }
      return floor.lvOf(seq)
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
        if (list[mid].endSeq() <= seq) {
          lo = mid + 1
        } else {
          hi = mid
        }
      }
      // Walk the contiguous block of entries that intersect `[seq, seq + length)`.
      var i = lo
      while (i < list.size && list[i].overlaps(seq, length)) {
        if (list[i].isVisible(lvLimit)) {
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
        if (entry.isVisible(lvLimit)) {
          return entry.endSeq()
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
    val list = agentIndex.getOrPut(event.agent()) {
      ArrayList()
    }
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

  private fun checkDivergedAppend(appended: Boolean) {
    require(appended) {
      "An append into a fresh prefix copy failed"
    }
  }

  /**
   * The committed size and the agents that authored it. This takes the monitor, because the
   * two counters belong to it, and a `toString` must not be the one reader that skips it.
   */
  override fun toString(): String {
    synchronized(this) {
      return "EventStore(runs=$committedRuns, units=$committedLvs, agents=${agentIndex.keys.sorted()})"
    }
  }

  /**
   * One per-agent index entry: the seqs `[seqStart, endSeq())` start at [lvStart]. The
   * entry owns the translation between a seq and an lv.
   */
  private class AgentRun(
    val seqStart: Int,
    private val length: Int,
    val lvStart: LV,
  ) {
    /** The first seq after this entry. */
    fun endSeq(): Int {
      return seqStart + length
    }

    /** Whether this entry names [seq]. */
    fun covers(seq: Int): Boolean {
      return seq >= seqStart && seq < endSeq()
    }

    /** Whether this entry names any seq in `[seq, seq + count)`. */
    fun overlaps(seq: Int, count: Int): Boolean {
      return seqStart < seq + count && seq < endSeq()
    }

    /** The lv of [seq], which this entry must cover. */
    fun lvOf(seq: Int): LV {
      return lvStart + (seq - seqStart)
    }

    /** Whether the graph bounded by [lvLimit] can see this entry at all. */
    fun isVisible(lvLimit: LV): Boolean {
      return lvStart < lvLimit
    }

    override fun toString(): String {
      return "seqs[$seqStart..${endSeq() - 1}] at lv $lvStart"
    }
  }

  companion object {
    private const val INITIAL_CAPACITY = 16

    fun empty(): EventStore {
      return EventStore(
        runs = arrayOfNulls(INITIAL_CAPACITY),
        committedRuns = 0,
        committedLvs = 0,
        agentIndex = HashMap(),
      )
    }
  }
}

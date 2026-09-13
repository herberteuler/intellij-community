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

  /**
   * The run in the slot [index], which counts RUNS. [EventGraphImpl.runAt] takes an lv, which
   * counts units, so the two names have to differ.
   */
  fun runByIndex(index: Int): StoredRun {
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

  /** The next free seq of [agent] among the runs below [lvLimit]. */
  fun nextSeq(agent: Agent, lvLimit: LV): Int {
    synchronized(this) {
      val list = agentIndex[agent] ?: return 0
      return endSeqBelow(list, lvLimit)
    }
  }

  /**
   * What the runs below [lvLimit] know, by agent. The reference implementation calls this
   * `summarizeVersion`. It costs one entry per agent and reads no run.
   */
  fun summarizeVersion(lvLimit: LV): VersionSummary {
    synchronized(this) {
      val endSeqs = HashMap<Agent, Int>(agentIndex.size)
      for ((agent, list) in agentIndex) {
        val endSeq = endSeqBelow(list, lvLimit)
        // An agent whose every run sits at or above lvLimit is invisible to this graph.
        if (endSeq > 0) {
          endSeqs[agent] = endSeq
        }
      }
      return VersionSummary(endSeqs)
    }
  }

  /**
   * The [StoredRun.lvStart] of every run below [lvLimit] that holds at least one unit
   * [summary] does not cover, ASCENDING. This is the delta: the merge appends exactly these
   * runs, and never looks at a run both graphs already share.
   *
   * The result MUST ascend, because a parent always sits at a smaller lv, and appending in
   * lv order therefore puts every parent in place before its child names it. The per-agent
   * lists each ascend already, so the sort only merges them.
   *
   * An entry this returns always holds a unit the [summary] lacks, so the caller never
   * builds an empty suffix event.
   */
  fun newRunStarts(summary: VersionSummary, lvLimit: LV): IntArray {
    synchronized(this) {
      val starts = ArrayList<Int>()
      for ((agent, list) in agentIndex) {
        var i = firstEntryPast(list, summary.endSeq(agent))
        // The entries ascend by lvStart too, so the first invisible one ends the agent.
        while (i < list.size && list[i].isVisible(lvLimit)) {
          starts.add(list[i].lvStart)
          i++
        }
      }
      starts.sort()
      return starts.toIntArray()
    }
  }

  /**
   * The first seq that the entries of one agent below [lvLimit] do not hold, or 0 when they
   * hold none. The entries ascend by seqStart AND by lvStart and leave no gap, so the last
   * visible entry holds the greatest seq.
   *
   * A graph's size is always a run boundary, because every append adds a whole run and
   * [copyPrefix] copies whole runs. So [lvLimit] never cuts an entry in half, and visibility
   * is a property of the whole entry. That is what makes this a binary search.
   */
  private fun endSeqBelow(list: ArrayList<AgentRun>, lvLimit: LV): Int {
    var lo = 0
    var hi = list.size
    while (lo < hi) {
      val mid = (lo + hi) ushr 1
      if (list[mid].isVisible(lvLimit)) {
        lo = mid + 1
      } else {
        hi = mid
      }
    }
    return if (lo == 0) 0 else list[lo - 1].endSeq()
  }

  /** The first entry of one agent that holds a seq at or after [seq]. The ends ascend. */
  private fun firstEntryPast(list: ArrayList<AgentRun>, seq: Int): Int {
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
    return lo
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
    checkSeqContinues(list, event.agent(), event.seq())
    list.add(AgentRun(event.seq(), event.length(), run.lvStart))
  }

  /**
   * Fails when the seq [seq] of [agent] does not continue the entries already in [list].
   *
   * The seqs of one agent ascend and leave no gap, so an entry always goes to the END of the
   * list, and one integer per agent then describes everything a graph knows. See
   * [VersionSummary]. Every search in this class rests on that order.
   *
   * `EventGraphImpl.checkNextSeq` already rejects such an append, so this never fires for a
   * caller that behaves. The store checks it anyway, because a break here would not throw
   * later: it would leave every binary search quietly wrong. It also covers [copyPrefix],
   * which replays the whole prefix through this method.
   */
  private fun checkSeqContinues(list: ArrayList<AgentRun>, agent: Agent, seq: Int) {
    val expected = if (list.isEmpty()) 0 else list[list.size - 1].endSeq()
    require(seq == expected) {
      "The seq $seq of $agent does not continue the stored seqs: expected $expected"
    }
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

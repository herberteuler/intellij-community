// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent

/**
 * The closed runs of one graph value, by agent: a [RunTree] per agent, keyed by the seq of each run.
 * A merge asks it which ids a graph holds, and an append asks it for the next seq.
 *
 * Documents have few agents, so the agents sit in one array sorted by agent, and an append copies
 * that array. The tree of the agent shares every node it does not change. The index is immutable,
 * and every field is final.
 *
 * The seqs of one agent ascend and leave no gap, so the runs of an agent cover the seqs
 * `[0, nextSeq(agent))`. Every query here rests on that order, and [appended] checks it.
 */
internal class AgentIndex private constructor(
  private val agents: Array<Agent>,
  private val trees: Array<RunTree>,
) {

  /** This index with [run] added to the tree of its agent. */
  fun appended(run: StoredRun): AgentIndex {
    val agent = run.event.agent()
    val slot = slotOf(agent)
    if (slot >= 0) {
      checkSeqContinues(trees[slot], run)
      val newTrees = trees.copyOf()
      newTrees[slot] = trees[slot].appended(run.event.seq(), run)
      return AgentIndex(agents, newTrees)
    }
    checkSeqContinues(RunTree.EMPTY, run)
    val insertAt = -slot - 1
    val newAgents = insertedAt(agents, insertAt, agent)
    val newTrees = insertedAt(trees, insertAt, RunTree.EMPTY.appended(run.event.seq(), run))
    return AgentIndex(newAgents, newTrees)
  }

  /** The first seq of [agent] that no closed run holds, or 0 when [agent] has none. */
  fun nextSeq(agent: Agent): Int {
    val slot = slotOf(agent)
    return if (slot < 0) 0 else endSeqOf(trees[slot])
  }

  /** The lv of the unit ([agent], [seq]) when a closed run holds it, or -1. */
  fun lvOfSeq(agent: Agent, seq: Int): LV {
    val slot = slotOf(agent)
    if (slot < 0) {
      return -1
    }
    val run = trees[slot].floor(seq)
    return if (run != null && run.holdsUnit(agent, seq)) run.lvOfSeq(seq) else -1
  }

  /** What the closed runs know, plus [newest], which is the tail of the graph, as one end seq per agent. */
  fun summarize(newest: StoredRun?): VersionSummary {
    val endSeqs = HashMap<Agent, Int>(agents.size + 1)
    for (slot in agents.indices) {
      endSeqs[agents[slot]] = endSeqOf(trees[slot])
    }
    if (newest != null) {
      endSeqs[newest.event.agent()] = newest.endSeq()
    }
    return VersionSummary(endSeqs)
  }

  /**
   * The [StoredRun.lvStart] of every closed run that holds a unit [summary] does not cover,
   * ascending. The runs of an agent cover its seqs without a gap, so the first such run is the one
   * that holds the first seq the summary lacks, and every later run of the agent follows it.
   */
  fun newRunStarts(summary: VersionSummary): IntArray {
    var count = 0
    val firsts = IntArray(agents.size)
    for (slot in agents.indices) {
      val known = summary.endSeq(agents[slot])
      val tree = trees[slot]
      firsts[slot] = if (known >= endSeqOf(tree)) tree.size() else tree.floorIndex(known)
      count += tree.size() - firsts[slot]
    }
    val starts = IntArray(count)
    var next = 0
    for (slot in agents.indices) {
      val tree = trees[slot]
      for (index in firsts[slot] until tree.size()) {
        starts[next] = tree.get(index).lvStart
        next++
      }
    }
    // Each agent's starts ascend already, so the sort only merges them.
    starts.sort()
    return starts
  }

  /** The slot of [agent], or `-(insertion point) - 1` when the index has no run of it. */
  private fun slotOf(agent: Agent): Int {
    var lo = 0
    var hi = agents.size - 1
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      val order = agents[mid].compareTo(agent)
      when {
        order < 0 -> lo = mid + 1
        order > 0 -> hi = mid - 1
        else -> return mid
      }
    }
    return -lo - 1
  }

  /**
   * Fails when [run] does not continue the seqs in [tree]. `EventGraphImpl.checkNextSeq` already
   * rejects such an append. The index checks it anyway, because a break here would not throw later:
   * it would leave every search here quietly wrong.
   */
  private fun checkSeqContinues(tree: RunTree, run: StoredRun) {
    val expected = endSeqOf(tree)
    require(run.event.seq() == expected) {
      "The seq ${run.event.seq()} of ${run.event.agent()} does not continue the stored seqs: expected $expected"
    }
  }

  override fun toString(): String {
    return "AgentIndex(${agents.indices.joinToString { "${agents[it]}:${trees[it].size()} runs" }})"
  }

  companion object {
    val EMPTY: AgentIndex = AgentIndex(emptyArray(), emptyArray())

    private fun endSeqOf(tree: RunTree): Int {
      return tree.last()?.endSeq() ?: 0
    }

    private inline fun <reified T> insertedAt(array: Array<T>, index: Int, element: T): Array<T> {
      return Array(array.size + 1) { i ->
        when {
          i < index -> array[i]
          i == index -> element
          else -> array[i - 1]
        }
      }
    }
  }
}

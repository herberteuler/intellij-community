// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.ex.experimental.Agent
import com.intellij.openapi.editor.ex.experimental.EventIdClashException

/**
 * Fails when the two graphs of a merge disagree about an id they both hold.
 *
 * One (agent, seq) pair names one operation forever. A difference means two branches minted the
 * same id, which the agent contract of [com.intellij.openapi.editor.ex.experimental.DocBranch] forbids:
 * a branch that edits concurrently must come from `fork(agent)`. Without this check the merge keeps
 * one operation, drops the other, and reports nothing. The two branches then disagree, and a merge
 * stops giving the same text in both directions.
 *
 * Both graphs hold the seqs `[0, endSeq)` of an agent. So the shared range of an agent runs from 0
 * to the smaller of the two end seqs. The check SAMPLES the two ends of that range. At each end it
 * compares the kind, the offset, the move offset, the character, and the parents. So it costs a few
 * lookups per shared agent and nothing per run. It does not see a difference strictly inside the
 * shared range.
 * A full compare would make every merge cost the whole shared history, and a merge must cost the
 * size of the change.
 */
internal class SharedIdCheck(private val graph: EventGraphImpl, private val other: EventGraphImpl) {

  /**
   * Samples every agent of [other] against [summary], which says what [graph] knows.
   */
  fun check(summary: VersionSummary) {
    val otherSummary = other.summarize()
    for (agent in otherSummary.agents()) {
      val sharedEnd = minOf(summary.endSeq(agent), otherSummary.endSeq(agent))
      if (sharedEnd == 0) {
        continue
      }
      checkSameId(agent, 0)
      if (sharedEnd > 1) {
        checkSameId(agent, sharedEnd - 1)
      }
    }
  }

  /**
   * Fails when the unit ([agent], [seq]) is a different operation in the two graphs.
   */
  private fun checkSameId(agent: Agent, seq: Int) {
    val lv = graph.lvOfUnit(agent, seq)
    val otherLv = other.lvOfUnit(agent, seq)
    require(lv >= 0 && otherLv >= 0) {
      "The id ($agent, $seq) is inside the shared range, but one graph does not hold it"
    }
    val run = graph.runAt(lv)
    val otherRun = other.runAt(otherLv)
    checkNoClash(run.isDelete() == otherRun.isDelete(), agent, seq, "the operation kind")
    checkNoClash(run.offsetAt(lv) == otherRun.offsetAt(otherLv), agent, seq, "the offset")
    checkNoClash(run.moveDistance() == otherRun.moveDistance(), agent, seq, "the move offset")
    // The kind check passed, so a run that is not a delete is an insert in both graphs.
    if (!run.isDelete()) {
      checkNoClash(run.charAt(lv) == otherRun.charAt(otherLv), agent, seq, "the inserted character")
    }
    checkNoClash(hasSameParentIds(lv, otherLv), agent, seq, "the parents")
  }

  /**
   * Fails with a typed exception, so a caller can tell a broken agent contract from a bug.
   */
  private fun checkNoClash(
    same: Boolean,
    agent: Agent,
    seq: Int,
    difference: String,
  ) {
    if (!same) {
      throw EventIdClashException(agent, seq, clashMessage(agent, seq, difference))
    }
  }

  /**
   * Whether the unit [lv] of [graph] and the unit [otherLv] of [other] have the same parents. The two
   * graphs of a merge give one unit different lvs, so only the ids compare. A unit usually has one
   * parent, and that case needs no set.
   */
  private fun hasSameParentIds(lv: LV, otherLv: LV): Boolean {
    val parents = graph.parentsOf(lv)
    val otherParents = other.parentsOf(otherLv)
    if (parents.size != otherParents.size) {
      return false
    }
    if (parents.size == 1) {
      return hasSameId(parents[0], otherParents[0])
    }
    val parentIdSet = parents.mapTo(HashSet()) {
      idOf(graph, it)
    }
    val otherParentIdSet = otherParents.mapTo(HashSet()) {
      idOf(other, it)
    }
    return parentIdSet == otherParentIdSet
  }

  /**
   * Whether the unit [lv] of [graph] and the unit [otherLv] of [other] have one id.
   */
  private fun hasSameId(lv: LV, otherLv: LV): Boolean {
    val run = graph.runAt(lv)
    val otherRun = other.runAt(otherLv)
    return run.event.agent() == otherRun.event.agent() &&
           run.seqAt(lv) == otherRun.seqAt(otherLv)
  }

  /**
   * The id of the unit [lv] of [of], as a pair for a set compare.
   */
  private fun idOf(of: EventGraphImpl, lv: LV): Pair<Agent, Int> {
    val run = of.runAt(lv)
    return run.event.agent() to run.seqAt(lv)
  }

  private fun clashMessage(agent: Agent, seq: Int, difference: String): String {
    return "Two events share the id ($agent, $seq) and differ in $difference. " +
           "One agent authored both, but a branch that edits concurrently must come from fork(agent)."
  }

  override fun toString(): String {
    return "SharedIdCheck(${graph.size()} units against ${other.size()} units)"
  }
}

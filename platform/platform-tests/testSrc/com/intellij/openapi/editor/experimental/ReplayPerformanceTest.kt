// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.testFramework.PerformanceUnitTest
import com.intellij.testFramework.junit5.StressTestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A benchmark, not a regression test: it measures what the replay pays to REPORT its output.
 *
 * [DocBranchPerformanceTest] cannot isolate this cost, because its runs have whatever length
 * its typing gives them. These scenarios instead fix the run length and vary the insert
 * position, which are the two things the report cost depends on.
 *
 * Why the position matters: the full replay builds the text in a `StringBuilder`, and an
 * insert shifts everything after it. A history that always inserts at the front therefore
 * pays the document length per insert. A history that appends pays nothing. So the front
 * case is the worst case for a report, and the append case is the best.
 *
 * [benchmarkSubtest] times every row with the platform benchmark framework. Compare the rows of
 * one run, and not the milliseconds of two builds. This benchmark once measured a 16x spread
 * between two runs of one unchanged build. The `len 1` row of a sweep is the control. One unit
 * per run leaves a per-character report and a per-run report the same work, so a ratio to that
 * row cancels the load out. Divide a published time by the passes of its row first.
 */
@StressTestApplication
@PerformanceUnitTest
class ReplayPerformanceTest {

  /**
   * A full replay of a history that always inserts at the front.
   *
   * The lookup stays cheap here: the position is 0, so it never walks the item list. Two costs
   * remain. The report shifts the text, and each new item shifts the item list, because it lands
   * at the index 0. So the row measures both, and an order-statistic tree would move it too.
   */
  @Test
  fun `a full replay of a history that inserts at the front`() {
    replaySweep("front", FRONT_PASSES_PER_RUN_UNIT) { _ -> 0 }
  }

  /**
   * A full replay of a history that appends. Every insert lands at the end, so a
   * `StringBuilder` shifts nothing whatever the report does.
   */
  @Test
  fun `a full replay of a history that appends`() {
    replaySweep("append", APPEND_PASSES_PER_RUN_UNIT) { length -> length }
  }

  /**
   * A merge of two concurrent pastes. This is the [DocOp] path through the batching sink, and
   * not the `StringBuilder` path, so it reports one op however many calls it takes to build.
   */
  @Test
  fun `a merge of two concurrent pastes`() {
    println("=== a merge of two concurrent pastes ===")
    println("  %-14s %10s".format("paste", "units"))
    for (paste in PASTE_SIZES) {
      val base = DocBranch.createBranch("base\n", agent("base"))
      val left = base.fork(agent("aaa")).applyOp(DocOp.ins(5, "L".repeat(paste)))
      val right = base.fork(agent("bbb")).applyOp(DocOp.ins(5, "R".repeat(paste)))
      assertEquals(5 + 2 * paste, left.merge(right).text().length())
      println("  %-14s %10d".format("$paste chars", 2 * paste))
      benchmarkSubtest("merge of two $paste-char pastes", PASTE_MERGE_PASSES) {
        left.merge(right).text().length()
      }
    }
  }

  /**
   * A merge that brings ONE new op into a branch, over a growing history.
   *
   * The replay cost is fixed here: the concurrent region is two ops whatever the history
   * holds. So this row measures the id join, and a time that grows with the history says the
   * join walks the whole history instead of the new part.
   */
  @Test
  fun `a merge of one op over a growing history`() {
    println("=== a merge of one new op, by history size ===")
    println("  %-14s %10s".format("history", "runs"))
    for (runs in HISTORY_RUNS) {
      val base = historyOfRuns(runs)
      val left = base.fork(agent("aaa")).applyOp(DocOp.ins(0, "L"))
      val right = base.fork(agent("bbb")).applyOp(DocOp.ins(0, "R"))
      assertEquals(runs + 2, left.merge(right).text().length())
      println("  %-14s %10d".format("$runs runs", left.graph().runCount()))
      benchmarkSubtest("merge of one op over $runs runs", ONE_OP_MERGE_PASSES) {
        left.merge(right).text().length()
      }
    }
  }

  /**
   * A merge that brings NOTHING, over a history of growing size. This is the row that
   * isolates the id join: the merge appends no run, so every repeat measures the join alone.
   *
   * A gossip protocol spends most of its merges here, because a replica usually has nothing
   * new to give. A time that grows with the history says the join walks the whole history to
   * discover that.
   */
  @Test
  fun `a merge that brings nothing over a growing history`() {
    println("=== a merge that brings nothing, by history size ===")
    println("  %-14s %10s".format("history", "runs"))
    for (runs in HISTORY_RUNS) {
      val branch = historyOfRuns(runs)
      // The same history in another graph value. A fork would share the graph itself, and then a
      // fast path for one instance would leave the row nothing to measure.
      val same = DocBranch.createBranch("", agent("aaa")).merge(branch)
      assertEquals(branch.text().length(), branch.merge(same).text().length())
      println("  %-14s %10d".format("$runs runs", branch.graph().runCount()))
      benchmarkSubtest("merge of nothing over $runs runs", EMPTY_MERGE_PASSES) {
        branch.merge(same).text().length()
      }
    }
  }

  /**
   * Builds a history of [UNITS] units for each run length, in runs of that length. Each run goes
   * to the position that [positionOf] picks from the current length. Then it times a full replay.
   * A longer run makes the replay cheaper. So a row makes [passesPerRunUnit] passes per unit of its
   * run length, and every row takes a similar time.
   *
   * Two agents take turns, so no run extends the one before it, and every run keeps the length
   * that the row names. The history stays linear, so the agents never meet in a tie-break.
   */
  private fun replaySweep(name: String, passesPerRunUnit: Int, positionOf: (Int) -> Int) {
    println("=== $name, $UNITS units ===")
    println("  %-14s %10s %10s %8s".format("history", "units", "runs", "passes"))
    for (runLength in RUN_LENGTHS) {
      val fragment = "x".repeat(runLength)
      var branch = DocBranch.createBranch("", AUTHORS[0])
      var length = 0
      for (run in 0 until UNITS / runLength) {
        branch = branch.fork(AUTHORS[run % AUTHORS.size]).applyOp(DocOp.ins(positionOf(length), fragment))
        length += runLength
      }
      val graph = branch.graph()
      assertEquals(branch.text().string(), graph.replay().string())
      val passes = passesPerRunUnit * runLength
      println("  %-14s %10d %10d %8d".format("$name, len $runLength", graph.size(), graph.runCount(), passes))
      benchmarkSubtest("$name, len $runLength", passes) {
        graph.replay().length()
      }
    }
  }

  /**
   * A history of [runs] runs of one character. Every op inserts at the front, so no op extends
   * the run before it.
   */
  private fun historyOfRuns(runs: Int): DocBranch {
    var branch = DocBranch.createBranch("", agent("u"))
    repeat(runs) {
      branch = branch.applyOp(DocOp.ins(0, "x"))
    }
    return branch
  }

  companion object {
    /** The unit count that every run length builds up to, so the rows compare. */
    private const val UNITS = 40_000

    /** The run lengths to sweep. One unit per run is what a backspace produces. */
    private val RUN_LENGTHS = intArrayOf(1, 4, 16, 64)

    private val AUTHORS = arrayOf(agent("u"), agent("w"))

    private val PASTE_SIZES = intArrayOf(2_000, 8_000, 20_000)

    /** The history sizes that the one-op merge sweeps, in runs. */
    private val HISTORY_RUNS = intArrayOf(2_000, 8_000, 32_000, 128_000)

    /**
     * The passes of one attempt, per scenario. Each count keeps an attempt at about 20 ms or
     * more, because the framework reports whole milliseconds. The paste merge takes a fixed count:
     * the walk costs one step per run, so only the copy of the paste grows with its size.
     */
    private const val FRONT_PASSES_PER_RUN_UNIT = 4
    private const val APPEND_PASSES_PER_RUN_UNIT = 8
    private const val PASTE_MERGE_PASSES = 20_000
    private const val ONE_OP_MERGE_PASSES = 100_000
    private const val EMPTY_MERGE_PASSES = 500_000
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A benchmark, not a regression test: it measures what the replay pays to REPORT its output.
 * The "Performance" name keeps it out of the functional runs.
 *
 * [DocBranchPerformanceTest] cannot see this cost. Its history is one run per op, so a
 * per-character report and a per-run report do the same work there. These scenarios instead
 * vary the run length and the insert position, which are the two things the report cost
 * depends on.
 *
 * Why the position matters: the full replay builds the text in a `StringBuilder`, and an
 * insert shifts everything after it. A history that always inserts at the front therefore
 * pays the document length per insert. A history that appends pays nothing. So the front
 * case is the worst case for a report, and the append case is the best.
 *
 * Every time is the BEST of [TIMED_RUNS] runs after [WARMUP_RUNS] warm-up runs. A single
 * timing is worthless here: this benchmark measured a 16x spread between two runs of one
 * unchanged build. A graph value is immutable, so every run does identical work, and the
 * best run is the one least disturbed by the machine.
 *
 * Read the `of len 1` column and not the milliseconds when two builds are compared. Even the
 * best of five varies by 2x between JVM launches on a busy machine. The `len 1` row is the
 * control: one unit per run leaves a per-character report and a per-run report the same work,
 * so dividing by it cancels the load out.
 */
class ReplayPerformanceTest {

  /**
   * A full replay of a history that always inserts at the front.
   *
   * The item scan stays cheap here: the position is 0, so the lookup never walks the item
   * list. What remains is the report, which makes this the scenario that isolates it.
   */
  @Test
  fun `a full replay of a history that inserts at the front`() {
    println("=== insert at the front, $UNITS units ===")
    printHeader()
    var control = 0.0
    for (runLength in RUN_LENGTHS) {
      val millis = replayScenario("front", runLength, control) { _ -> 0 }
      if (runLength == RUN_LENGTHS[0]) {
        control = millis
      }
    }
    println()
  }

  /**
   * A full replay of a history that appends. Every insert lands at the end, so a
   * `StringBuilder` shifts nothing whatever the report does.
   */
  @Test
  fun `a full replay of a history that appends`() {
    println("=== append, $UNITS units ===")
    printHeader()
    var control = 0.0
    for (runLength in RUN_LENGTHS) {
      val millis = replayScenario("append", runLength, control) { length -> length }
      if (runLength == RUN_LENGTHS[0]) {
        control = millis
      }
    }
    println()
  }

  /**
   * A merge of two concurrent pastes. This is the [DocOp] path through the batching sink, and
   * not the `StringBuilder` path, so it reports one op however many calls it takes to build.
   */
  @Test
  fun `a merge of two concurrent pastes`() {
    println("=== a merge of two concurrent pastes ===")
    println("  %-14s %10s %10s".format("paste", "units", "merge"))
    for (paste in PASTE_SIZES) {
      val base = DocBranch.createBranch("base\n", agent("base"))
      val left = base.fork(agent("aaa")).applyOp(DocOp.ins(5, "L".repeat(paste)))
      val right = base.fork(agent("bbb")).applyOp(DocOp.ins(5, "R".repeat(paste)))
      val expected = 5 + 2 * paste
      val millis = bestOf {
        assertEquals(expected, left.merge(right).text().length())
      }
      println("  %-14s %10d %8.1f ms".format("$paste chars", expected - 5, millis))
    }
    println()
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
    println("  %-14s %10s %10s %9s".format("history", "runs", "merge", "per run"))
    for (runs in HISTORY_RUNS) {
      var branch = DocBranch.createBranch("", agent("u"))
      repeat(runs) {
        branch = branch.applyOp(DocOp.ins(branch.text().length(), "x"))
      }
      val base = branch
      val left = base.fork(agent("aaa")).applyOp(DocOp.ins(0, "L"))
      val right = base.fork(agent("bbb")).applyOp(DocOp.ins(0, "R"))
      val expected = runs + 2
      val millis = bestOf {
        assertEquals(expected, left.merge(right).text().length())
      }
      val perRun = millis * 1_000_000 / (runs + 1)
      println("  %-14s %10d %8.1f ms %7.0f ns".format("$runs runs", left.graph().runCount(), millis, perRun))
    }
    println()
  }

  /**
   * A merge that brings NOTHING, over a history of growing size. This is the row that
   * isolates the id join: the merge appends no run, so it never copies a store prefix, and
   * every repeat measures the join alone.
   *
   * A gossip protocol spends most of its merges here, because a replica usually has nothing
   * new to give. A time that grows with the history says the join walks the whole history to
   * discover that.
   */
  @Test
  fun `a merge that brings nothing over a growing history`() {
    println("=== a merge that brings nothing, by history size ===")
    println("  %-14s %10s %10s %9s".format("history", "runs", "merge", "per run"))
    for (runs in HISTORY_RUNS) {
      var branch = DocBranch.createBranch("", agent("u"))
      repeat(runs) {
        branch = branch.applyOp(DocOp.ins(branch.text().length(), "x"))
      }
      // The fork adds no op, so it holds exactly the history of the branch.
      val same = branch.fork(agent("aaa"))
      val expected = branch.text().length()
      val millis = bestOf {
        assertEquals(expected, branch.merge(same).text().length())
      }
      val perRun = millis * 1_000_000 / runs
      println("  %-14s %10d %8.3f ms %7.1f ns".format("$runs runs", branch.graph().runCount(), millis, perRun))
    }
    println()
  }

  /**
   * Builds a history of [UNITS] units in runs of [runLength], each run inserted at the
   * position that [positionOf] picks from the current length, then times a full replay and
   * returns it. [control] is the time of the `len 1` row, or 0 for the row that sets it.
   */
  private fun replayScenario(name: String, runLength: Int, control: Double, positionOf: (Int) -> Int): Double {
    val fragment = "x".repeat(runLength)
    var branch = DocBranch.createBranch("", agent("u"))
    var length = 0
    repeat(UNITS / runLength) {
      branch = branch.applyOp(DocOp.ins(positionOf(length), fragment))
      length += runLength
    }
    val graph = branch.graph()
    val expected = branch.text().string()
    val millis = bestOf {
      assertEquals(expected, graph.replay().string())
    }
    val share = if (control == 0.0) 1.0 else millis / control
    println(
      "  %-14s %10d %10d %8.1f ms %9.3f".format(
        "$name, len $runLength", graph.size(), graph.runCount(), millis, share,
      )
    )
    return millis
  }

  /** The best time of [action], in milliseconds, over [TIMED_RUNS] runs after the warm-up. */
  private fun bestOf(action: () -> Unit): Double {
    repeat(WARMUP_RUNS) {
      action()
    }
    var best = Long.MAX_VALUE
    repeat(TIMED_RUNS) {
      val start = System.nanoTime()
      action()
      best = minOf(best, System.nanoTime() - start)
    }
    return best / 1_000_000.0
  }

  private fun printHeader() {
    println("  %-14s %10s %10s %10s %9s".format("history", "units", "runs", "replay", "of len 1"))
  }

  companion object {
    /** The unit count that every run length builds up to, so the rows compare. */
    private const val UNITS = 40_000

    /** The run lengths to sweep. One unit per run is what typing produces. */
    private val RUN_LENGTHS = intArrayOf(1, 4, 16, 64)

    private val PASTE_SIZES = intArrayOf(2_000, 8_000, 20_000)

    /** The history sizes that the one-op merge sweeps, in runs. */
    private val HISTORY_RUNS = intArrayOf(2_000, 8_000, 32_000, 128_000)

    private const val WARMUP_RUNS = 3
    private const val TIMED_RUNS = 5
  }
}

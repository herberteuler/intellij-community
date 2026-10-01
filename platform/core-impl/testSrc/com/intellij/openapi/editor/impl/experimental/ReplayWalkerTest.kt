// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocBranch
import com.intellij.openapi.editor.experimental.DocTextOp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Tests [ReplayWalker] over histories long enough that its [ItemTree] grows inner levels. The other
 * replay tests walk a few items, so their tree stays one leaf.
 *
 * The text of a branch comes from [DocText][com.intellij.openapi.editor.experimental.DocText] ops,
 * and not from a walk. So it is an oracle for a full replay of a history of one agent. A merge has
 * no such oracle, and its partial replay must give the text of a full replay.
 */
internal class ReplayWalkerTest {

  @Test
  fun `a history of edits at random places replays to its text`() {
    val branch = randomEdits(Random(20261001L), DocBranch.createBranch("", U), OPS)
    assertTrue(replayInFull(branch) >= 2, "the item tree has fewer than two inner levels")
  }

  @Test
  fun `typing with backspaces at a caret replays to its text`() {
    // One long run of typing in the middle of a text. A run of inserts at the end of a leaf moves
    // no older item when the leaf splits, so this checks the leaves that the run fills.
    var branch = DocBranch.createBranch("[" + "o".repeat(OPS) + "]", U)
    val random = Random(20261002L)
    var caret = 1 + OPS / 2
    repeat(OPS) {
      if (random.nextInt(5) == 0) {
        caret--
        branch = branch.applyOp(DocTextOp.deleteOp(caret, 1))
      } else {
        branch = branch.applyOp(DocTextOp.insertOp(caret, "x"))
        caret++
      }
    }
    assertTrue(replayInFull(branch) >= 1, "the item tree has no inner level")
  }

  @Test
  fun `a history that overtypes replays to its text`() {
    // Each pair of ops leaves one deleted item at the place of the next pair.
    var branch = DocBranch.createBranch("o".repeat(OPS), U)
    for (offset in 0 until OPS) {
      branch = branch.applyOp(DocTextOp.deleteOp(offset, 1))
      branch = branch.applyOp(DocTextOp.insertOp(offset, "x"))
    }
    assertEquals("x".repeat(OPS), branch.text().string())
    assertTrue(replayInFull(branch) >= 2, "the item tree has fewer than two inner levels")
  }

  @Test
  fun `two long concurrent histories merge to the text of a full replay`() {
    val random = Random(20261003L)
    val base = randomEdits(random, DocBranch.createBranch("", U), OPS / 10)
    val left = randomEdits(random, base.fork(V), OPS / 2)
    val right = randomEdits(random, base.fork(W), OPS / 2)
    val forward = left.merge(right)
    val backward = right.merge(left)
    assertEquals(forward.text().string(), backward.text().string())
    assertTrue(replayInFull(forward) >= 2, "the item tree has fewer than two inner levels")
  }

  /**
   * Replays the whole graph of [branch] with a fresh walker, checks its text against the text of
   * [branch], checks the item tree, and returns the inner levels of the tree.
   */
  private fun replayInFull(branch: DocBranch): Int {
    val graph = EventGraphImpl.implOf(branch.graph())
    val walker = ReplayWalker(graph, placeholderCount = 0)
    val text = StringBuilder()
    walker.replayAt(graph.lvVersion(), TextSink(text))
    assertEquals(branch.text().string(), text.toString())
    walker.checkItems()
    return walker.itemTreeDepth()
  }

  /**
   * [ops] edits of [start] at random places. Seven of ten insert one or two characters, and the
   * rest delete one.
   */
  private fun randomEdits(random: Random, start: DocBranch, ops: Int): DocBranch {
    var branch = start
    repeat(ops) {
      val length = branch.text().length()
      val at = random.nextInt(length + 1)
      branch = if (length > 0 && random.nextInt(10) < 3) {
        val deleteAt = minOf(at, length - 1)
        branch.applyOp(DocTextOp.deleteOp(deleteAt, 1))
      } else {
        val fragment = "ab".substring(0, 1 + random.nextInt(2))
        branch.applyOp(DocTextOp.insertOp(at, fragment))
      }
    }
    return branch
  }

  private class TextSink(private val text: StringBuilder) : EgWalkerReplay.Sink {
    override fun insert(effectPos: Int, fragment: CharSequence) {
      text.insert(effectPos, fragment)
    }

    override fun delete(effectPos: Int, count: Int) {
      text.delete(effectPos, effectPos + count)
    }
  }

  private companion object {
    /**
     * Enough ops for two inner levels of the item tree: a leaf holds up to [ItemTree.WIDTH] items.
     */
    const val OPS = 3_000

    val U: Agent = Agent.createAgent("u")
    val V: Agent = Agent.createAgent("v")
    val W: Agent = Agent.createAgent("w")
  }
}

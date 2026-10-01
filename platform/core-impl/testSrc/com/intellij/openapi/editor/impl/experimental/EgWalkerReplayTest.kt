// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocBranch
import com.intellij.openapi.editor.experimental.DocTextOp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the precondition of [EgWalkerReplay.mergeInto] that no public call can break.
 */
internal class EgWalkerReplayTest {

  /**
   * The graph of "a" holds its own delete at the lv 6 and the delete of "b" at the lv 7. A merge
   * into the version of "b" would walk the lv 7 first and the new lv 6 after it, which the delete
   * targets cannot take. The merge must say so, and not fail inside them.
   */
  @Test
  fun `a merge into a branch whose own units sit above the new units is rejected`() {
    val base = DocBranch.createBranch("abcdef", Agent.createAgent("a"))
    val a = base.applyOp(DocTextOp.deleteOp(0, 1))
    val b = base.fork(Agent.createAgent("b")).applyOp(DocTextOp.deleteOp(5, 1))
    val merged = EventGraphImpl.implOf(a.graph()).mergeFromImpl(EventGraphImpl.implOf(b.graph())).graph()
    val failure = assertThrows(IllegalArgumentException::class.java) {
      EgWalkerReplay.mergeInto(merged, VersionImpl(intArrayOf(7)), BatchingSink(b.text()))
    }
    assertTrue(failure.message.orEmpty().contains("do not all sit above"), failure.message)
    // The direction that a merge uses works on the same graph.
    val sink = BatchingSink(a.text())
    EgWalkerReplay.mergeInto(merged, EventGraphImpl.implOf(a.graph()).versionImpl(), sink)
    assertEquals("bcde", sink.result().string())
  }
}

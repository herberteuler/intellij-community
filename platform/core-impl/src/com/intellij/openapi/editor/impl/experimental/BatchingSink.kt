// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.DocText

/**
 * Coalesces the merge effects into fragment and range ops before they reach the text.
 * Successive inserts that meet end to end equal one fragment insert at the first position;
 * successive deletes at one position equal one delete of their total length. This is also
 * the op stream an editor integration would fire as events.
 *
 * The walk already reports one span per run, so this class earns its keep across runs: two
 * runs that land side by side arrive as two calls and still leave as one op.
 */
internal class BatchingSink(
  private var updated: DocText,
) : EgWalkerReplay.Sink {
  private var kind: Int = NONE
  private var startEffectPos: Int = 0
  private val pendingFragment = StringBuilder()
  private var deleteCount: Int = 0

  override fun insert(effectPos: Int, fragment: CharSequence) {
    if (kind != INSERT || effectPos != startEffectPos + pendingFragment.length) {
      flush()
      kind = INSERT
      startEffectPos = effectPos
    }
    pendingFragment.append(fragment)
  }

  override fun delete(effectPos: Int, count: Int) {
    if (kind != DELETE || effectPos != startEffectPos) {
      flush()
      kind = DELETE
      startEffectPos = effectPos
    }
    deleteCount += count
  }

  fun result(): DocText {
    flush()
    return updated
  }

  /** The text length so far, plus the op that still waits for its neighbour. */
  override fun toString(): String {
    val pending = when (kind) {
      INSERT -> "insert at $startEffectPos of ${pendingFragment.quotedForMessage()}"
      DELETE -> "delete of $deleteCount at $startEffectPos"
      else -> "nothing"
    }
    return "BatchingSink(length=${updated.length()}, pending=$pending)"
  }

  private fun flush() {
    // The effect version IS the text this sink builds, so its position is the op's offset.
    when (kind) {
      INSERT -> updated = updated.applyOp(DocOp.ins(startEffectPos, pendingFragment.toString()))
      DELETE -> updated = updated.applyOp(DocOp.del(startEffectPos, deleteCount))
    }
    kind = NONE
    pendingFragment.setLength(0)
    deleteCount = 0
  }

  companion object {
    private const val NONE = 0
    private const val INSERT = 1
    private const val DELETE = 2
  }
}

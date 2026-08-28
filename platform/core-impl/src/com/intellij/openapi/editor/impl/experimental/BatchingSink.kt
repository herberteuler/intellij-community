// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.DocText

/**
 * Coalesces the per-unit merge effects into fragment and range ops before they reach
 * the text. N successive inserts at the positions `pos, pos + 1, ...` equal one
 * fragment insert at `pos`; N successive deletes at one position equal one delete of
 * the length N. This is also the op stream an editor integration would fire as events.
 */
internal class BatchingSink(
  private var updated: DocText,
) : EgWalkerReplay.Sink {
  private var kind: Int = NONE
  private var start: Int = 0
  private val fragment = StringBuilder()
  private var deleteCount: Int = 0

  override fun insert(pos: Int, character: Char) {
    if (kind != INSERT || pos != start + fragment.length) {
      flush()
      kind = INSERT
      start = pos
    }
    fragment.append(character)
  }

  override fun delete(pos: Int) {
    if (kind != DELETE || pos != start) {
      flush()
      kind = DELETE
      start = pos
    }
    deleteCount++
  }

  fun result(): DocText {
    flush()
    return updated
  }

  private fun flush() {
    when (kind) {
      INSERT -> updated = updated.applyOp(DocOp.ins(start, fragment.toString()))
      DELETE -> updated = updated.applyOp(DocOp.del(start, deleteCount))
    }
    kind = NONE
    fragment.setLength(0)
    deleteCount = 0
  }

  companion object {
    private const val NONE = 0
    private const val INSERT = 1
    private const val DELETE = 2
  }
}

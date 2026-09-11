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
  private var start: Int = 0
  private val pendingFragment = StringBuilder()
  private var deleteCount: Int = 0

  override fun insert(pos: Int, fragment: CharSequence) {
    if (kind != INSERT || pos != start + pendingFragment.length) {
      flush()
      kind = INSERT
      start = pos
    }
    pendingFragment.append(fragment)
  }

  override fun delete(pos: Int, count: Int) {
    if (kind != DELETE || pos != start) {
      flush()
      kind = DELETE
      start = pos
    }
    deleteCount += count
  }

  fun result(): DocText {
    flush()
    return updated
  }

  private fun flush() {
    when (kind) {
      INSERT -> updated = updated.applyOp(DocOp.ins(start, pendingFragment.toString()))
      DELETE -> updated = updated.applyOp(DocOp.del(start, deleteCount))
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

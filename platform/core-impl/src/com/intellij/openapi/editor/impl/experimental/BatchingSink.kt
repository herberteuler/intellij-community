// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.DocMerge
import com.intellij.openapi.editor.experimental.DocTextOp
import com.intellij.openapi.editor.experimental.DocText
import java.util.Collections

/**
 * Turns the reports of a merge into ops, applies them to the text, and records them. The recorded
 * ops are the op stream of [DocMerge.ops].
 *
 * The sink holds at most one pending op. A report joins the pending op when the two equal one op:
 * - an insert at the end of a pending insert extends its fragment;
 * - a delete that starts inside a pending insert and ends at its end shortens the fragment. The
 *   new side deleted text that it inserted itself, so no op brings that text at all;
 * - a delete at the position of a pending delete extends it, as the Delete key does;
 * - a delete that ends at the position of a pending delete extends it backwards, as a backspace
 *   does.
 *
 * Any other report flushes the pending op first. Each join costs O(1), because it never moves the
 * characters of the pending fragment. The walk reports one span per run, so the joins pay off
 * across runs. Two runs that land side by side arrive as two reports and leave as one op.
 *
 * The sink is single use, and one merge owns it. [result] and [ops] finish it, and a report after
 * that fails.
 */
internal class BatchingSink(
  private var updated: DocText,
) : EgWalkerReplay.Sink {
  private var kind: Int = NONE
  private var startEffectPos: Int = 0
  private val pendingFragment = StringBuilder()
  private var deleteCount: Int = 0
  private val applied = ArrayList<DocTextOp>()
  private var finished = false

  override fun insert(effectPos: Int, fragment: CharSequence) {
    checkOpen()
    checkInsert(effectPos, fragment)
    if (kind != INSERT || effectPos != pendingInsertEnd()) {
      flush()
      kind = INSERT
      startEffectPos = effectPos
    }
    pendingFragment.append(fragment)
  }

  override fun delete(effectPos: Int, count: Int) {
    checkOpen()
    checkDelete(effectPos, count)
    if (kind == INSERT && endsPendingInsert(effectPos, count)) {
      pendingFragment.setLength(effectPos - startEffectPos)
      if (pendingFragment.isEmpty()) {
        kind = NONE
      }
    } else if (kind == DELETE && effectPos == startEffectPos) {
      deleteCount += count
    } else if (kind == DELETE && effectPos + count == startEffectPos) {
      startEffectPos = effectPos
      deleteCount += count
    } else {
      flush()
      kind = DELETE
      startEffectPos = effectPos
      deleteCount = count
    }
  }

  /**
   * The text with every report applied. This finishes the sink.
   */
  fun result(): DocText {
    finish()
    return updated
  }

  /**
   * The ops that [result] applied to the text, in order. This finishes the sink.
   */
  fun ops(): List<DocTextOp> {
    finish()
    return Collections.unmodifiableList(applied)
  }

  /**
   * The text length so far, the op that still waits for its neighbour, and the ops so far.
   */
  override fun toString(): String {
    val pending = when (kind) {
      INSERT -> "insert at $startEffectPos of ${pendingFragment.quotedForMessage()}"
      DELETE -> "delete of $deleteCount at $startEffectPos"
      else -> "nothing"
    }
    val state = if (finished) "finished" else "open"
    return "BatchingSink(length=${updated.length()}, pending=$pending, ops=${applied.size}, $state)"
  }

  private fun finish() {
    if (!finished) {
      flush()
      finished = true
    }
  }

  private fun flush() {
    // The effect version IS the text this sink builds, so its position is the op's offset.
    val op = when (kind) {
      INSERT -> DocTextOp.insertOp(startEffectPos, pendingFragment.toString())
      DELETE -> DocTextOp.deleteOp(startEffectPos, deleteCount)
      else -> null
    }
    if (op != null) {
      updated = updated.applyOp(op)
      applied.add(op)
    }
    kind = NONE
    pendingFragment.setLength(0)
    deleteCount = 0
  }

  private fun pendingInsertEnd(): Int {
    return startEffectPos + pendingFragment.length
  }

  /**
   * Whether the delete of [count] at [effectPos] starts inside the pending insert and ends at its end.
   */
  private fun endsPendingInsert(effectPos: Int, count: Int): Boolean {
    return effectPos >= startEffectPos && effectPos + count == pendingInsertEnd()
  }

  /**
   * The length of the text with every report so far applied, the pending op included.
   */
  private fun currentLength(): Int {
    return when (kind) {
      INSERT -> updated.length() + pendingFragment.length
      DELETE -> updated.length() - deleteCount
      else -> updated.length()
    }
  }

  private fun checkOpen() {
    require(!finished) {
      "A report arrived after the sink finished"
    }
  }

  /**
   * Fails unless the insert brings text at a position of the current text. The text is what the
   * merge builds, so a report outside it means a faulty event. This check names the fault, before a
   * flush fails with no name.
   */
  private fun checkInsert(effectPos: Int, fragment: CharSequence) {
    require(fragment.isNotEmpty()) {
      "An empty insert at $effectPos"
    }
    require(effectPos in 0..currentLength()) {
      "The insert at $effectPos is outside the text of length ${currentLength()}"
    }
  }

  /**
   * Fails unless the delete removes at least one character, all inside the current text.
   */
  private fun checkDelete(effectPos: Int, count: Int) {
    require(count >= 1) {
      "The delete count is not positive: $count"
    }
    require(effectPos >= 0 && count <= currentLength() - effectPos) {
      "The delete of $count at $effectPos is outside the text of length ${currentLength()}"
    }
  }

  private companion object {
    const val NONE = 0
    const val INSERT = 1
    const val DELETE = 2
  }
}

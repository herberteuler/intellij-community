// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl

import com.intellij.openapi.editor.ex.DocumentModState
import com.intellij.openapi.editor.ex.DocumentOp
import com.intellij.openapi.editor.ex.DocumentOp.Companion.deleteOp
import com.intellij.openapi.editor.ex.DocumentOp.Companion.insertOp
import com.intellij.openapi.editor.ex.DocumentOp.Companion.modStampOp
import com.intellij.openapi.editor.ex.DocumentOp.Companion.unmodifiedLinesOp
import com.intellij.openapi.editor.ex.DocumentText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests the rules of [RangeModState] that a random history reaches rarely: the results that change
 * nothing, the failures, and named examples of the line rules of [ModifiedLineSet].
 */
internal class RangeModStateTest {

  @Test
  fun `a new state marks no line, even past the text`() {
    val modState = RangeModState.create(DocumentText.createText("a\nb"))
    assertEquals(0, modState.sequence())
    assertFalse(modState.isLineModified(0))
    assertFalse(modState.isLineModified(100))
  }

  @Test
  fun `an op that changes nothing keeps the instance`() {
    val text = DocumentText.createText("abc")
    val modState = RangeModState.create(text)
    val sameStamp = modStampOp(modState.stamp(), false)
    val noModifiedLines = unmodifiedLinesOp(0, 1, IntArray(0))
    assertSame(modState, modState.applyOp(text, text, insertOp(1, "")))
    assertSame(modState, modState.applyOp(text, text, deleteOp(1, 0)))
    assertSame(modState, modState.applyOp(text, text, sameStamp))
    assertSame(modState, modState.applyOp(text, text, noModifiedLines))
  }

  @Test
  fun `a stamp op sets the stamp and can count`() {
    val text = DocumentText.createText("abc")
    val stamped = RangeModState.create(text).applyOp(text, text, modStampOp(42, true))
    assertEquals(42, stamped.stamp())
    assertEquals(1, stamped.sequence())
  }

  @Test
  fun `a line past the text fails once a line is modified`() {
    val edited = editedFirstLine()
    assertThrows(IndexOutOfBoundsException::class.java) { edited.modState.isLineModified(2) }
    assertThrows(IndexOutOfBoundsException::class.java) { edited.modState.isLineModified(-1) }
  }

  @Test
  fun `a clear of a wrong line range fails`() {
    val edited = editedFirstLine()
    val reversed = unmodifiedLinesOp(1, 0, IntArray(0))
    val pastEnd = unmodifiedLinesOp(0, 3, IntArray(0))
    assertThrows(IllegalArgumentException::class.java) { edited.apply(reversed) }
    assertThrows(IndexOutOfBoundsException::class.java) { edited.apply(pastEnd) }
  }

  @Test
  fun `an op against another text fails`() {
    val edited = editedFirstLine()
    val longer = DocumentText.createText("a longer text\n")
    val insert = insertOp(0, "y")
    assertThrows(IllegalArgumentException::class.java) {
      edited.modState.applyOp(longer, longer.applyOp(insert), insert)
    }
  }

  @Test
  fun `the empty line after the final separator is never modified`() {
    val edited = assertSameAsLineSet("a\n", insertOp(2, "b\n"))
    assertTrue(edited.modState.isLineModified(1))
    assertFalse(edited.modState.isLineModified(2))
  }

  @Test
  fun `a delete of the text of the last line keeps that line modified`() {
    val edited = assertSameAsLineSet("a\nbc", deleteOp(2, 2))
    assertFalse(edited.modState.isLineModified(0))
    assertTrue(edited.modState.isLineModified(1))
  }

  @Test
  fun `a delete of whole lines through the end marks no line`() {
    val edited = assertSameAsLineSet("a\nb\nc", deleteOp(2, 3))
    assertFalse(edited.modState.isLineModified(0))
    assertFalse(edited.modState.isLineModified(1))
  }

  @Test
  fun `a change inside a CRLF pair marks the lines of the pair`() {
    val edited = assertSameAsLineSet("a\r\nb\r\nc", insertOp(5, "x"))
    assertTrue(edited.modState.isLineModified(1))
  }

  @Test
  fun `a clear keeps an except line that was modified`() {
    val edited = assertSameAsLineSet(
      "a\nb\nc",
      insertOp(4, "x"),
      unmodifiedLinesOp(0, Int.MAX_VALUE, intArrayOf(1, 2)),
    )
    assertFalse(edited.modState.isLineModified(1))
    assertTrue(edited.modState.isLineModified(2))
  }

  /**
   * Applies [ops] to a new [RangeModState] and to a new [DocumentModStateImpl], and checks that both
   * mark the same lines.
   */
  private fun assertSameAsLineSet(start: String, vararg ops: DocumentOp): Edited {
    var text = DocumentText.createText(start)
    var expected: DocumentModState = DocumentModStateImpl()
    var actual: DocumentModState = RangeModState.create(text)
    for (op in ops) {
      val after = text.applyOp(op)
      expected = expected.applyOp(text, after, op)
      actual = actual.applyOp(text, after, op)
      text = after
    }
    assertEquals(expected.sequence(), actual.sequence())
    for (line in 0 until text.lineCount()) {
      assertEquals(expected.isLineModified(line), actual.isLineModified(line)) { "line $line" }
    }
    return Edited(text, actual)
  }

  /**
   * The text `"xa\nb"` with its first line modified.
   */
  private fun editedFirstLine(): Edited {
    val start = DocumentText.createText("a\nb")
    val insert = insertOp(0, "x")
    val text = start.applyOp(insert)
    val modState = RangeModState.create(start).applyOp(start, text, insert)
    return Edited(text, modState)
  }

  /**
   * A text and its mod state after some ops.
   */
  private class Edited(val text: DocumentText, val modState: DocumentModState) {
    fun apply(op: DocumentOp): DocumentModState {
      return modState.applyOp(text, text.applyOp(op), op)
    }
  }
}

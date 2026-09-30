// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.DocText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Tests [BatchingSink]: which reports leave as one op, which leave as several, and that the text
 * is the same either way. The ops are what an editor integration fires as events, so the test
 * counts them and does not only read the text.
 */
internal class BatchingSinkTest {

  @Test
  fun `inserts that meet end to end leave as one op`() {
    val ops = ArrayList<String>()
    val sink = BatchingSink(RecordingText(DocText.createText("[]"), ops))
    sink.insert(1, "ab")
    sink.insert(3, "cd")
    sink.insert(5, "e")
    assertEquals("[abcde]", sink.result().string())
    assertEquals(listOf("ins(1, \"abcde\")"), ops)
  }

  @Test
  fun `deletes at one position leave as one op`() {
    val ops = ArrayList<String>()
    val sink = BatchingSink(RecordingText(DocText.createText("0123456789"), ops))
    sink.delete(2, 3)
    sink.delete(2, 1)
    assertEquals("016789", sink.result().string())
    assertEquals(listOf("del(2, len=4)"), ops)
  }

  @Test
  fun `a report that does not continue the pending op flushes it first`() {
    val ops = ArrayList<String>()
    val sink = BatchingSink(RecordingText(DocText.createText("0123456789"), ops))
    sink.insert(2, "x")
    // Before the pending insert, so it does not continue it.
    sink.insert(2, "y")
    sink.delete(0, 1)
    // Another position.
    sink.delete(5, 2)
    sink.insert(0, "z")
    assertEquals("z1yx236789", sink.result().string())
    assertEquals(listOf("ins(2, \"x\")", "ins(2, \"y\")", "del(0, len=1)", "del(5, len=2)", "ins(0, \"z\")"), ops)
  }

  @Test
  fun `a result without reports is the text itself`() {
    val ops = ArrayList<String>()
    val text = RecordingText(DocText.createText("abc"), ops)
    val sink = BatchingSink(text)
    assertSame(text, sink.result())
    assertSame(text, sink.result())
    assertEquals(emptyList<String>(), ops)
  }

  @Test
  fun `a second result applies nothing more`() {
    val ops = ArrayList<String>()
    val sink = BatchingSink(RecordingText(DocText.createText("abc"), ops))
    sink.insert(3, "d")
    val first = sink.result()
    assertSame(first, sink.result())
    assertEquals(1, ops.size)
  }

  /** A text that records every op it applies, and passes the rest to [inner]. */
  private class RecordingText(private val inner: DocText, private val ops: MutableList<String>) : DocText by inner {
    override fun applyOp(op: DocOp): DocText {
      ops.add(op.toString())
      return RecordingText(inner.applyOp(op), ops)
    }
  }
}

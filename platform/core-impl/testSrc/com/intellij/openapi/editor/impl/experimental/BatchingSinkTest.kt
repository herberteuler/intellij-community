// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.ex.experimental.DocTextOp
import com.intellij.openapi.editor.ex.experimental.DocText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests [BatchingSink]: which reports join the pending op, which flush it, and what the sink
 * rejects. The ops are the op stream of a merge, so every test compares them with the ops that the
 * text really took, and checks that they fold to the result.
 */
internal class BatchingSinkTest {

  @Test
  fun `inserts that meet end to end leave as one op`() {
    val sink = Recorded("[]")
    sink.insert(1, "ab")
    sink.insert(3, "cd")
    sink.insert(5, "e")
    sink.check("[abcde]", "ins(1, \"abcde\")")
  }

  @Test
  fun `deletes at one position leave as one op`() {
    val sink = Recorded("0123456789")
    sink.delete(2, 3)
    sink.delete(2, 1)
    sink.check("016789", "del(2, len=4)")
  }

  @Test
  fun `deletes that end where the pending delete starts leave as one op`() {
    // Three backspaces: each removes the character before the one the last removed.
    val sink = Recorded("0123456")
    sink.delete(5, 1)
    sink.delete(4, 1)
    sink.delete(3, 1)
    sink.check("0126", "del(3, len=3)")
  }

  @Test
  fun `a Delete key press and a backspace at one place leave as one op`() {
    val sink = Recorded("0123456")
    sink.delete(3, 1)
    sink.delete(3, 1)
    sink.delete(2, 1)
    sink.check("0156", "del(2, len=3)")
  }

  @Test
  fun `a delete of the end of a pending insert shortens it`() {
    val sink = Recorded("[]")
    sink.insert(1, "abcd")
    sink.delete(4, 1)
    sink.delete(2, 2)
    sink.check("[a]", "ins(1, \"a\")")
  }

  @Test
  fun `a delete of the whole pending insert leaves no op`() {
    val sink = Recorded("[]")
    sink.insert(1, "ab")
    sink.delete(2, 1)
    sink.delete(1, 1)
    sink.check("[]")
    assertSame(sink.initial, sink.inner.result())
  }

  @Test
  fun `a report after an emptied insert starts a new op`() {
    val sink = Recorded("0123")
    sink.insert(2, "ab")
    sink.delete(2, 2)
    sink.insert(4, "x")
    sink.delete(0, 1)
    sink.check("123x", "ins(4, \"x\")", "del(0, len=1)")
  }

  @Test
  fun `a delete inside a pending insert that misses its end flushes it`() {
    val sink = Recorded("[]")
    sink.insert(1, "abc")
    sink.delete(1, 1)
    sink.check("[bc]", "ins(1, \"abc\")", "del(1, len=1)")
  }

  @Test
  fun `a delete that starts before a pending insert flushes it`() {
    val sink = Recorded("0123")
    sink.insert(2, "ab")
    sink.delete(1, 3)
    sink.check("023", "ins(2, \"ab\")", "del(1, len=3)")
  }

  @Test
  fun `a report that does not continue the pending op flushes it first`() {
    val sink = Recorded("0123456789")
    sink.insert(2, "x")
    // Before the pending insert, so it does not continue it.
    sink.insert(2, "y")
    sink.delete(0, 1)
    // Neither at the pending delete nor just before it.
    sink.delete(5, 2)
    sink.insert(0, "z")
    // An insert at the position of a pending delete is not a delete.
    sink.delete(3, 1)
    sink.insert(3, "w")
    sink.check(
      "z1yw236789",
      "ins(2, \"x\")",
      "ins(2, \"y\")",
      "del(0, len=1)",
      "del(5, len=2)",
      "ins(0, \"z\")",
      "del(3, len=1)",
      "ins(3, \"w\")",
    )
  }

  @Test
  fun `a result without reports is the text itself`() {
    val sink = Recorded("abc")
    assertSame(sink.initial, sink.inner.result())
    sink.check("abc")
  }

  @Test
  fun `a second result or ops call applies nothing more`() {
    val sink = Recorded("abc")
    sink.insert(3, "d")
    val first = sink.inner.result()
    assertSame(first, sink.inner.result())
    assertEquals(sink.inner.ops(), sink.inner.ops())
    sink.check("abcd", "ins(3, \"d\")")
  }

  @Test
  fun `a report after the sink finished is rejected`() {
    val byResult = BatchingSink(DocText.createText("abc"))
    byResult.result()
    assertThrows(IllegalArgumentException::class.java) { byResult.insert(0, "x") }
    val byOps = BatchingSink(DocText.createText("abc"))
    byOps.ops()
    assertThrows(IllegalArgumentException::class.java) { byOps.delete(0, 1) }
  }

  @Test
  fun `a report outside the current text is rejected`() {
    val sink = BatchingSink(DocText.createText("xyz"))
    assertThrows(IllegalArgumentException::class.java) { sink.insert(-1, "a") }
    assertThrows(IllegalArgumentException::class.java) { sink.insert(4, "a") }
    assertThrows(IllegalArgumentException::class.java) { sink.delete(-1, 1) }
    assertThrows(IllegalArgumentException::class.java) { sink.delete(3, 1) }
    assertThrows(IllegalArgumentException::class.java) { sink.delete(1, Int.MAX_VALUE) }
    // The length counts the pending op: "xyz" with "ab" pending has 5 characters.
    sink.insert(3, "ab")
    sink.delete(4, 1)
    assertThrows(IllegalArgumentException::class.java) { sink.delete(4, 1) }
    sink.insert(4, "c")
    // Then "xyzac" with 2 deleted pending has 3.
    sink.delete(0, 2)
    assertThrows(IllegalArgumentException::class.java) { sink.insert(4, "q") }
    sink.insert(3, "q")
    assertEquals("zacq", sink.result().string())
  }

  @Test
  fun `an empty report is rejected`() {
    val sink = BatchingSink(DocText.createText("abc"))
    assertThrows(IllegalArgumentException::class.java) { sink.insert(0, "") }
    assertThrows(IllegalArgumentException::class.java) { sink.delete(0, 0) }
    assertThrows(IllegalArgumentException::class.java) { sink.delete(0, -1) }
    assertEquals(emptyList<DocTextOp>(), sink.ops())
  }

  @Test
  fun `the ops cannot change`() {
    val sink = BatchingSink(DocText.createText("abc"))
    sink.insert(0, "x")
    @Suppress("UNCHECKED_CAST")
    val ops = sink.ops() as MutableList<DocTextOp>
    assertThrows(UnsupportedOperationException::class.java) { ops.add(DocTextOp.deleteOp(0, 1)) }
    assertThrows(UnsupportedOperationException::class.java) { ops.clear() }
  }

  @Test
  fun `a long pending fragment stays short in a message`() {
    val sink = BatchingSink(DocText.createText(""))
    sink.insert(0, "x".repeat(1_000))
    val message = sink.toString()
    assertTrue(message.contains("(1000 chars)"), message)
    assertTrue(message.length < 200, message)
  }

  /**
   * A sink over a text that records every op it applies. [check] compares those ops with the ops
   * the sink reports, and folds them over the start text.
   */
  private class Recorded(text: String) {
    private val start = DocText.createText(text)
    private val applied = ArrayList<String>()

    /**
     * The text the sink starts from, which records the ops.
     */
    val initial: DocText = RecordingText(start, applied)
    val inner = BatchingSink(initial)

    fun insert(effectPos: Int, fragment: String) {
      inner.insert(effectPos, fragment)
    }

    fun delete(effectPos: Int, count: Int) {
      inner.delete(effectPos, count)
    }

    fun check(expectedText: String, vararg expectedOps: String) {
      val result = inner.result()
      assertEquals(expectedText, result.string())
      assertEquals(expectedOps.toList(), applied)
      assertEquals(applied, inner.ops().map { it.toString() })
      val folded = inner.ops().fold(start) { text, op -> text.applyOp(op) }
      assertEquals(expectedText, folded.string())
    }
  }

  /**
   * A text that records every op it applies, and passes the rest to [inner].
   */
  private class RecordingText(
    private val inner: DocText,
    private val ops: MutableList<String>,
  ) : DocText by inner {
    override fun applyOp(op: DocTextOp): DocText {
      ops.add(op.toString())
      return RecordingText(inner.applyOp(op), ops)
    }
  }
}

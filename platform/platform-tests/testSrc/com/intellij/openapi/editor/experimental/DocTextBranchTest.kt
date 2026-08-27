// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocTextBranchTest {

  @Test
  fun `local edits match a plain DocText`() {
    var plain = DocText.createText("fun main() {\n  println()\n}\n")
    var branch: DocTextBranch = DocTextBranch.createBranch(plain.string(), agent("user"))
    val ops = listOf(
      insertOp(13, "  val x = 1\n"),
      deleteOp(0, 4),
      insertOp(0, "private fun "),
      deleteOp(20, 3),
    )
    for (op in ops) {
      plain = plain.applyOp(op)
      branch = branch.applyOp(op)
      assertSameText(plain, branch.text())
    }
    val tail = insertOp(plain.length(), "// end")
    assertSameText(plain.applyOp(tail), branch.applyOp(tail).text())
  }

  @Test
  fun `concurrent inserts converge and order by agent`() {
    val base = DocTextBranch.createBranch("", agent("a"))
    val a = base.applyOp(insertOp(0, "X"))
    val b = base.fork(agent("b")).applyOp(insertOp(0, "Y"))
    val ab = a.merge(b)
    val ba = b.merge(a)
    // Fugue breaks the tie by the agent order, so both replicas see the same text.
    assertEquals("XY", ab.string())
    assertEquals("XY", ba.string())
  }

  @Test
  fun `concurrent runs do not interleave`() {
    val base = DocTextBranch.createBranch("()", agent("a"))
    val a = base.applyOp(insertOp(1, "111"))
    val b = base.fork(agent("b")).applyOp(insertOp(1, "222"))
    val merged = a.merge(b)
    assertEquals("(111222)", merged.string())
    assertEquals(merged.string(), b.merge(a).string())
  }

  @Test
  fun `an insert into a concurrently deleted range survives`() {
    val base = DocTextBranch.createBranch("abcdef", agent("a"))
    val a = base.applyOp(deleteOp(1, 3)) // deletes "bcd" -> "aef"
    val b = base.fork(agent("b")).applyOp(insertOp(3, "X")) // -> "abcXdef"
    val ab = a.merge(b)
    val ba = b.merge(a)
    assertEquals("aXef", ab.string())
    assertEquals("aXef", ba.string())
  }

  @Test
  fun `a concurrent delete of one char deletes it once`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    val a = base.applyOp(deleteOp(1, 1))
    val b = base.fork(agent("b")).applyOp(deleteOp(1, 1))
    assertEquals("ac", a.merge(b).string())
    assertEquals("ac", b.merge(a).string())
  }

  @Test
  fun `a merge with an ancestor changes nothing`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    val a = base.applyOp(insertOp(3, "def"))
    assertSame(a, a.merge(base))
  }

  @Test
  fun `a merge with a descendant fast-forwards`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    val b = base.fork(agent("b")).applyOp(insertOp(3, "def")).applyOp(deleteOp(0, 1))
    val merged = base.merge(b)
    assertEquals("bcdef", merged.string())
    assertEquals(b.string(), merged.string())
  }

  @Test
  fun `a repeated merge changes nothing`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    val a = base.applyOp(insertOp(0, "1"))
    val b = base.fork(agent("b")).applyOp(insertOp(3, "2"))
    val merged = a.merge(b)
    assertSame(merged, merged.merge(b))
    assertSame(merged, merged.merge(a))
  }

  @Test
  fun `edits continue after a merge`() {
    val base = DocTextBranch.createBranch("start\n", agent("a"))
    val a = base.applyOp(insertOp(6, "from a\n"))
    val b = base.fork(agent("b")).applyOp(insertOp(6, "from b\n"))
    val merged = a.merge(b)
    val edited = merged.applyOp(insertOp(merged.length(), "end\n"))
    val c = merged.fork(agent("c")).applyOp(insertOp(0, "top\n"))
    val again = edited.merge(c)
    assertEquals(again.string(), c.merge(edited).string())
    assertEquals("top\nstart\nfrom a\nfrom b\nend\n", again.string())
  }

  @Test
  fun `the merged text matches a replay of the merged graph`() {
    val base = DocTextBranch.createBranch("one\ntwo\n", agent("a"))
    val a = base.applyOp(insertOp(4, "1.5\n"))
    val b = base.fork(agent("b")).applyOp(deleteOp(0, 4))
    val merged = a.merge(b)
    assertEquals(merged.graph().replay().string(), merged.string())
  }

  @Test
  fun `the line structure after a merge matches a fresh DocText`() {
    val base = DocTextBranch.createBranch("a\nb\nc\n", agent("a"))
    val a = base.applyOp(insertOp(2, "a2\n"))
    val b = base.fork(agent("b")).applyOp(deleteOp(4, 2)).applyOp(insertOp(0, "top\n"))
    val merged = a.merge(b)
    assertSameText(DocText.createText(merged.string()), merged.text())
  }

  @Test
  fun `an empty op keeps the instance`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    assertSame(base, base.applyOp(insertOp(1, "")))
    assertSame(base, base.applyOp(deleteOp(1, 0)))
  }

  @Test
  fun `a merge with an unedited fork keeps the instance`() {
    val base = DocTextBranch.createBranch("abc", agent("a")).applyOp(insertOp(0, "x"))
    val fork = base.fork(agent("b"))
    assertSame(base, base.merge(fork))
  }

  @Test
  fun `concurrent inserts at the opposite ends converge`() {
    val base = DocTextBranch.createBranch("mid", agent("a"))
    val left = base.applyOp(insertOp(0, "L"))
    val right = base.fork(agent("b")).applyOp(insertOp(3, "R"))
    assertEquals("LmidR", left.merge(right).string())
    assertEquals("LmidR", right.merge(left).string())
  }

  @Test
  fun `concurrent overlapping deletes erase the union of the ranges`() {
    val base = DocTextBranch.createBranch("abcdef", agent("a"))
    val a = base.applyOp(deleteOp(1, 2)) // deletes "bc" -> "adef"
    val b = base.fork(agent("b")).applyOp(deleteOp(2, 2)) // deletes "cd" -> "abef"
    assertEquals("aef", a.merge(b).string())
    assertEquals("aef", b.merge(a).string())
  }

  @Test
  fun `concurrent deletes that cover the text erase all of it`() {
    val base = DocTextBranch.createBranch("abcdef", agent("a"))
    val a = base.applyOp(deleteOp(0, 4)) // -> "ef"
    val b = base.fork(agent("b")).applyOp(deleteOp(2, 4)) // -> "ab"
    assertEquals("", a.merge(b).string())
    assertEquals("", b.merge(a).string())
  }

  @Test
  fun `a criss-cross merge converges`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    val a1 = base.applyOp(insertOp(0, "1"))
    val b1 = base.fork(agent("b")).applyOp(insertOp(3, "2"))
    // Both sides merge each other, then edit again, then merge again.
    val a2 = a1.merge(b1).applyOp(insertOp(0, "3"))
    val b2 = b1.merge(a1).applyOp(insertOp(0, "4"))
    val forward = a2.merge(b2)
    val backward = b2.merge(a2)
    assertEquals(forward.string(), backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
    for (marker in listOf("1", "2", "3", "4")) {
      assertTrue(forward.string().contains(marker)) { "The marker $marker is lost in '${forward.string()}'" }
    }
    assertSameText(DocText.createText(forward.string()), forward.text())
  }

  @Test
  fun `an incremental sync equals a fresh merge`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    val a1 = base.applyOp(insertOp(3, "1"))
    var b1 = base.fork(agent("b")).applyOp(insertOp(0, "2"))
    val first = a1.merge(b1)
    b1 = b1.applyOp(insertOp(0, "3"))
    val incremental = first.merge(b1)
    val fresh = a1.merge(b1)
    assertEquals(fresh.string(), incremental.string())
    assertEquals(incremental.graph().replay().string(), incremental.string())
  }

  @Test
  fun `an edit based on a pre-merge state merges cleanly`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    val a1 = base.applyOp(deleteOp(1, 1)) // -> "ac"
    val b1 = base.fork(agent("b")).applyOp(insertOp(2, "Z")) // -> "abZc"
    val merged = a1.merge(b1) // -> "aZc"
    // The next edit descends from a1, not from the merge.
    val a2 = a1.applyOp(insertOp(1, "W")) // -> "aWc"
    // The replay must advance over a1's delete again; this is the advance-of-a-delete path.
    val forward = merged.merge(a2)
    val backward = a2.merge(merged)
    assertEquals("aWZc", forward.string())
    assertEquals("aWZc", backward.string())
  }

  @Test
  fun `three replicas converge in every merge order`() {
    val base = DocTextBranch.createBranch("base\n", agent("m"))
    val replicas = listOf(
      base.fork(agent("a")).applyOp(insertOp(0, "aa")),
      base.fork(agent("b")).applyOp(insertOp(5, "bb")),
      base.fork(agent("c")).applyOp(deleteOp(0, 2)),
    )
    val orders = listOf(
      listOf(0, 1, 2), listOf(0, 2, 1),
      listOf(1, 0, 2), listOf(1, 2, 0),
      listOf(2, 0, 1), listOf(2, 1, 0),
    )
    val texts = HashSet<String>()
    for (order in orders) {
      val merged = order.map { replicas[it] }.reduce { acc, replica -> acc.merge(replica) }
      texts.add(merged.string())
    }
    assertEquals(1, texts.size) { "The merge orders diverge: $texts" }
  }

  @Test
  fun `surrogate pairs survive a concurrent merge`() {
    val base = DocTextBranch.createBranch("ab", agent("a"))
    val rocket = base.applyOp(insertOp(1, "🚀")) // 🚀
    val smile = base.fork(agent("b")).applyOp(insertOp(1, "🙂")) // 🙂
    val forward = rocket.merge(smile)
    val backward = smile.merge(rocket)
    // The runs do not interleave, so both pairs stay intact.
    assertEquals("a🚀🙂b", forward.string())
    assertEquals(forward.string(), backward.string())
    assertEquals(4, forward.string().codePointCount(0, forward.length()))
  }

  @Test
  fun `branches with no common history merge deterministically`() {
    val first = DocTextBranch.createBranch("ab", agent("a"))
    val second = DocTextBranch.createBranch("cd", agent("b"))
    // This is correct CRDT behavior, not the intended use: both texts survive as runs.
    assertEquals("abcd", first.merge(second).string())
    assertEquals("abcd", second.merge(first).string())
  }

  @Test
  fun `CRLF separators merge and match a fresh DocText`() {
    val base = DocTextBranch.createBranch("a\r\nb", agent("a"))
    val a = base.applyOp(insertOp(1, "X")) // -> "aX\r\nb"
    val b = base.fork(agent("b")).applyOp(deleteOp(1, 1)) // deletes '\r' -> "a\nb"
    val forward = a.merge(b)
    assertEquals("aX\nb", forward.string())
    assertEquals(forward.string(), b.merge(a).string())
    assertEquals(2, forward.text().lineCount())
    assertEquals(1, forward.text().lineSeparatorLength(0))
    assertSameText(DocText.createText("aX\nb"), forward.text())
  }

  @Test
  fun `an invalid op throws and keeps the branch usable`() {
    val base = DocTextBranch.createBranch("abc", agent("a"))
    assertThrows(IndexOutOfBoundsException::class.java) { base.applyOp(insertOp(4, "X")) }
    assertThrows(IndexOutOfBoundsException::class.java) { base.applyOp(insertOp(-1, "X")) }
    assertThrows(IndexOutOfBoundsException::class.java) { base.applyOp(deleteOp(2, 5)) }
    // The failed ops left no events behind.
    assertEquals(3, base.graph().size())
    assertEquals("abcX", base.applyOp(insertOp(3, "X")).string())
  }

  @Test
  fun `divergent edits on one instance stay independent`() {
    val base = DocTextBranch.createBranch("ab", agent("a"))
    val first = base.applyOp(insertOp(0, "X"))
    val second = base.applyOp(insertOp(2, "Y"))
    // Both values are usable; the shared storage copies on the divergence.
    // They must not merge with each other: one agent edited both, which the contract forbids.
    assertEquals("ab", base.string())
    assertEquals("Xab", first.string())
    assertEquals("abY", second.string())
    assertEquals(2, base.graph().size())
    assertEquals(3, first.graph().size())
    assertEquals(3, second.graph().size())
  }

  @Test
  fun `a fork that reuses an agent stays consistent after merges`() {
    val base = DocTextBranch.createBranch("ab", agent("a"))
    val b1 = base.fork(agent("b")).applyOp(insertOp(2, "1"))
    val merged = base.merge(b1)
    // The agent "b" resumes on a fresh fork; its seq numbers must continue, not restart.
    val resumed = merged.fork(agent("b")).applyOp(insertOp(0, "2"))
    val other = merged.fork(agent("c")).applyOp(insertOp(0, "3"))
    val forward = resumed.merge(other)
    val backward = other.merge(resumed)
    assertEquals(forward.string(), backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
    for (marker in listOf("1", "2", "3")) {
      assertTrue(forward.string().contains(marker)) { "The marker $marker is lost in '${forward.string()}'" }
    }
  }
}

internal fun agent(name: String): Agent = Agent.createAgent(name)

internal fun DocTextBranch.string(): String = text().string()

internal fun DocTextBranch.length(): Int = text().length()

internal fun insertOp(offset: Int, fragment: CharSequence): DocOp.Insert {
  return object : DocOp.Insert {
    override fun offset(): Int = offset
    override fun fragment(): CharSequence = fragment
  }
}

internal fun deleteOp(offset: Int, length: Int): DocOp.Delete {
  return object : DocOp.Delete {
    override fun offset(): Int = offset
    override fun length(): Int = length
  }
}

internal fun assertSameText(expected: DocText, actual: DocText) {
  assertEquals(expected.string(), actual.string())
  assertEquals(expected.chars().toString(), actual.chars().toString())
  assertEquals(expected.length(), actual.length())
  assertEquals(expected.lineCount(), actual.lineCount())
  for (line in 0 until expected.lineCount()) {
    assertEquals(expected.lineStartOffset(line), actual.lineStartOffset(line)) { "lineStartOffset($line)" }
    assertEquals(expected.lineEndOffset(line), actual.lineEndOffset(line)) { "lineEndOffset($line)" }
    assertEquals(expected.lineSeparatorLength(line), actual.lineSeparatorLength(line)) { "lineSeparatorLength($line)" }
  }
  for (offset in 0 until expected.length()) {
    assertEquals(expected.lineNumber(offset), actual.lineNumber(offset)) { "lineNumber($offset)" }
  }
  if (expected.length() > 2) {
    val range = TextRange(1, expected.length() - 1)
    assertEquals(expected.string(range), actual.string(range))
  }
}

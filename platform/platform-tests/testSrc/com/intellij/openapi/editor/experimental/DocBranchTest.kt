// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DocBranchTest {

  @Test
  fun `local edits match a plain DocText`() {
    var plain = DocText.createText("fun main() {\n  println()\n}\n")
    var branch: DocBranch = DocBranch.createBranch(plain.string(), agent("user"))
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
    val base = DocBranch.createBranch("", agent("a"))
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
    val base = DocBranch.createBranch("()", agent("a"))
    val a = base.applyOp(insertOp(1, "111"))
    val b = base.fork(agent("b")).applyOp(insertOp(1, "222"))
    val merged = a.merge(b)
    assertEquals("(111222)", merged.string())
    assertEquals(merged.string(), b.merge(a).string())
  }

  @Test
  fun `an insert into a concurrently deleted range survives`() {
    val base = DocBranch.createBranch("abcdef", agent("a"))
    val a = base.applyOp(deleteOp(1, 3)) // deletes "bcd" -> "aef"
    val b = base.fork(agent("b")).applyOp(insertOp(3, "X")) // -> "abcXdef"
    val ab = a.merge(b)
    val ba = b.merge(a)
    assertEquals("aXef", ab.string())
    assertEquals("aXef", ba.string())
  }

  @Test
  fun `a concurrent delete of one char deletes it once`() {
    val base = DocBranch.createBranch("abc", agent("a"))
    val a = base.applyOp(deleteOp(1, 1))
    val b = base.fork(agent("b")).applyOp(deleteOp(1, 1))
    assertEquals("ac", a.merge(b).string())
    assertEquals("ac", b.merge(a).string())
  }

  @Test
  fun `a merge with an ancestor changes nothing`() {
    val base = DocBranch.createBranch("abc", agent("a"))
    val a = base.applyOp(insertOp(3, "def"))
    assertSame(a, a.merge(base))
  }

  @Test
  fun `a merge with a descendant fast-forwards`() {
    val base = DocBranch.createBranch("abc", agent("a"))
    val b = base.fork(agent("b")).applyOp(insertOp(3, "def")).applyOp(deleteOp(0, 1))
    val merged = base.merge(b)
    assertEquals("bcdef", merged.string())
    assertEquals(b.string(), merged.string())
  }

  @Test
  fun `a repeated merge changes nothing`() {
    val base = DocBranch.createBranch("abc", agent("a"))
    val a = base.applyOp(insertOp(0, "1"))
    val b = base.fork(agent("b")).applyOp(insertOp(3, "2"))
    val merged = a.merge(b)
    assertSame(merged, merged.merge(b))
    assertSame(merged, merged.merge(a))
  }

  @Test
  fun `edits continue after a merge`() {
    val base = DocBranch.createBranch("start\n", agent("a"))
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
    val base = DocBranch.createBranch("one\ntwo\n", agent("a"))
    val a = base.applyOp(insertOp(4, "1.5\n"))
    val b = base.fork(agent("b")).applyOp(deleteOp(0, 4))
    val merged = a.merge(b)
    assertEquals(merged.graph().replay().string(), merged.string())
  }

  @Test
  fun `the line structure after a merge matches a fresh DocText`() {
    val base = DocBranch.createBranch("a\nb\nc\n", agent("a"))
    val a = base.applyOp(insertOp(2, "a2\n"))
    val b = base.fork(agent("b")).applyOp(deleteOp(4, 2)).applyOp(insertOp(0, "top\n"))
    val merged = a.merge(b)
    assertSameText(DocText.createText(merged.string()), merged.text())
  }

  @Test
  fun `an empty op keeps the instance`() {
    val base = DocBranch.createBranch("abc", agent("a"))
    assertSame(base, base.applyOp(insertOp(1, "")))
    assertSame(base, base.applyOp(deleteOp(1, 0)))
  }

  @Test
  fun `a merge of two merged branches converges`() {
    val base = DocBranch.createBranch("abc\n", agent("a"))
    val a = base.applyOp(insertOp(0, "1"))
    val b = base.fork(agent("b")).applyOp(insertOp(4, "2"))
    val c = base.fork(agent("c")).applyOp(deleteOp(1, 1))
    // Both sides of the final merge are merge results with multi-head versions.
    val left = a.merge(b)
    val right = b.merge(c)
    val forward = left.merge(right)
    val backward = right.merge(left)
    assertEquals("1ac\n2", forward.string())
    assertEquals(forward.string(), backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
    assertSameText(DocText.createText(forward.string()), forward.text())
  }

  @Test
  fun `a concurrent edit survives a full deletion`() {
    val base = DocBranch.createBranch("abcdef", agent("a"))
    val wipe = base.applyOp(deleteOp(0, 6))
    val edit = base.fork(agent("b")).applyOp(insertOp(3, "X"))
    assertEquals("X", wipe.merge(edit).string())
    assertEquals("X", edit.merge(wipe).string())
  }

  @Test
  fun `alternating pulls converge over many rounds`() {
    // A staircase history: the common ancestor of every merge climbs round by round.
    var x = DocBranch.createBranch("seed\n", agent("x"))
    var y = x.fork(agent("y"))
    for (round in 0 until 12) {
      x = x.applyOp(insertOp(0, "x$round "))
      y = y.applyOp(insertOp(y.text().length(), " y$round"))
      if (round % 2 == 0) {
        x = x.merge(y)
      } else {
        y = y.merge(x)
      }
      val forward = x.merge(y)
      val backward = y.merge(x)
      assertEquals(forward.string(), backward.string()) { "round $round" }
      assertEquals(forward.graph().replay().string(), forward.string()) { "round $round" }
    }
    val merged = x.merge(y)
    assertSameText(DocText.createText(merged.string()), merged.text())
    for (round in 0 until 12) {
      assertTrue(merged.string().contains("x$round")) { "The edit x$round is lost" }
      assertTrue(merged.string().contains("y$round")) { "The edit y$round is lost" }
    }
  }

  @Test
  fun `a merge applies pastes and deletions as batches`() {
    val base = DocBranch.createBranch("line1\nline2\nline3\n", agent("a"))
    val a = base.applyOp(insertOp(0, "top\n"))
    // The other branch mixes a multi-char paste, a range deletion, and another paste,
    // so the merge sink flushes at every kind and position boundary.
    val b = base.fork(agent("b"))
      .applyOp(insertOp(6, "pasted block\n"))
      .applyOp(deleteOp(0, 6))
      .applyOp(insertOp(0, "L1\n"))
    val forward = a.merge(b)
    val backward = b.merge(a)
    assertEquals(forward.string(), backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
    for (marker in listOf("top\n", "L1\n", "pasted block\n")) {
      assertTrue(forward.string().contains(marker)) { "The fragment '$marker' is lost" }
    }
    assertTrue(!forward.string().contains("line1"))
    assertSameText(DocText.createText(forward.string()), forward.text())
  }

  @Test
  fun `a fork from a stale value mints fresh seqs`() {
    val base = DocBranch.createBranch("xy", agent("a"))
    // The abandoned fork makes two runs, so its first run lands in the shared store beyond the
    // base's own size. A single run would stay in the fork's own value.
    val abandoned = base.fork(agent("b")).applyOp(insertOp(0, "A")).applyOp(insertOp(0, "Z"))
    // A fork of the same agent from the stale base must not see those units.
    val fork = base.fork(agent("b")).applyOp(insertOp(2, "B"))
    assertEquals("xyB", fork.string())
    assertEquals("xyB", base.merge(fork).string())
    // The abandoned branch stays intact; it is never merged with its twin.
    assertEquals("ZAxy", abandoned.string())
  }

  @Test
  fun `a fragment op makes one run`() {
    val base = DocBranch.createBranch("abc", agent("a"))
    assertEquals(3, base.graph().size())
    assertEquals(1, base.graph().runCount())
    val edited = base.applyOp(insertOp(1, "xyz")).applyOp(deleteOp(0, 2))
    assertEquals(8, edited.graph().size())
    assertEquals(3, edited.graph().runCount())
  }

  @Test
  fun `a resumed agent continues its seqs after multi-unit runs`() {
    val base = DocBranch.createBranch("0123", agent("a"))
    val b1 = base.fork(agent("b")).applyOp(insertOp(4, "bbb")).applyOp(deleteOp(0, 2))
    val merged = base.merge(b1) // the agent "b" owns five unit ids now, in two runs
    val resumed = merged.fork(agent("b")).applyOp(insertOp(0, "RR"))
    val sibling = merged.fork(agent("c")).applyOp(insertOp(0, "CC"))
    val forward = resumed.merge(sibling)
    val backward = sibling.merge(resumed)
    assertEquals(forward.string(), backward.string())
    // A seq collision would make the merge drop "RR" as an already known run.
    assertTrue(forward.string().contains("RR"))
    assertTrue(forward.string().contains("CC"))
    assertEquals(forward.graph().replay().string(), forward.string())
  }

  @Test
  fun `a large document costs one run and fast-forwards without a replay`() {
    val size = 100_000
    val text = buildString {
      repeat(size) { append('x') }
    }
    val base = DocBranch.createBranch(text, agent("a"))
    assertEquals(size, base.graph().size())
    assertEquals(1, base.graph().runCount())
    val edited = base.fork(agent("b")).applyOp(insertOp(size, "end"))
    // A fast-forward adopts the descendant's text; a full replay here would time out.
    val merged = base.merge(edited)
    assertEquals(size + 3, merged.text().length())
    assertEquals(2, merged.graph().runCount())
    assertTrue(merged.string().endsWith("end"))
  }

  @Test
  fun `a merge with an unedited fork keeps the instance`() {
    val base = DocBranch.createBranch("abc", agent("a")).applyOp(insertOp(0, "x"))
    val fork = base.fork(agent("b"))
    assertSame(base, base.merge(fork))
  }

  @Test
  fun `concurrent inserts at the opposite ends converge`() {
    val base = DocBranch.createBranch("mid", agent("a"))
    val left = base.applyOp(insertOp(0, "L"))
    val right = base.fork(agent("b")).applyOp(insertOp(3, "R"))
    assertEquals("LmidR", left.merge(right).string())
    assertEquals("LmidR", right.merge(left).string())
  }

  @Test
  fun `concurrent overlapping deletes erase the union of the ranges`() {
    val base = DocBranch.createBranch("abcdef", agent("a"))
    val a = base.applyOp(deleteOp(1, 2)) // deletes "bc" -> "adef"
    val b = base.fork(agent("b")).applyOp(deleteOp(2, 2)) // deletes "cd" -> "abef"
    assertEquals("aef", a.merge(b).string())
    assertEquals("aef", b.merge(a).string())
  }

  @Test
  fun `concurrent deletes that cover the text erase all of it`() {
    val base = DocBranch.createBranch("abcdef", agent("a"))
    val a = base.applyOp(deleteOp(0, 4)) // -> "ef"
    val b = base.fork(agent("b")).applyOp(deleteOp(2, 4)) // -> "ab"
    assertEquals("", a.merge(b).string())
    assertEquals("", b.merge(a).string())
  }

  @Test
  fun `a criss-cross merge converges`() {
    val base = DocBranch.createBranch("abc", agent("a"))
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
    val base = DocBranch.createBranch("abc", agent("a"))
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
    val base = DocBranch.createBranch("abc", agent("a"))
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
    val base = DocBranch.createBranch("base\n", agent("m"))
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
    val base = DocBranch.createBranch("ab", agent("a"))
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
    val first = DocBranch.createBranch("ab", agent("a"))
    val second = DocBranch.createBranch("cd", agent("b"))
    // This is correct CRDT behavior, not the intended use: both texts survive as runs.
    assertEquals("abcd", first.merge(second).string())
    assertEquals("abcd", second.merge(first).string())
  }

  @Test
  fun `CRLF separators merge and match a fresh DocText`() {
    val base = DocBranch.createBranch("a\r\nb", agent("a"))
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
    val base = DocBranch.createBranch("abc", agent("a"))
    assertThrows(IndexOutOfBoundsException::class.java) { base.applyOp(insertOp(4, "X")) }
    assertThrows(IndexOutOfBoundsException::class.java) { base.applyOp(insertOp(-1, "X")) }
    assertThrows(IndexOutOfBoundsException::class.java) { base.applyOp(deleteOp(2, 5)) }
    // The failed ops left no events behind.
    assertEquals(3, base.graph().size())
    assertEquals("abcX", base.applyOp(insertOp(3, "X")).string())
  }

  @Test
  fun `divergent edits on one instance stay independent`() {
    val base = DocBranch.createBranch("ab", agent("a"))
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
    val base = DocBranch.createBranch("ab", agent("a"))
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

  @Test
  fun `a self merge keeps the instance`() {
    val base = DocBranch.createBranch("abc", agent("a"))
    val edited = base.applyOp(insertOp(3, "d"))
    assertSame(edited, edited.merge(edited))
  }

  @Test
  fun `a second round over unrelated histories converges`() {
    // Two branches with no common history join into a concatenation. The joined graph
    // holds two parentless runs, so its version has two heads. The next round merges
    // above that two-head version.
    val first = DocBranch.createBranch("aaa\n", agent("a"))
    val second = DocBranch.createBranch("bbb\n", agent("b"))
    val joined = first.merge(second)
    assertEquals("aaa\nbbb\n", joined.string())
    val left = joined.applyOp(insertOp(0, "L"))
    // The delete crosses the seam between the two parentless runs.
    val right = joined.fork(agent("c")).applyOp(deleteOp(2, 4))
    val forward = left.merge(right)
    val backward = right.merge(left)
    assertEquals("Laab\n", forward.string())
    assertEquals(forward.string(), backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
    assertSameText(DocText.createText(forward.string()), forward.text())
  }

  @Test
  fun `a concurrent run beside a long run keeps both runs whole`() {
    // The long run makes the replay walk the implicit parent chain inside one run,
    // and the delete run removes units from the middle of it.
    val base = DocBranch.createBranch("()", agent("a"))
    val a = base
      .applyOp(insertOp(1, "0123456789"))
      .applyOp(deleteOp(5, 3)) // removes "456" -> "(0123789)"
    val b = base.fork(agent("b")).applyOp(insertOp(1, "ABC"))
    val forward = a.merge(b)
    val backward = b.merge(a)
    assertEquals("(0123789ABC)", forward.string())
    assertEquals(forward.string(), backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
  }

  @Test
  fun `a concurrent insert survives a delete run over the whole region`() {
    // The delete run consumes six placeholder units one by one, so the merge splits
    // the single placeholder span again and again.
    val base = DocBranch.createBranch("abcdefghij", agent("a"))
    val a = base.applyOp(deleteOp(2, 6)) // removes "cdefgh" -> "abij"
    val b = base.fork(agent("b"))
      .applyOp(insertOp(5, "X")) // inside the deleted range
      .applyOp(insertOp(0, "Y")) // before it
    val forward = a.merge(b)
    val backward = b.merge(a)
    assertEquals("YabXij", forward.string())
    assertEquals(forward.string(), backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
  }

  @Test
  fun `an insert at the end of an emptied document keeps its place`() {
    // The placeholder span is longer than the document at the common ancestor, so the
    // trailing units sit after every reachable position. The insert lands at the end of
    // the ancestor document, which is exactly the boundary of those trailing units.
    val base = DocBranch.createBranch("abcdefgh", agent("a"))
    val wipe = base.applyOp(deleteOp(0, 8))
    assertEquals("", wipe.string())
    val tail = base.fork(agent("b")).applyOp(insertOp(8, "TAIL"))
    val forward = wipe.merge(tail)
    val backward = tail.merge(wipe)
    assertEquals("TAIL", forward.string())
    assertEquals("TAIL", backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
  }

  @Test
  fun `three concurrent runs at one anchor converge in every order`() {
    // Every run has the same left origin and the same right parent, so the tie-break
    // by agent decides the whole order. This is the case the integrate scan resolves.
    val base = DocBranch.createBranch("<>", agent("m"))
    val replicas = listOf("a", "b", "c").map { name ->
      base.fork(agent(name)).applyOp(insertOp(1, name.repeat(3)))
    }
    val orders = listOf(
      listOf(0, 1, 2), listOf(0, 2, 1),
      listOf(1, 0, 2), listOf(1, 2, 0),
      listOf(2, 0, 1), listOf(2, 1, 0),
    )
    for (order in orders) {
      val merged = order.map { replicas[it] }.reduce { acc, replica -> acc.merge(replica) }
      assertEquals("<aaabbbccc>", merged.string()) { "the order $order" }
      assertEquals(merged.graph().replay().string(), merged.string()) { "the order $order" }
    }
  }

  @Test
  fun `an edit based on a pre-merge delete run merges cleanly`() {
    val base = DocBranch.createBranch("abcdef", agent("a"))
    val a1 = base.applyOp(deleteOp(1, 3)) // -> "aef"
    val b1 = base.fork(agent("b")).applyOp(insertOp(3, "Z")) // -> "abcZdef"
    val merged = a1.merge(b1) // -> "aZef"
    assertEquals("aZef", merged.string())
    // The next edit descends from a1, so the replay must advance over the delete run.
    val a2 = a1.applyOp(insertOp(1, "W")) // -> "aWef"
    val forward = merged.merge(a2)
    val backward = a2.merge(merged)
    assertEquals("aWZef", forward.string())
    assertEquals("aWZef", backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
  }

  @Test
  fun `a merge of two branches that share an event id fails loudly`() {
    // One agent edited the same value twice, which the contract forbids: both results
    // hold the id (a, 3). Before the merge check, the merge silently kept one edit and
    // dropped the other, and the two directions returned different texts.
    val base = DocBranch.createBranch("abc", agent("a"))
    val sameSpot = base.applyOp(insertOp(0, "X"))
    for (clash in listOf(insertOp(0, "Y"), insertOp(2, "Y"), insertOp(0, "YY"), deleteOp(0, 1))) {
      val other = base.applyOp(clash)
      assertThrows(IllegalArgumentException::class.java, { sameSpot.merge(other) }, "$clash")
      assertThrows(IllegalArgumentException::class.java, { other.merge(sameSpot) }, "$clash")
    }
    // The same id with the same operation is a normal re-merge, not a clash.
    val twin = base.applyOp(insertOp(0, "X"))
    assertSame(sameSpot, sameSpot.merge(twin))
  }

  @Test
  fun `a concurrent delete inside a long run splits the span`() {
    // One paste op makes one run, and the replay covers it with one span. The other
    // branch deletes the middle of that run, so the merge must split the span in three
    // and retreat only the middle part.
    val base = DocBranch.createBranch("[]", agent("a"))
    val a = base.applyOp(insertOp(1, "0123456789"))
    val b = base.fork(agent("b")).merge(a).applyOp(deleteOp(4, 4)) // removes "3456"
    val a2 = a.applyOp(insertOp(6, "X")) // inside the range that b deletes
    val forward = b.merge(a2)
    val backward = a2.merge(b)
    assertEquals("[012X789]", forward.string())
    assertEquals(forward.string(), backward.string())
    assertEquals(forward.graph().replay().string(), forward.string())
  }

  @Test
  fun `a replay at a merged past version returns that merged text`() {
    val base = DocBranch.createBranch("abc", agent("a"))
    val a1 = base.applyOp(insertOp(3, "1"))
    val b1 = base.fork(agent("b")).applyOp(insertOp(0, "2"))
    val merged = a1.merge(b1)
    // The version of a merge has two heads; a replay at it must select that event set.
    val mergedVersion = merged.graph().version()
    val later = merged.applyOp(insertOp(0, "Z")).applyOp(deleteOp(1, 2))
    assertEquals(merged.string(), later.graph().replay(mergedVersion).string())
    assertEquals(later.string(), later.graph().replay().string())
  }
}

internal fun agent(name: String): Agent = Agent.createAgent(name)

internal fun DocBranch.string(): String = text().string()

internal fun DocBranch.length(): Int = text().length()

internal fun insertOp(offset: Int, fragment: CharSequence): DocOp.Insert = DocOp.ins(offset, fragment)

internal fun deleteOp(offset: Int, length: Int): DocOp.Delete = DocOp.del(offset, length)

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

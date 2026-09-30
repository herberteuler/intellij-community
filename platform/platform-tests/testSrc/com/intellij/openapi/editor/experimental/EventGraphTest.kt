// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventGraphTest {

  @Test
  fun `an empty graph has the root version and replays to an empty text`() {
    val graph = EventGraph.createGraph()
    assertEquals(0, graph.size())
    assertEquals(0, graph.runCount())
    assertTrue(graph.version().isRoot())
    assertEquals(Version.root(), graph.version())
    assertEquals("", graph.replay().string())
  }

  @Test
  fun `an appended run advances the size by its length`() {
    val empty = EventGraph.createGraph()
    val event = Event.createInsert(agent("u"), 0, 0, "xyz")
    val graph = empty.append(event, empty.version())
    assertEquals(3, graph.size())
    assertEquals(1, graph.runCount())
    assertFalse(graph.version().isRoot())
    assertEquals("xy", graph.replay(Version.of(1)).string())
    assertEquals("xyz", graph.replay().string())
    // The old value did not change.
    assertEquals(0, empty.size())
  }

  @Test
  fun `concurrent root inserts order by agent`() {
    // The reference README example: two users insert at 0 concurrently; the result is "AB".
    val root = Version.root()
    val graph = EventGraph.createGraph()
      .append(Event.createInsert(agent("user1"), 0, 0, "A"), root)
      .append(Event.createInsert(agent("user2"), 0, 0, "B"), root)
    assertEquals("AB", graph.replay().string())
  }

  @Test
  fun `concurrent runs order by agent and do not interleave`() {
    val root = Version.root()
    val graph = EventGraph.createGraph()
      .append(Event.createInsert(agent("user1"), 0, 0, "AAA"), root)
      .append(Event.createInsert(agent("user2"), 0, 0, "BBB"), root)
    assertEquals("AAABBB", graph.replay().string())
    assertEquals(6, graph.size())
    assertEquals(2, graph.runCount())
  }

  @Test
  fun `a replay at a past version returns the past text`() {
    val u = agent("u")
    var graph = EventGraph.createGraph()
    graph = graph.append(Event.createInsert(u, 0, 0, "hi"), graph.version())
    val past = graph.version()
    graph = graph.append(Event.createInsert(u, 2, 2, "!"), graph.version())
    graph = graph.append(Event.createDelete(u, 3, 0, 1), graph.version())
    assertEquals("i!", graph.replay().string())
    assertEquals("hi", graph.replay(past).string())
    assertEquals("", graph.replay(Version.root()).string())
  }

  @Test
  fun `mergeFrom converges from both sides`() {
    var base = EventGraph.createGraph()
    base = base.append(Event.createInsert(agent("u"), 0, 0, "x"), base.version())
    val left = base.append(Event.createInsert(agent("a"), 0, 1, "A"), base.version())
    val right = base.append(Event.createInsert(agent("b"), 0, 1, "B"), base.version())
    val forward = left.mergeFrom(right)
    val backward = right.mergeFrom(left)
    assertEquals(3, forward.size())
    assertEquals(3, backward.size())
    assertEquals("xAB", forward.replay().string())
    assertEquals("xAB", backward.replay().string())
  }

  @Test
  fun `mergeFrom skips the events the graph already has`() {
    var base = EventGraph.createGraph()
    base = base.append(Event.createInsert(agent("u"), 0, 0, "x"), base.version())
    val bigger = base.append(Event.createInsert(agent("a"), 0, 1, "A"), base.version())
    assertEquals(2, bigger.mergeFrom(base).size())
    assertEquals(2, base.mergeFrom(bigger).size())
    assertEquals("xA", base.mergeFrom(bigger).replay().string())
  }

  @Test
  fun `mergeFrom splits a partly known run`() {
    val u = agent("u")
    // One replica minted "abcd" as one run; the other knows only its first half, as its own run.
    val whole = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "abcd"), Version.root())
    val prefix = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "ab"), Version.root())
    val merged = prefix.mergeFrom(whole)
    assertEquals("abcd", merged.replay().string())
    assertEquals(4, merged.size())
    assertEquals(1, merged.runCount()) // the appended "cd" suffix extends the known "ab" run
    // The reverse direction knows every unit already, so nothing is appended.
    assertEquals(4, whole.mergeFrom(prefix).size())
    assertEquals(1, whole.mergeFrom(prefix).runCount())
  }

  @Test
  fun `mergeFrom splits a partly known delete run`() {
    val u = agent("u")
    val d = agent("d")
    val base = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "abcdef"), Version.root())
    // One replica deleted "bcde" as one run; the other knows only the "bc" half.
    val whole = base.append(Event.createDelete(d, 0, 1, 4), base.version())
    val prefix = base.append(Event.createDelete(d, 0, 1, 2), base.version())
    val forward = prefix.mergeFrom(whole)
    assertEquals("af", forward.replay().string())
    assertEquals("af", whole.mergeFrom(prefix).replay().string())
    assertEquals(10, forward.size())
    assertEquals(2, forward.runCount()) // the insert, and the known "bc" half that the "de" half extends
  }

  @Test
  fun `mergeFrom counts a prefix known as several pieces`() {
    val u = agent("u")
    // A run of another agent comes between the two pieces, so "cd" cannot extend "ab".
    var pieces = EventGraph.createGraph()
    pieces = pieces.append(Event.createInsert(u, 0, 0, "ab"), pieces.version())
    val afterAb = pieces.version()
    pieces = pieces.append(Event.createInsert(agent("v"), 0, 2, "Z"), afterAb)
    pieces = pieces.append(Event.createInsert(u, 2, 2, "cd"), afterAb)
    val whole = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "abcdef"), Version.root())
    val merged = pieces.mergeFrom(whole)
    assertEquals("abcdefZ", merged.replay().string())
    assertEquals("abcdefZ", whole.mergeFrom(pieces).replay().string())
    assertEquals(7, merged.size())
    assertEquals(3, merged.runCount()) // "ab", "Z", and "cd" that the appended "ef" suffix extends
  }

  @Test
  fun `a run appended at a mid-run version merges cleanly`() {
    val u = agent("u")
    val v = agent("v")
    // One replica minted "abcdef" as one run.
    val whole = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "abcdef"), Version.root())
    // Another replica knows only "abcd" and appends "XY" there. In the merged graph,
    // the parent of "XY" points into the middle of the "abcdef" run.
    var other = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "abcd"), Version.root())
    other = other.append(Event.createInsert(v, 0, 4, "XY"), other.version())
    val forward = whole.mergeFrom(other)
    val backward = other.mergeFrom(whole)
    // "ef" and "XY" are concurrent at the same anchor; the agent order puts "ef" first.
    assertEquals("abcdefXY", forward.replay().string())
    assertEquals("abcdefXY", backward.replay().string())
    assertEquals(8, forward.size())
  }

  @Test
  fun `an event exposes its run fields`() {
    val insert = Event.createInsert(agent("u"), 5, 2, "abc")
    assertEquals(agent("u"), insert.agent())
    assertEquals(5, insert.seq())
    assertEquals(3, insert.length())
    val insertOp = insert.op() as DocOp.Insert
    assertEquals(2, insertOp.offset())
    assertEquals("abc", insertOp.fragment().toString())
    val delete = Event.createDelete(agent("u"), 8, 1, 4)
    assertEquals(8, delete.seq())
    assertEquals(4, delete.length())
    assertEquals(1, (delete.op() as DocOp.Delete).offset())
  }

  @Test
  fun `a long fragment stays short in a message`() {
    // An insert fragment can be a whole pasted file, and toString reaches the messages of
    // the id checks. A line break escapes too, so a message stays on one line.
    val short = Event.createInsert(agent("u"), 0, 0, "a\nb")
    assertTrue(short.toString().contains("\"a\\nb\"")) { short.toString() }

    val pasted = Event.createInsert(agent("u"), 0, 0, "x".repeat(5000))
    val message = pasted.toString()
    assertTrue(message.length < 100) { "The message is not short: $message" }
    assertTrue(message.contains("...")) { message }
    assertTrue(message.contains("5000 chars")) { message }

    // The same protection reaches the message of a rejected id, through the event.
    val graph = EventGraph.createGraph().append(pasted, Version.root())
    val clash = assertThrows(IllegalArgumentException::class.java) {
      graph.append(Event.createInsert(agent("u"), 0, 0, "y".repeat(5000)), Version.root())
    }
    assertTrue(clash.message!!.length < 200) { "The message is not short: ${clash.message}" }
  }

  /**
   * An event keeps the exact op it records. The other half of the claim is a compile-time
   * one that no assert can state: an event is not a [DocOp], so it cannot reach
   * [DocText.applyOp] at all.
   */
  @Test
  fun `an event wraps the op it records`() {
    val op = DocOp.ins(2, "abc")
    val insert = Event.create(agent("u"), 0, op)
    assertSame(op, insert.op())
    assertEquals(3, insert.length())
    val delete = Event.create(agent("u"), 3, DocOp.del(1, 4))
    assertEquals(1, (delete.op() as DocOp.Delete).offset())
    assertEquals(4, delete.length())
  }

  @Test
  fun `concurrent deletes of one character replay once`() {
    var graph = EventGraph.createGraph()
    graph = graph.append(Event.createInsert(agent("u"), 0, 0, "x"), graph.version())
    val base = graph.version()
    graph = graph.append(Event.createDelete(agent("a"), 0, 0, 1), base)
    graph = graph.append(Event.createDelete(agent("b"), 0, 0, 1), base)
    assertEquals("", graph.replay().string())
    assertEquals("x", graph.replay(base).string())
  }

  @Test
  fun `an overlapping event id is rejected`() {
    val root = Version.root()
    val graph = EventGraph.createGraph().append(Event.createInsert(agent("u"), 0, 0, "xy"), root)
    // The exact same first id.
    assertThrows(IllegalArgumentException::class.java) {
      graph.append(Event.createInsert(agent("u"), 0, 1, "z"), root)
    }
    // An id inside the stored run.
    assertThrows(IllegalArgumentException::class.java) {
      graph.append(Event.createDelete(agent("u"), 1, 0, 1), graph.version())
    }
    // A range that overlaps the stored run's tail.
    assertThrows(IllegalArgumentException::class.java) {
      graph.append(Event.createInsert(agent("u"), 1, 0, "zz"), root)
    }
  }

  @Test
  fun `a version of a larger graph is rejected`() {
    var small = EventGraph.createGraph()
    small = small.append(Event.createInsert(agent("u"), 0, 0, "x"), small.version())
    val big = small.append(Event.createInsert(agent("u"), 1, 1, "y"), small.version())
    assertThrows(IllegalArgumentException::class.java) {
      small.append(Event.createInsert(agent("v"), 0, 0, "z"), big.version())
    }
    assertThrows(IllegalArgumentException::class.java) {
      small.replay(big.version())
    }
  }

  @Test
  fun `an invalid event is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { Event.createInsert(agent("u"), -1, 0, "x") }
    assertThrows(IllegalArgumentException::class.java) { Event.createInsert(agent("u"), 0, -1, "x") }
    assertThrows(IllegalArgumentException::class.java) { Event.createInsert(agent("u"), 0, 0, "") }
    assertThrows(IllegalArgumentException::class.java) { Event.createDelete(agent("u"), 0, 0, 0) }
    assertThrows(IllegalArgumentException::class.java) { Event.createDelete(agent("u"), 0, -1, 1) }
    assertThrows(IllegalArgumentException::class.java) { Agent.createAgent("") }
  }

  @Test
  fun `an overflowing event is rejected`() {
    val u = agent("u")
    // The run's id range would wrap past Int.MAX_VALUE.
    assertThrows(IllegalArgumentException::class.java) { Event.createInsert(u, Int.MAX_VALUE, 0, "ab") }
    assertThrows(IllegalArgumentException::class.java) { Event.createDelete(u, Int.MAX_VALUE - 1, 0, 3) }
    // The run's position range would wrap past Int.MAX_VALUE.
    assertThrows(IllegalArgumentException::class.java) { Event.createInsert(u, 0, Int.MAX_VALUE, "ab") }
    // The exact boundary stays legal: the last id is Int.MAX_VALUE - 1.
    Event.createInsert(u, Int.MAX_VALUE - 2, 0, "xy")
    Event.createDelete(u, Int.MAX_VALUE - 1, 0, 1)
  }

  @Test
  fun `a merge of two events that share an id is rejected`() {
    val u = agent("u")
    val root = Version.root()
    val mine = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "ab"), root)
    // Each graph gives the ids (u, 0) and (u, 1) to different operations. The check
    // samples the two ends of the SHARED SEQ RANGE, so a difference at either end is caught.
    val clashes = listOf(
      Event.createInsert(u, 0, 0, "Zb"), // another character at the first unit
      Event.createInsert(u, 0, 0, "aZ"), // another character at the last unit
      Event.createInsert(u, 0, 1, "ab"), // another position
      Event.createDelete(u, 0, 0, 2), // another kind
    )
    for (clash in clashes) {
      val theirs = EventGraph.createGraph().append(clash, root)
      assertThrows(EventIdClashException::class.java, { mine.mergeFrom(theirs) }, "$clash")
      assertThrows(EventIdClashException::class.java, { theirs.mergeFrom(mine) }, "$clash")
    }
    // A different run cut over the same operations stays legal. A run of another agent comes
    // between the two units, so "b" cannot extend "a".
    var recut = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "a"), root)
    val afterA = recut.version()
    recut = recut.append(Event.createInsert(agent("v"), 0, 1, "Q"), afterA)
    recut = recut.append(Event.createInsert(u, 1, 1, "b"), afterA)
    assertEquals("abQ", mine.mergeFrom(recut).replay().string())
    assertEquals("abQ", recut.mergeFrom(mine).replay().string())
    // A clash is caught whichever graph is further ahead, because the sample stops at the
    // end of the shared range and not at the end of either history.
    val ahead = mine.append(Event.createInsert(u, 2, 2, "c"), mine.version())
    val clashingPrefix = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "Zb"), root)
    assertThrows(IllegalArgumentException::class.java) { ahead.mergeFrom(clashingPrefix) }
    assertThrows(IllegalArgumentException::class.java) { clashingPrefix.mergeFrom(ahead) }
    // A shared range of ONE seq has both ends at seq 0, so the second sample is skipped.
    val oneUnit = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "a"), root)
    val otherUnit = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "Q"), root)
    assertThrows(IllegalArgumentException::class.java) { oneUnit.mergeFrom(otherUnit) }
    assertThrows(IllegalArgumentException::class.java) { otherUnit.mergeFrom(oneUnit) }
  }

  /**
   * A DELIBERATE limit, and the price of a merge that costs the change instead of the
   * session: the id check samples only the two ends of the shared seq range, so a clash
   * strictly inside it goes unseen and the two graphs then disagree.
   *
   * This test exists to keep that visible. Do not "fix" it by comparing every shared id:
   * that makes every merge cost the whole shared history. See `checkSharedIds`.
   */
  @Test
  fun `a clash inside the shared range is not caught`() {
    val u = agent("u")
    val root = Version.root()
    fun graphOf(middle: String): EventGraph {
      var graph = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "a"), root)
      graph = graph.append(Event.createInsert(u, 1, 1, middle), graph.version())
      return graph.append(Event.createInsert(u, 2, 2, "c"), graph.version())
    }
    val mine = graphOf("b")
    val theirs = graphOf("Z")
    // Both ends of the shared range agree, so neither merge throws.
    assertEquals("abc", mine.mergeFrom(theirs).replay().string())
    assertEquals("aZc", theirs.mergeFrom(mine).replay().string())
  }

  @Test
  fun `an append at a stale version keeps the other heads`() {
    val u = agent("u")
    var graph = EventGraph.createGraph()
    graph = graph.append(Event.createInsert(u, 0, 0, "ab"), graph.version())
    val afterAb = graph.version()
    graph = graph.append(Event.createInsert(agent("x"), 0, 2, "X"), afterAb)
    val afterX = graph.version()
    // Two more runs branch off the stale versions, so the frontier keeps three heads.
    graph = graph.append(Event.createInsert(agent("y"), 0, 2, "Y"), afterAb)
    graph = graph.append(Event.createInsert(agent("z"), 0, 3, "Z"), afterX)
    assertEquals(5, graph.size())
    // "X" and "Y" share the left origin "b", so the agent order puts "X" first. "Z"
    // hangs off "X", so it lands between "X" and "Y".
    assertEquals("abXZY", graph.replay().string())
    assertEquals("abX", graph.replay(afterX).string())
    assertEquals("ab", graph.replay(afterAb).string())
  }

  /**
   * The seqs of one agent must ascend and leave no gap. That rejects a reused id, and it is
   * also what lets a merge describe a whole history with one integer per agent, so it can
   * skip a shared history without reading it.
   */
  @Test
  fun `an append must continue the seqs of its agent`() {
    val u = agent("u")
    var graph = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "abc"), Version.root())
    // A reused seq, a seq inside the stored range, and a gap are all rejected.
    for (seq in intArrayOf(0, 1, 2, 4, 100)) {
      assertThrows(
        IllegalArgumentException::class.java,
        { graph.append(Event.createInsert(u, seq, 3, "x"), graph.version()) },
        "seq $seq",
      )
    }
    // Only the exact next seq is accepted.
    graph = graph.append(Event.createInsert(u, 3, 3, "de"), graph.version())
    assertEquals("abcde", graph.replay().string())
    // Each agent owns its own seq space, so a second agent starts again at 0.
    val v = agent("v")
    graph = graph.append(Event.createInsert(v, 0, 5, "Z"), graph.version())
    assertEquals("abcdeZ", graph.replay().string())
    // The two spaces advanced on their own: u is at 5 now, and v is at 1.
    assertEquals(8, graph.append(Event.createInsert(u, 5, 6, "fg"), graph.version()).size())
    assertEquals(7, graph.append(Event.createInsert(v, 1, 6, "Y"), graph.version()).size())
    assertThrows(IllegalArgumentException::class.java) {
      graph.append(Event.createInsert(v, 5, 6, "Y"), graph.version())
    }
  }

  @Test
  fun `a graph value answers for its own seqs`() {
    val u = agent("u")
    val older = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "ab"), Version.root())
    // Two siblings both continue the older value, so both legally mint the seq 2. An older
    // value must not see the seq that a newer one took.
    val newer = older.append(Event.createInsert(u, 2, 2, "c"), older.version())
    val sibling = older.append(Event.createInsert(u, 2, 2, "C"), older.version())
    assertEquals("abc", newer.replay().string())
    assertEquals("abC", sibling.replay().string())
    // The newer value moved on: it wants 3 and refuses 2. The older value still wants 2.
    assertEquals(4, newer.append(Event.createInsert(u, 3, 3, "d"), newer.version()).size())
    assertThrows(IllegalArgumentException::class.java) {
      newer.append(Event.createInsert(u, 2, 2, "d"), newer.version())
    }
  }

  /**
   * The delta merge collects the new runs per AGENT and then sorts them by lv. Without that
   * sort a run would reach the graph before a parent that another agent authored.
   */
  @Test
  fun `a merge appends the new runs in a parent-first order`() {
    val u = agent("u")
    val v = agent("v")
    var src = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "a"), Version.root())
    // The two agents alternate, so every run but the first has a parent of the OTHER agent.
    // Per-agent order alone would be u0, u1, u2, v0, v1, v2, which is not causal.
    for (step in 0 until 3) {
      src = src.append(Event.createInsert(v, step, 1 + 2 * step, "B"), src.version())
      src = src.append(Event.createInsert(u, 1 + step, 2 + 2 * step, "a"), src.version())
    }
    assertEquals(7, src.size())
    val expected = src.replay().string()
    assertEquals("aBaBaBa", expected)
    // An empty graph has to take all seven, in an order where every parent lands first.
    assertEquals(expected, EventGraph.createGraph().mergeFrom(src).replay().string())
    // So does a graph that already holds the first three.
    var behind = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "a"), Version.root())
    behind = behind.append(Event.createInsert(v, 0, 1, "B"), behind.version())
    behind = behind.append(Event.createInsert(u, 1, 2, "a"), behind.version())
    val caughtUp = behind.mergeFrom(src)
    assertEquals(expected, caughtUp.replay().string())
    assertEquals(7, caughtUp.size())
  }

  @Test
  fun `a merge appends only what the graph is missing`() {
    val u = agent("u")
    val root = Version.root()
    val short = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "ab"), root)
    // "cd" goes to the front, so it cannot extend "ab" and stays a run of its own.
    val long = short.append(Event.createInsert(u, 2, 0, "cd"), short.version())
    // Nothing new: the run count does not move.
    assertEquals(2, long.mergeFrom(long).runCount())
    assertEquals(1, short.mergeFrom(short).runCount())
    // One new run in one direction, none in the other.
    assertEquals(2, short.mergeFrom(long).runCount())
    assertEquals("cdab", short.mergeFrom(long).replay().string())
    assertEquals(2, long.mergeFrom(short).runCount())
    // An empty graph takes everything; an empty source brings nothing.
    assertEquals(2, EventGraph.createGraph().mergeFrom(long).runCount())
    assertEquals(2, long.mergeFrom(EventGraph.createGraph()).runCount())
    assertEquals(0, EventGraph.createGraph().mergeFrom(EventGraph.createGraph()).size())
  }

  @Test
  fun `a merge is one-sided per agent`() {
    val u = agent("u")
    val v = agent("v")
    val root = Version.root()
    val base = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "a"), root)
    // "mine" is ahead for u and knows nothing of v; "theirs" is the mirror image.
    val mine = base.append(Event.createInsert(u, 1, 1, "b"), base.version())
    val theirs = base.append(Event.createInsert(v, 0, 1, "Z"), base.version())
    val forward = mine.mergeFrom(theirs)
    val backward = theirs.mergeFrom(mine)
    assertEquals(3, forward.size())
    assertEquals(3, backward.size())
    assertEquals(forward.replay().string(), backward.replay().string())
    // An agent that only this graph knows brings nothing back from the other side.
    assertEquals(3, forward.mergeFrom(theirs).size())
  }

  /**
   * The delta merge binary searches the per-agent index twice: once for what this graph
   * knows, and once for the first entry that goes past it. Both land in the MIDDLE here, and
   * an off-by-one in either one hides on the short histories the other tests build.
   *
   * Every append inserts at the front, so no run extends the one before it, and the index
   * gets one entry per append.
   */
  @Test
  fun `a merge finds the boundary inside a long per-agent history`() {
    val u = agent("u")
    val v = agent("v")
    var base = EventGraph.createGraph()
    repeat(HALF) { i ->
      base = base.append(Event.createInsert(u, i, 0, "a"), base.version())
    }
    // The same agent doubles its history, so the destination knows an exact middle prefix.
    var ahead = base
    repeat(HALF) { i ->
      ahead = ahead.append(Event.createInsert(u, HALF + i, 0, "b"), ahead.version())
    }
    // A second agent forks off the middle prefix, so the merge also has to place its runs.
    var side = base
    repeat(20) { i ->
      side = side.append(Event.createInsert(v, i, 0, "Z"), side.version())
    }

    // Catching up over the boundary: every run of the second half arrives, and no more.
    val caughtUp = base.mergeFrom(ahead)
    assertEquals(2 * HALF, caughtUp.size())
    assertEquals(2 * HALF, caughtUp.runCount())
    assertEquals("b".repeat(HALF) + "a".repeat(HALF), caughtUp.replay().string())
    // The reverse direction knows it all already.
    assertEquals(2 * HALF, ahead.mergeFrom(base).size())
    assertEquals(2 * HALF, ahead.mergeFrom(base).runCount())

    // Each side holds a part the other lacks, and the two converge.
    val forward = side.mergeFrom(ahead)
    val backward = ahead.mergeFrom(side)
    assertEquals(2 * HALF + 20, forward.size())
    assertEquals(2 * HALF + 20, backward.size())
    assertEquals(forward.replay().string(), backward.replay().string())
    assertEquals(forward.replay().string(), forward.mergeFrom(backward).replay().string())
  }

  /**
   * Two siblings of one value append to the same per-agent index, and each must answer only for
   * its own ids. The siblings share every node of the base, so an append that changed a shared
   * node in place would let one sibling see the other's runs. A break would not throw: it would
   * leave every search of the index quietly wrong.
   */
  @Test
  fun `a sibling append keeps its own id index`() {
    val u = agent("u")
    val v = agent("v")
    var base = EventGraph.createGraph()
    // Two agents interleave, so each agent's runs sit in its own tree while the runs themselves
    // arrive interleaved in lv order.
    repeat(10) { i ->
      base = base.append(Event.createInsert(u, i, 2 * i, "a"), base.version())
      base = base.append(Event.createInsert(v, i, 2 * i + 1, "B"), base.version())
    }
    // Both appends close the newest run of base into their own trees, and both give the id
    // (u, 10) to a character of their own.
    val tip = base.append(Event.createInsert(u, 10, 20, "x"), base.version())
    var copied = base.append(Event.createInsert(u, 10, 20, "y"), base.version())
    assertEquals(21, tip.size())
    assertEquals(21, copied.size())
    // A run of a third agent closes "y" into the trees. So every question about u and v below
    // reaches the agent index, and the newest run answers none of them.
    copied = copied.append(Event.createInsert(agent("w"), 0, 21, "W"), copied.version())

    // The index answers every question an append and a merge ask of it.
    assertEquals(23, copied.append(Event.createInsert(u, 11, 22, "z"), copied.version()).size())
    assertEquals(23, copied.append(Event.createInsert(v, 10, 22, "C"), copied.version()).size())
    for (seq in intArrayOf(0, 5, 10)) {
      assertThrows(
        IllegalArgumentException::class.java,
        { copied.append(Event.createInsert(u, seq, 22, "z"), copied.version()) },
        "seq $seq",
      )
    }
    // The two siblings gave the id (u, 10) to different characters. That sits at the LAST
    // shared seq, which is the end the check samples.
    assertThrows(IllegalArgumentException::class.java) { tip.mergeFrom(copied) }
    assertThrows(IllegalArgumentException::class.java) { copied.mergeFrom(tip) }
  }

  @Test
  fun `a merge splits a straddling run that other runs follow`() {
    val u = agent("u")
    val v = agent("v")
    val root = Version.root()
    // The source minted "abcd" as one run, then another agent appended after it.
    var src = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "abcd"), root)
    src = src.append(Event.createInsert(v, 0, 4, "Z"), src.version())
    // The destination knows only the first half of that run, so the merge has to split it
    // AND keep the later run of the other agent after the split piece.
    val dest = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "ab"), root)
    val merged = dest.mergeFrom(src)
    assertEquals("abcdZ", merged.replay().string())
    assertEquals(5, merged.size())
    assertEquals(2, merged.runCount()) // "ab" that the appended "cd" suffix extends, and "Z"
    assertEquals("abcdZ", src.mergeFrom(dest).replay().string())
  }

  @Test
  fun `a delete that runs past the document says so`() {
    // The raw API does not compare an offset against the document, so a graph can hold a
    // delete of more units than the document at its parents has. The replay must then name
    // the run and not raise a bare index error.
    val graph = EventGraph.createGraph()
      .append(Event.createInsert(agent("u"), 0, 0, "ab"), Version.root())
      .append(Event.createDelete(agent("v"), 0, 0, 5), Version.of(1))
    val failure = assertThrows(IllegalArgumentException::class.java) { graph.replay() }
    assertTrue(
      failure.message!!.contains("reached the end of the item list"),
      "Unexpected message: ${failure.message}",
    )
  }

  @Test
  fun `agents compare by the name`() {
    assertTrue(agent("a") < agent("b"))
    assertTrue(agent("b") > agent("a"))
    assertEquals(0, agent("a").compareTo(agent("a")))
    assertEquals(agent("a"), agent("a"))
  }

  @Test
  fun `an insert past the document says so`() {
    // Like the delete above: the raw API takes the offset as it is, and the replay names the fault.
    val graph = EventGraph.createGraph()
      .append(Event.createInsert(agent("u"), 0, 0, "ab"), Version.root())
      .append(Event.createInsert(agent("v"), 0, 9, "x"), Version.of(1))
    val failure = assertThrows(IllegalArgumentException::class.java) { graph.replay() }
    assertTrue(
      failure.message!!.contains("not long enough"),
      "Unexpected message: ${failure.message}",
    )
  }

  @Test
  fun `a foreign event is rejected`() {
    // The graph keeps an event forever, so it takes only the implementation that checks itself.
    // This one reports a length that no real event can have. Every other check of the append
    // passes it, and the graph would then shrink to two units and lose the run.
    val graph = EventGraph.createGraph().append(Event.createInsert(agent("u"), 0, 0, "abc"), Version.root())
    val real = Event.createDelete(agent("v"), 0, 0, 1)
    val foreign = object : Event by real {
      override fun length(): Int = -1
    }
    val failure = assertThrows(IllegalArgumentException::class.java) { graph.append(foreign, graph.version()) }
    assertTrue(failure.message!!.contains("Foreign Event"), "Unexpected message: ${failure.message}")
  }

  @Test
  fun `a foreign agent is rejected where it enters`() {
    // The id order compares agents, and a foreign agent would fail only at the first compare.
    val foreign = object : Agent {
      override fun compareTo(other: Agent): Int = 0
    }
    assertThrows(IllegalArgumentException::class.java) { Event.createInsert(foreign, 0, 0, "x") }
    assertThrows(IllegalArgumentException::class.java) { DocBranch.createBranch("", foreign) }
  }

  @Test
  fun `a foreign op is rejected`() {
    // Only DocOp.ins and DocOp.del detach the content from a sequence the caller can change.
    val foreign = object : DocOp.Insert {
      override fun offset(): Int = 0
      override fun length(): Int = 1
      override fun fragment(): CharSequence = "x"
    }
    assertThrows(IllegalArgumentException::class.java) { Event.create(agent("u"), 0, foreign) }
  }

  @Test
  fun `an insert keeps its fragment when the caller changes the builder`() {
    val builder = StringBuilder("ab")
    val op = DocOp.ins(0, builder)
    builder.setLength(0)
    builder.append("zz")
    assertEquals("ab", op.fragment().toString())
    val graph = EventGraph.createGraph().append(Event.create(agent("u"), 0, op), Version.root())
    assertEquals("ab", graph.replay().string())
  }

  @Test
  fun `an append past the unit space is rejected`() {
    // A delete holds no content, so one run can take almost the whole unit space for free.
    val huge = EventGraph.createGraph().append(Event.createDelete(agent("u"), 0, 0, Int.MAX_VALUE - 1), Version.root())
    assertEquals(Int.MAX_VALUE - 1, huge.size())
    assertThrows(IllegalArgumentException::class.java) {
      huge.append(Event.createDelete(agent("v"), 0, 0, 2), huge.version())
    }
    // The last unit still fits.
    assertEquals(Int.MAX_VALUE, huge.append(Event.createDelete(agent("v"), 0, 0, 1), huge.version()).size())
  }

  @Test
  fun `a merge past the unit space is rejected`() {
    // Each graph fits on its own, but their union needs one unit more than the space has.
    val half = Int.MAX_VALUE / 2 + 1
    val a = EventGraph.createGraph().append(Event.createDelete(agent("u"), 0, 0, half), Version.root())
    val b = EventGraph.createGraph().append(Event.createDelete(agent("v"), 0, 0, half), Version.root())
    assertThrows(IllegalArgumentException::class.java) { a.mergeFrom(b) }
    assertThrows(IllegalArgumentException::class.java) { b.mergeFrom(a) }
    assertEquals(half, a.size())
  }

  @Test
  fun `a shared id with other parents is rejected`() {
    // One agent minted (v, 0) twice: once after "ab", and once at the root. The kind, the
    // position and the character match, so only the parents tell the two operations apart.
    val u = agent("u")
    val v = agent("v")
    val base = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "ab"), Version.root())
    val afterBase = base.append(Event.createInsert(v, 0, 0, "x"), base.version())
    val atRoot = EventGraph.createGraph().append(Event.createInsert(v, 0, 0, "x"), Version.root())
    val clash = assertThrows(EventIdClashException::class.java) { afterBase.mergeFrom(atRoot) }
    assertEquals(v, clash.agent())
    assertEquals(0, clash.seq())
    assertThrows(EventIdClashException::class.java) { atRoot.mergeFrom(afterBase) }
  }

  @Test
  fun `a shared id with several parents compares them as a set of ids`() {
    val u = agent("u")
    val v = agent("v")
    val w = agent("w")
    val root = Version.root()
    // Two graphs append the root inserts of u and v in the opposite orders, so the parents of
    // (w, 0) have other lvs in each graph but the same ids.
    val uFirst = EventGraph.createGraph()
      .append(Event.createInsert(u, 0, 0, "a"), root)
      .append(Event.createInsert(v, 0, 0, "b"), root)
    val vFirst = EventGraph.createGraph()
      .append(Event.createInsert(v, 0, 0, "b"), root)
      .append(Event.createInsert(u, 0, 0, "a"), root)
    val one = uFirst.append(Event.createInsert(w, 0, 2, "c"), uFirst.version())
    val other = vFirst.append(Event.createInsert(w, 0, 2, "c"), vFirst.version())
    assertEquals("abc", one.mergeFrom(other).replay().string())
    assertEquals("abc", other.mergeFrom(one).replay().string())
    // Here (w, 0) names two parents too, but (v, 1) and not (v, 0).
    val later = vFirst.append(Event.createInsert(v, 1, 1, "d"), Version.of(0))
    val clashing = later.append(Event.createInsert(w, 0, 2, "c"), Version.of(1, 2))
    val clash = assertThrows(EventIdClashException::class.java) { one.mergeFrom(clashing) }
    assertEquals(w, clash.agent())
    assertThrows(EventIdClashException::class.java) { clashing.mergeFrom(one) }
  }

  private companion object {
    /**
     * The runs in each half of the long per-agent history. It has to be big enough that a
     * binary search over the index takes several steps, so an off-by-one cannot pass.
     */
    const val HALF = 40
  }
}

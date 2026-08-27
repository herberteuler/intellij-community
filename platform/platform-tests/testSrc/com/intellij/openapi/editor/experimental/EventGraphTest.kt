// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    val graph = empty.append(Event.createInsert(agent("u"), 0, 0, "xyz"), empty.version())
    assertEquals(3, graph.size())
    assertEquals(1, graph.runCount())
    assertFalse(graph.version().isRoot())
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
    assertEquals(2, merged.runCount()) // "ab" plus the appended "cd" suffix
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
    assertEquals(3, forward.runCount()) // the insert, the known "bc" half, the appended "de" half
  }

  @Test
  fun `mergeFrom counts a prefix known as several pieces`() {
    val u = agent("u")
    var pieces = EventGraph.createGraph()
    pieces = pieces.append(Event.createInsert(u, 0, 0, "ab"), pieces.version())
    pieces = pieces.append(Event.createInsert(u, 2, 2, "cd"), pieces.version())
    val whole = EventGraph.createGraph().append(Event.createInsert(u, 0, 0, "abcdef"), Version.root())
    val merged = pieces.mergeFrom(whole)
    assertEquals("abcdef", merged.replay().string())
    assertEquals(6, merged.size())
    assertEquals(3, merged.runCount()) // "ab", "cd", and the appended "ef" suffix
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
    assertEquals(2, insert.pos())
    assertEquals(3, insert.length())
    assertEquals("abc", insert.content().toString())
    val delete = Event.createDelete(agent("u"), 8, 1, 4)
    assertEquals(8, delete.seq())
    assertEquals(1, delete.pos())
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
  fun `agents compare by the name`() {
    assertTrue(agent("a") < agent("b"))
    assertTrue(agent("b") > agent("a"))
    assertEquals(0, agent("a").compareTo(agent("a")))
    assertEquals(agent("a"), agent("a"))
  }
}

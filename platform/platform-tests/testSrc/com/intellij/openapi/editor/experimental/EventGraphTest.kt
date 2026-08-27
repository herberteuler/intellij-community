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
    assertTrue(graph.version().isRoot())
    assertEquals(Version.root(), graph.version())
    assertEquals("", graph.replay().string())
  }

  @Test
  fun `an append advances the size and the version`() {
    val empty = EventGraph.createGraph()
    val graph = empty.append(Event.createInsert(agent("u"), 0, 0, 'x'), empty.version())
    assertEquals(1, graph.size())
    assertFalse(graph.version().isRoot())
    assertEquals("x", graph.replay().string())
    // The old value did not change.
    assertEquals(0, empty.size())
  }

  @Test
  fun `concurrent root inserts order by agent`() {
    // The reference README example: two users insert at 0 concurrently; the result is "AB".
    val root = Version.root()
    val graph = EventGraph.createGraph()
      .append(Event.createInsert(agent("user1"), 0, 0, 'A'), root)
      .append(Event.createInsert(agent("user2"), 0, 0, 'B'), root)
    assertEquals("AB", graph.replay().string())
  }

  @Test
  fun `a replay at a past version returns the past text`() {
    val u = agent("u")
    var graph = EventGraph.createGraph()
    graph = graph.append(Event.createInsert(u, 0, 0, 'h'), graph.version())
    graph = graph.append(Event.createInsert(u, 1, 1, 'i'), graph.version())
    val past = graph.version()
    graph = graph.append(Event.createInsert(u, 2, 2, '!'), graph.version())
    graph = graph.append(Event.createDelete(u, 3, 0), graph.version())
    assertEquals("i!", graph.replay().string())
    assertEquals("hi", graph.replay(past).string())
    assertEquals("", graph.replay(Version.root()).string())
  }

  @Test
  fun `mergeFrom converges from both sides`() {
    var base = EventGraph.createGraph()
    base = base.append(Event.createInsert(agent("u"), 0, 0, 'x'), base.version())
    val left = base.append(Event.createInsert(agent("a"), 0, 1, 'A'), base.version())
    val right = base.append(Event.createInsert(agent("b"), 0, 1, 'B'), base.version())
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
    base = base.append(Event.createInsert(agent("u"), 0, 0, 'x'), base.version())
    val bigger = base.append(Event.createInsert(agent("a"), 0, 1, 'A'), base.version())
    assertEquals(2, bigger.mergeFrom(base).size())
    assertEquals(2, base.mergeFrom(bigger).size())
    assertEquals("xA", base.mergeFrom(bigger).replay().string())
  }

  @Test
  fun `concurrent deletes of one character replay once`() {
    var graph = EventGraph.createGraph()
    graph = graph.append(Event.createInsert(agent("u"), 0, 0, 'x'), graph.version())
    val base = graph.version()
    graph = graph.append(Event.createDelete(agent("a"), 0, 0), base)
    graph = graph.append(Event.createDelete(agent("b"), 0, 0), base)
    assertEquals("", graph.replay().string())
    assertEquals("x", graph.replay(base).string())
  }

  @Test
  fun `a duplicate event id is rejected`() {
    val root = Version.root()
    val graph = EventGraph.createGraph().append(Event.createInsert(agent("u"), 0, 0, 'x'), root)
    assertThrows(IllegalArgumentException::class.java) {
      graph.append(Event.createInsert(agent("u"), 0, 1, 'y'), root)
    }
    assertThrows(IllegalArgumentException::class.java) {
      graph.append(Event.createDelete(agent("u"), 0, 0), graph.version())
    }
  }

  @Test
  fun `a version of a larger graph is rejected`() {
    var small = EventGraph.createGraph()
    small = small.append(Event.createInsert(agent("u"), 0, 0, 'x'), small.version())
    val big = small.append(Event.createInsert(agent("u"), 1, 1, 'y'), small.version())
    assertThrows(IllegalArgumentException::class.java) {
      small.append(Event.createInsert(agent("v"), 0, 0, 'z'), big.version())
    }
    assertThrows(IllegalArgumentException::class.java) {
      small.replay(big.version())
    }
  }

  @Test
  fun `an invalid event is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { Event.createInsert(agent("u"), -1, 0, 'x') }
    assertThrows(IllegalArgumentException::class.java) { Event.createInsert(agent("u"), 0, -1, 'x') }
    assertThrows(IllegalArgumentException::class.java) { Event.createDelete(agent("u"), 0, -1) }
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

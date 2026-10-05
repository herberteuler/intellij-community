// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.ex.DocumentOp
import com.intellij.openapi.editor.ex.experimental.Agent
import com.intellij.openapi.editor.ex.experimental.Event
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.BitSet
import java.util.Random

/**
 * Tests what [diff] and [findConflicting] return, against the set algebra of [eventsOf]. A text test cannot see a common ancestor that is valid but
 * too low: the text stays right, and only the walk grows. This test pins the greatest ancestor.
 *
 * The random graphs fork from inside runs, merge past versions, and have several roots.
 */
internal class EventGraphWalkTest {

  @Test
  fun `diff and findConflicting match the set algebra of eventsOf`() {
    val random = Random(20261001)
    val agents = (0 until 3).map { Agent.createAgent("agent$it") }
    repeat(ROUNDS) { round ->
      var graph = EventGraphImpl.empty()
      val versions = arrayListOf(LvVersion.ROOT)
      repeat(1 + random.nextInt(25)) {
        val agent = agents[random.nextInt(agents.size)]
        val parents = randomParents(random, graph, versions)
        val text = "x".repeat(1 + random.nextInt(4))
        val event = Event.createInsert(agent, graph.nextSeqFor(agent), continuingOffset(graph, agent, parents), text)
        graph = graph.appendAt(event, parents)
        versions.add(graph.lvVersion())
      }
      repeat(PAIRS) {
        val a = randomVersion(random, graph, versions)
        val b = randomVersion(random, graph, versions)
        checkWalks(graph, a, b) { "round $round, a=$a, b=$b" }
      }
    }
  }

  private fun checkWalks(
    graph: EventGraphImpl,
    a: LvVersion,
    b: LvVersion,
    where: () -> String,
  ) {
    val eventsA = graph.eventsOf(a)
    val eventsB = graph.eventsOf(b)
    val diff = graph.diff(a.lvs, b.lvs)
    assertEquals(without(eventsA, eventsB), bitsOf(diff.aOnly)) { "aOnly, ${where()}" }
    assertEquals(without(eventsB, eventsA), bitsOf(diff.bOnly)) { "bOnly, ${where()}" }
    val conflict = graph.findConflicting(a.lvs, b.lvs)
    val fresh = bitsOf(conflict.newRanges)
    val conflicting = bitsOf(conflict.conflictRanges)
    val ancestor = graph.eventsOf(conflict.commonAncestor)
    assertEquals(without(eventsB, eventsA), fresh) { "newRanges, ${where()}" }
    assertFalse(fresh.intersects(conflicting)) { "overlap, ${where()}" }
    assertEquals(greatestAncestor(graph, eventsA, eventsB), ancestor) { "ancestor, ${where()}" }
    assertEquals(without(joined(eventsA, eventsB), ancestor), joined(fresh, conflicting)) { "coverage, ${where()}" }
  }

  /**
   * The largest set of shared events that every other event of the union descends from.
   */
  private fun greatestAncestor(graph: EventGraphImpl, eventsA: BitSet, eventsB: BitSet): BitSet {
    val all = joined(eventsA, eventsB)
    val ancestor = (eventsA.clone() as BitSet).apply { and(eventsB) }
    do {
      val before = ancestor.cardinality()
      var lv = all.nextSetBit(0)
      while (lv >= 0) {
        if (!ancestor.get(lv)) {
          ancestor.and(graph.eventsOf(LvVersion(intArrayOf(lv))))
        }
        lv = all.nextSetBit(lv + 1)
      }
    } while (ancestor.cardinality() != before)
    return ancestor
  }

  /**
   * The tip, a fork from inside a run, a merge of two past versions, or one past version.
   */
  private fun randomParents(
    random: Random,
    graph: EventGraphImpl,
    versions: List<LvVersion>,
  ): LvVersion {
    if (graph.size() == 0) {
      return LvVersion.ROOT
    }
    return when (random.nextInt(4)) {
      0 -> graph.lvVersion()
      1 -> LvVersion(intArrayOf(random.nextInt(graph.size())))
      2 -> {
        val one = versions[random.nextInt(versions.size)]
        val other = versions[random.nextInt(versions.size)]
        reduced(graph, (one.lvs + other.lvs).toList())
      }
      else -> versions[random.nextInt(versions.size)]
    }
  }

  private fun randomVersion(
    random: Random,
    graph: EventGraphImpl,
    versions: List<LvVersion>,
  ): LvVersion {
    if (graph.size() == 0 || random.nextInt(3) != 0) {
      return versions[random.nextInt(versions.size)]
    }
    return reduced(graph, List(1 + random.nextInt(3)) { random.nextInt(graph.size()) })
  }

  /**
   * The offset that extends the tail when [agent] owns it and [parents] names its last unit, else 0.
   */
  private fun continuingOffset(graph: EventGraphImpl, agent: Agent, parents: LvVersion): Int {
    val last = graph.size() - 1
    if (last < 0 || !parents.lvs.contentEquals(intArrayOf(last))) {
      return 0
    }
    val event = graph.runAt(last).event
    val op = event.op()
    return if (event.agent() == agent && op is DocumentOp.Insert) op.offset() + op.length() else 0
  }

  /**
   * The heads among [lvs]: every lv that is no ancestor of another one.
   */
  private fun reduced(graph: EventGraphImpl, lvs: List<Int>): LvVersion {
    val distinct = lvs.toSortedSet()
    val heads = distinct.filter { lv ->
      distinct.none { other -> other != lv && graph.eventsOf(LvVersion(intArrayOf(other))).get(lv) }
    }
    return LvVersion(heads.toIntArray())
  }

  private fun EventGraphImpl.appendAt(event: Event, parents: LvVersion): EventGraphImpl {
    val parentIds = VersionImpl.fromLvVersion(this, parents)
    return EventGraphImpl.implOf(append(event, parentIds))
  }

  private fun bitsOf(ranges: LvRanges): BitSet {
    val bits = BitSet()
    for (index in 0 until ranges.size()) {
      bits.set(ranges.start(index), ranges.end(index))
    }
    return bits
  }

  private fun without(a: BitSet, b: BitSet): BitSet {
    return (a.clone() as BitSet).apply { andNot(b) }
  }

  private fun joined(a: BitSet, b: BitSet): BitSet {
    return (a.clone() as BitSet).apply { or(b) }
  }

  private companion object {
    const val ROUNDS = 400
    const val PAIRS = 8
  }
}

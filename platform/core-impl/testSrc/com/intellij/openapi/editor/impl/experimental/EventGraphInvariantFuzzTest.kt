// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocBranch
import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph
import com.intellij.openapi.editor.experimental.Version
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * A fuzz that checks every graph invariant after every step, through [EventGraphImpl.checkInvariants].
 * The fuzz tests of the platform tests compare texts only, and they cannot reach that check.
 *
 * It has two shapes. In the first, replicas of one [DocBranch] edit and pull in a random order. In
 * the second, raw graphs append at random past versions. So a run can start inside another run,
 * name several parents, or be concurrent with an earlier run of its own agent.
 */
internal class EventGraphInvariantFuzzTest {

  @Test
  fun `branches that edit and pull keep every invariant`() {
    val random = Random(20260930)
    repeat(ROUNDS) { round ->
      val base = DocBranch.createBranch(randomText(random, 8), Agent.createAgent("base"))
      val replicas = (0 until 2 + random.nextInt(3)).mapTo(ArrayList()) { base.fork(Agent.createAgent("agent$it")) }
      val carets = IntArray(replicas.size)
      repeat(STEPS) { step ->
        val i = random.nextInt(replicas.size)
        if (random.nextInt(3) == 0) {
          replicas[i] = replicas[i].merge(replicas[random.nextInt(replicas.size)])
        } else {
          val op = randomOp(random, replicas[i].text().length(), carets[i])
          replicas[i] = replicas[i].applyOp(op)
          carets[i] = if (op is DocOp.Insert) op.offset() + op.length() else op.offset()
        }
        checkGraph(replicas[i].graph()) { "round $round, step $step, replica $i" }
        assertEquals(replicas[i].graph().replay().string(), replicas[i].text().string()) { "round $round, step $step, replica $i" }
      }
    }
  }

  @Test
  fun `raw appends at past versions keep every invariant`() {
    val random = Random(20261001)
    repeat(ROUNDS) { round ->
      val agents = (0 until 2 + random.nextInt(2)).map { Agent.createAgent("agent$it") }
      val graphs = agents.mapTo(ArrayList()) { EventGraph.createGraph() }
      // Every version each replica passed through. A replica only appends, so each one stays valid.
      val versions = agents.mapTo(ArrayList()) { arrayListOf(Version.root()) }
      val carets = IntArray(agents.size)
      repeat(STEPS) { step ->
        val i = random.nextInt(graphs.size)
        val graph = graphs[i]
        if (random.nextInt(4) == 0) {
          graphs[i] = graph.mergeFrom(graphs[random.nextInt(graphs.size)])
        } else {
          val atTip = random.nextBoolean()
          val parents = if (atTip) graph.version() else versions[i][random.nextInt(versions[i].size)]
          val text = graph.replay(parents).string()
          val caret = if (atTip) carets[i] else random.nextInt(text.length + 1)
          val seq = EventGraphImpl.implOf(graph).nextSeqFor(agents[i])
          val event = randomEvent(random, agents[i], seq, text, caret)
          graphs[i] = graph.append(event, parents)
          carets[i] = event.op().offset() + if (event.op() is DocOp.Insert) event.length() else 0
        }
        versions[i].add(graphs[i].version())
        checkGraph(graphs[i]) { "round $round, step $step, replica $i" }
      }
      // A full sync in two orders gives one text.
      val forward = graphs.fold(EventGraph.createGraph()) { all, graph -> all.mergeFrom(graph) }
      val backward = graphs.foldRight(EventGraph.createGraph()) { graph, all -> all.mergeFrom(graph) }
      checkGraph(forward) { "round $round, the forward sync" }
      checkGraph(backward) { "round $round, the backward sync" }
      assertEquals(forward.replay().string(), backward.replay().string()) { "round $round" }
    }
  }

  private fun checkGraph(graph: EventGraph, where: () -> String) {
    try {
      EventGraphImpl.implOf(graph).checkInvariants()
    } catch (e: IllegalArgumentException) {
      throw AssertionError("${where()}: ${e.message}", e)
    }
  }

  private fun randomOp(random: Random, length: Int, caret: Int): DocOp {
    val at = if (random.nextInt(4) == 0) random.nextInt(length + 1) else caret.coerceIn(0, length)
    if (length > 0 && random.nextInt(3) == 0) {
      val offset = at.coerceAtMost(length - 1)
      return DocOp.del(offset, 1 + random.nextInt(minOf(3, length - offset)))
    }
    return DocOp.ins(at, randomText(random, 1 + random.nextInt(3)))
  }

  /** An event that fits [text], the document at its parents. It edits at [caret] when it can. */
  private fun randomEvent(random: Random, agent: Agent, seq: Int, text: String, caret: Int): Event {
    val at = caret.coerceIn(0, text.length)
    if (text.isNotEmpty() && random.nextInt(3) == 0) {
      val offset = at.coerceAtMost(text.length - 1)
      return Event.createDelete(agent, seq, offset, 1 + random.nextInt(minOf(3, text.length - offset)))
    }
    return Event.createInsert(agent, seq, at, randomText(random, 1 + random.nextInt(3)))
  }

  private fun randomText(random: Random, length: Int): String {
    return String(CharArray(length) { ALPHABET[random.nextInt(ALPHABET.length)] })
  }

  private companion object {
    const val ROUNDS = 300
    const val STEPS = 100
    const val ALPHABET = "abcxyz \n"
  }
}

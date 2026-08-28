// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * A gossip fuzz: replicas edit and pull from each other in a random order, so the merge
 * base moves in every direction. [DocBranchFuzzTest] keeps a fork-then-sync shape with
 * small ops. This test adds the shapes that shape misses: many pulls in any direction,
 * whole-document deletes, big pastes, and a replica with no common history.
 *
 * Two invariants hold after every merge:
 * - the branch text equals a from-scratch replay of the merged graph;
 * - a full sync brings every replica to one text.
 */
class DocBranchGossipFuzzTest {

  @Test
  fun `gossiping replicas converge and match a replay`() {
    val random = Random(20260828)
    repeat(ROUNDS) { round ->
      val replicaCount = 2 + random.nextInt(4)
      val base = DocBranch.createBranch(randomText(random, 10), agent("base"))
      val replicas = ArrayList<DocBranch>()
      for (i in 0 until replicaCount) {
        replicas.add(base.fork(agent("agent$i")))
      }
      // One replica has no common history at all; it joins by a merge.
      if (random.nextInt(4) == 0) {
        replicas.add(DocBranch.createBranch(randomText(random, 6), agent("alien")))
      }

      // Per replica: the versions it passed through, with the text it held there. A
      // replica only ever appends to its own lv space, so its old versions stay valid.
      val history = ArrayList<ArrayList<Pair<Version, String>>>()
      for (replica in replicas) {
        history.add(arrayListOf(replica.graph().version() to replica.string()))
      }

      repeat(STEPS) { step ->
        val i = random.nextInt(replicas.size)
        if (random.nextInt(3) == 0) {
          val j = random.nextInt(replicas.size)
          if (i != j) {
            val merged = replicas[i].merge(replicas[j])
            assertEquals(merged.graph().replay().string(), merged.string()) {
              "round $round, step $step, merge $i <- $j"
            }
            replicas[i] = merged
          }
        } else {
          replicas[i] = replicas[i].applyOp(randomOp(random, replicas[i].length()))
        }
        history[i].add(replicas[i].graph().version() to replicas[i].string())
      }

      // A full sync: every replica pulls every other one, until nothing changes.
      repeat(2) {
        for (i in replicas.indices) {
          for (j in replicas.indices) {
            if (i != j) {
              replicas[i] = replicas[i].merge(replicas[j])
            }
          }
        }
      }
      val expected = replicas[0].string()
      for (i in replicas.indices) {
        assertEquals(expected, replicas[i].string()) { "round $round, replica $i after the sync" }
        assertEquals(replicas[i].graph().replay().string(), replicas[i].string()) { "round $round, replica $i replay" }
        // Every past version of this replica still replays to the text it held there,
        // out of the much larger merged graph.
        val graph = replicas[i].graph()
        for ((at, text) in history[i]) {
          assertEquals(text, graph.replay(at).string()) { "round $round, replica $i at $at" }
        }
      }
      assertSameText(DocText.createText(expected), replicas[0].text())
    }
  }

  private fun randomText(random: Random, bound: Int): String {
    val text = StringBuilder()
    repeat(random.nextInt(bound)) {
      text.append(ALPHABET[random.nextInt(ALPHABET.length)])
    }
    return text.toString()
  }

  private fun randomOp(random: Random, length: Int): DocOp {
    if (length == 0 || random.nextInt(10) < 6) {
      // A mix of single keystrokes and big pastes.
      val fragmentLength = if (random.nextInt(5) == 0) 1 + random.nextInt(12) else 1 + random.nextInt(2)
      val fragment = StringBuilder()
      repeat(fragmentLength) {
        fragment.append(ALPHABET[random.nextInt(ALPHABET.length)])
      }
      return insertOp(random.nextInt(length + 1), fragment.toString())
    }
    // A mix of small deletes and a wipe of the whole rest of the text.
    val offset = random.nextInt(length)
    val opLength = if (random.nextInt(6) == 0) length - offset else 1 + random.nextInt(minOf(4, length - offset))
    return deleteOp(offset, opLength)
  }

  companion object {
    private const val ROUNDS = 4000
    private const val STEPS = 30
    private const val ALPHABET = "abcde \n\r"
  }
}

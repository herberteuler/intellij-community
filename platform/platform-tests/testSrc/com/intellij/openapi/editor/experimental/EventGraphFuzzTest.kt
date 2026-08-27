// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class EventGraphFuzzTest {

  /**
   * Builds one random linear history three times: one run per logical op (`whole`),
   * the same units recut into random smaller runs (`pieces`), and a copy of `pieces`
   * frozen at a random earlier unit (`partial`). Every encoding must replay to the
   * [StringBuilder] model, and every merge across the encodings must change nothing
   * except catching `partial` up. A `partial` merge crosses run boundaries, so it
   * exercises the run-split path of `mergeFrom`.
   */
  @Test
  fun `run boundaries do not change the document`() {
    val random = Random(20260827)
    repeat(ROUNDS) { round ->
      val u = agent("u")
      var whole = EventGraph.createGraph()
      var pieces = EventGraph.createGraph()
      var partial: EventGraph? = null
      val stopAfterPieces = random.nextInt(12)
      var pieceCount = 0
      val text = StringBuilder()
      var seq = 0

      repeat(1 + random.nextInt(6)) {
        if (text.isEmpty() || random.nextBoolean()) {
          val pos = random.nextInt(text.length + 1)
          val content = randomString(random)
          whole = whole.append(Event.createInsert(u, seq, pos, content), whole.version())
          var offset = 0
          while (offset < content.length) {
            val piece = 1 + random.nextInt(content.length - offset)
            if (pieceCount == stopAfterPieces) {
              partial = pieces
            }
            pieces = pieces.append(
              Event.createInsert(u, seq + offset, pos + offset, content.substring(offset, offset + piece)),
              pieces.version(),
            )
            pieceCount++
            offset += piece
          }
          text.insert(pos, content)
          seq += content.length
        }
        else {
          val pos = random.nextInt(text.length)
          val length = 1 + random.nextInt(minOf(3, text.length - pos))
          whole = whole.append(Event.createDelete(u, seq, pos, length), whole.version())
          var offset = 0
          while (offset < length) {
            val piece = 1 + random.nextInt(length - offset)
            if (pieceCount == stopAfterPieces) {
              partial = pieces
            }
            pieces = pieces.append(Event.createDelete(u, seq + offset, pos, piece), pieces.version())
            pieceCount++
            offset += piece
          }
          text.delete(pos, pos + length)
          seq += length
        }
      }

      val expected = text.toString()
      assertEquals(expected, whole.replay().string()) { "round $round, one run per op" }
      assertEquals(expected, pieces.replay().string()) { "round $round, recut runs" }
      assertEquals(whole.size(), pieces.size()) { "round $round" }
      assertTrue(pieces.runCount() >= whole.runCount()) { "round $round" }

      // The same units with different run boundaries merge into the same document.
      assertEquals(expected, whole.mergeFrom(pieces).replay().string()) { "round $round, whole + pieces" }
      assertEquals(expected, pieces.mergeFrom(whole).replay().string()) { "round $round, pieces + whole" }

      // A partly caught-up replica catches up from either encoding.
      val caughtUp = partial ?: pieces
      assertEquals(expected, caughtUp.mergeFrom(whole).replay().string()) { "round $round, partial + whole" }
      assertEquals(expected, caughtUp.mergeFrom(pieces).replay().string()) { "round $round, partial + pieces" }
    }
  }

  private fun randomString(random: Random): String {
    val length = 1 + random.nextInt(4)
    val text = StringBuilder()
    repeat(length) {
      text.append(ALPHABET[random.nextInt(ALPHABET.length)])
    }
    return text.toString()
  }

  companion object {
    private const val ROUNDS = 5000
    private const val ALPHABET = "abcdef\n"
  }
}

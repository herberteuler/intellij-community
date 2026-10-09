// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

internal class ModifiedPositionsTest {

  @Test
  fun `a random history matches a plain array`() {
    repeat(SEEDS) { seed ->
      val random = Random(seed.toLong())
      var expected = BooleanArray(1 + random.nextInt(20))
      var actual = ModifiedPositions.clean(expected.size)
      repeat(STEPS) { step ->
        val from = random.nextInt(expected.size)
        val to = from + random.nextInt(expected.size - from)
        if (random.nextBoolean()) {
          val count = if (expected.size > MAX_SIZE) 1 else 1 + random.nextInt(4)
          actual = actual.splice(from, to, count)
          expected = spliced(expected, from, to, count)
        } else {
          val modified = random.nextBoolean()
          actual = actual.fill(from, to, modified)
          expected = filled(expected, from, to, modified)
        }
        assertMatches(expected, actual, "seed $seed, step $step")
      }
    }
  }

  @Test
  fun `an older value stays valid`() {
    val clean = ModifiedPositions.clean(5)
    val spliced = clean.splice(1, 2, 3)
    assertEquals(5, clean.size())
    assertFalse(clean.hasModified(0, 4))
    assertEquals(6, spliced.size())
    assertTrue(spliced.hasModified(1, 1))
  }

  @Test
  fun `a wrong size or range fails`() {
    val positions = ModifiedPositions.clean(3)
    assertThrows(IllegalArgumentException::class.java) { ModifiedPositions.clean(0) }
    assertThrows(IllegalArgumentException::class.java) { positions.splice(-1, 0, 1) }
    assertThrows(IllegalArgumentException::class.java) { positions.splice(2, 1, 1) }
    assertThrows(IllegalArgumentException::class.java) { positions.splice(0, 3, 1) }
    assertThrows(IllegalArgumentException::class.java) { positions.splice(0, 0, 0) }
  }

  private fun assertMatches(expected: BooleanArray, actual: ModifiedPositions, message: String) {
    assertEquals(expected.size, actual.size(), message)
    assertEquals(runCount(expected), actual.runCount(), message)
    for (from in expected.indices) {
      var hasModified = false
      for (to in from until expected.size) {
        hasModified = hasModified || expected[to]
        assertEquals(hasModified, actual.hasModified(from, to)) { "$message, $from..$to" }
      }
    }
  }

  private fun spliced(positions: BooleanArray, from: Int, to: Int, count: Int): BooleanArray {
    val head = positions.copyOfRange(0, from)
    val tail = positions.copyOfRange(to + 1, positions.size)
    return head + BooleanArray(count) { true } + tail
  }

  private fun filled(positions: BooleanArray, from: Int, to: Int, modified: Boolean): BooleanArray {
    val copy = positions.copyOf()
    copy.fill(modified, from, to + 1)
    return copy
  }

  private fun runCount(positions: BooleanArray): Int {
    var count = 1
    for (index in 1 until positions.size) {
      if (positions[index] != positions[index - 1]) {
        count++
      }
    }
    return count
  }

  private companion object {
    const val SEEDS = 300
    const val STEPS = 200
    const val MAX_SIZE = 40
  }
}

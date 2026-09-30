// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Tests [LvRanges]: the descending build, the join of touching ranges, and the order checks. */
internal class LvRangesTest {

  @Test
  fun `ranges added from the top down come out ascending`() {
    val builder = LvRanges.DescendingBuilder()
    builder.add(20, 25)
    builder.add(10, 12)
    builder.add(0, 1)
    val ranges = builder.build()
    assertEquals(listOf(0 to 1, 10 to 12, 20 to 25), pairsOf(ranges))
    assertEquals(8, ranges.unitCount())
    assertEquals("[0, 10..11, 20..24] (8 units)", ranges.toString())
  }

  @Test
  fun `a range that ends where the last one starts joins it`() {
    val builder = LvRanges.DescendingBuilder()
    builder.add(7, 9)
    builder.add(5, 7)
    builder.add(4, 5)
    assertEquals(listOf(4 to 9), pairsOf(builder.build()))
  }

  @Test
  fun `a builder grows past its first capacity`() {
    val builder = LvRanges.DescendingBuilder()
    for (index in RANGES - 1 downTo 0) {
      builder.add(3 * index, 3 * index + 2)
    }
    val ranges = builder.build()
    assertEquals(RANGES, ranges.size())
    for (index in 0 until RANGES) {
      assertEquals(3 * index, ranges.start(index))
      assertEquals(3 * index + 2, ranges.end(index))
    }
    assertTrue(ranges.toString().endsWith(", ... ($RANGES ranges)] (${2 * RANGES} units)"), ranges.toString())
  }

  @Test
  fun `an empty builder builds no range`() {
    val ranges = LvRanges.DescendingBuilder().build()
    assertTrue(ranges.isEmpty())
    assertEquals(0, ranges.unitCount())
  }

  @Test
  fun `a range out of order, empty, or negative is rejected`() {
    val builder = LvRanges.DescendingBuilder()
    builder.add(10, 12)
    // A walk must visit a parent before its child, so a range above the last one is a fault.
    assertThrows(IllegalArgumentException::class.java) { builder.add(12, 14) }
    assertThrows(IllegalArgumentException::class.java) { builder.add(9, 11) }
    assertThrows(IllegalArgumentException::class.java) { builder.add(5, 5) }
    assertThrows(IllegalArgumentException::class.java) { builder.add(-1, 3) }
    assertEquals(listOf(10 to 12), pairsOf(builder.build()))
  }

  private fun pairsOf(ranges: LvRanges): List<Pair<LV, LV>> {
    return (0 until ranges.size()).map { ranges.start(it) to ranges.end(it) }
  }

  private companion object {
    /** More ranges than the first capacity and than a message lists. */
    const val RANGES = 20
  }
}

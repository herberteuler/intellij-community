// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/**
 * A set of lvs as ranges `[start, end)`. The ranges ascend, and no two of them overlap or touch. A
 * walk that must visit every unit of a region gets the region this way. A paste of a million
 * characters then costs one entry and not a million.
 *
 * This is NOT a [Frontier]: it names every unit of a region, and not only the heads.
 *
 * [diff] and [findConflicting] build it. Both walks take the greatest lv first, so a
 * [DescendingBuilder] collects the ranges from the top down. It joins a range that ends where the
 * range before it starts.
 */
internal class LvRanges private constructor(
  private val starts: IntArray,
  private val ends: IntArray,
) {
  /** The number of ranges, which is not the number of units. */
  fun size(): Int {
    return starts.size
  }

  fun isEmpty(): Boolean {
    return starts.isEmpty()
  }

  /** The first lv of the range at [index]. */
  fun start(index: Int): LV {
    return starts[index]
  }

  /** The lv after the last one of the range at [index]. */
  fun end(index: Int): LV {
    return ends[index]
  }

  /** The number of units in all the ranges. */
  fun unitCount(): Int {
    var count = 0
    for (index in starts.indices) {
      count += ends[index] - starts[index]
    }
    return count
  }

  /**
   * The ranges as a list that is safe to put in a message. A range prints its first and its last
   * lv, like a span of an [Item]. A long list keeps its head and reports its own size.
   */
  override fun toString(): String {
    val shown = minOf(starts.size, MAX_LISTED_RANGES)
    val listed = (0 until shown).joinToString { index ->
      val last = ends[index] - 1
      if (starts[index] == last) "$last" else "${starts[index]}..$last"
    }
    val more = if (shown < starts.size) ", ... (${starts.size} ranges)" else ""
    return "[$listed$more] (${unitCount()} units)"
  }

  /**
   * Collects ranges in DESCENDING order, from the greatest lv down. A range that ends where the
   * range before it starts extends that range. [add] checks the order, because a walk over an
   * unordered set would visit a child before its parent.
   */
  class DescendingBuilder {
    private var starts = IntArray(INITIAL_CAPACITY)
    private var ends = IntArray(INITIAL_CAPACITY)
    private var count = 0

    /** Adds the units `[start, end)`, which must sit below every range added so far. */
    fun add(start: LV, end: LV) {
      checkRange(start, end)
      if (count > 0) {
        checkBelow(end)
        if (starts[count - 1] == end) {
          starts[count - 1] = start
          return
        }
      }
      if (count == starts.size) {
        starts = starts.copyOf(count * 2)
        ends = ends.copyOf(count * 2)
      }
      starts[count] = start
      ends[count] = end
      count++
    }

    /** The collected ranges, ascending. */
    fun build(): LvRanges {
      return LvRanges(IntArray(count) { starts[count - 1 - it] }, IntArray(count) { ends[count - 1 - it] })
    }

    private fun checkRange(start: LV, end: LV) {
      require(start in 0 until end) {
        "The range [$start, $end) is empty or holds a negative lv"
      }
    }

    private fun checkBelow(end: LV) {
      require(end <= starts[count - 1]) {
        "The range that ends at $end does not sit below the range that starts at ${starts[count - 1]}"
      }
    }

    override fun toString(): String {
      return "LvRanges.DescendingBuilder(ranges=$count)"
    }
  }

  private companion object {
    const val INITIAL_CAPACITY = 8

    /** The longest range list that a message prints in full. */
    const val MAX_LISTED_RANGES = 10
  }
}

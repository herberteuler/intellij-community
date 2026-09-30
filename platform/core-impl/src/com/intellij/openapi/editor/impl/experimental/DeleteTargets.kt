// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/**
 * For every delete unit that a walk applied, the unit it deleted. The reference implementation
 * calls this `delTargets`, and keeps one map entry per delete unit.
 *
 * This keeps one entry per PIECE instead. A piece is the part of a delete run that consumed one
 * item, so its delete units deleted consecutive units of that item. A 20,000-character delete of
 * one item is then one entry and not 20,000.
 *
 * A walk applies its units in ascending lv order, so the pieces arrive sorted, and a lookup is a
 * binary search over primitive arrays. [add] checks that order, because a lookup would silently
 * return a wrong unit without it.
 */
internal class DeleteTargets {
  private var deleteStarts = IntArray(INITIAL_CAPACITY)
  private var targetStarts = IntArray(INITIAL_CAPACITY)
  private var lengths = IntArray(INITIAL_CAPACITY)
  private var count = 0

  /**
   * Records that the [length] delete units from [deleteStart] deleted the units from
   * [targetStart]. A piece that continues the one before it in both spaces extends that one.
   */
  fun add(deleteStart: LV, targetStart: LV, length: Int) {
    checkLength(length)
    checkAscending(deleteStart)
    val last = count - 1
    if (last >= 0 && deleteStarts[last] + lengths[last] == deleteStart && targetStarts[last] + lengths[last] == targetStart) {
      lengths[last] += length
      return
    }
    if (count == deleteStarts.size) {
      val capacity = deleteStarts.size * 2
      deleteStarts = deleteStarts.copyOf(capacity)
      targetStarts = targetStarts.copyOf(capacity)
      lengths = lengths.copyOf(capacity)
    }
    deleteStarts[count] = deleteStart
    targetStarts[count] = targetStart
    lengths[count] = length
    count++
  }

  /** The unit that the delete unit [lv] deleted. The walk must have applied [lv]. */
  fun targetOf(lv: LV): LV {
    val index = pieceIndexOf(lv)
    return targetStarts[index] + (lv - deleteStarts[index])
  }

  /**
   * The first delete unit of the piece that holds [lv]. The units of a piece delete consecutive
   * units, so a walk can move them as one batch.
   */
  fun pieceStartOf(lv: LV): LV {
    return deleteStarts[pieceIndexOf(lv)]
  }

  /** The delete unit after the last one of the piece that holds [lv]. */
  fun pieceEndOf(lv: LV): LV {
    val index = pieceIndexOf(lv)
    return deleteStarts[index] + lengths[index]
  }

  /** The index of the piece that holds [lv], which the walk must have applied. */
  private fun pieceIndexOf(lv: LV): Int {
    var lo = 0
    var hi = count - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (deleteStarts[mid] <= lv) {
        lo = mid
      } else {
        hi = mid - 1
      }
    }
    checkApplied(lo, lv)
    return lo
  }

  /** The number of pieces. */
  fun size(): Int {
    return count
  }

  /** An empty piece would cover no unit, and it would still take part in the merge of the next. */
  private fun checkLength(length: Int) {
    require(length >= 1) {
      "The piece length is not positive: $length"
    }
  }

  private fun checkAscending(deleteStart: LV) {
    require(count == 0 || deleteStart >= deleteStarts[count - 1] + lengths[count - 1]) {
      "The delete unit $deleteStart arrived after the delete units up to ${deleteStarts[count - 1] + lengths[count - 1] - 1}"
    }
  }

  private fun checkApplied(index: Int, lv: LV) {
    require(count > 0 && lv >= deleteStarts[index] && lv < deleteStarts[index] + lengths[index]) {
      "The delete unit $lv was never applied in this walk"
    }
  }

  override fun toString(): String {
    return "DeleteTargets(pieces=$count)"
  }

  private companion object {
    const val INITIAL_CAPACITY = 16
  }
}

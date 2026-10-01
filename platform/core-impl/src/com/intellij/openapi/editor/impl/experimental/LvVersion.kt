// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import java.util.Arrays

/**
 * A version inside one graph value, as a sorted array of lvs. The reference implementation calls
 * an lv a local version (LV).
 *
 * An lv keeps its unit in this graph value and in every value that grows from it, by an append or
 * by a merge into it. Any other graph can give the unit another lv. So a version leaves the graph
 * only as a [VersionImpl], which names the same heads by event id.
 */
internal class LvVersion(val lvs: Frontier) {

  init {
    checkSorted(lvs)
    checkSpan(lvs)
  }

  /**
   * The size of the unit space this version can name: the greatest lv plus one, and 0 for the root.
   * A graph can hold this version only when its size is at least this value. The document at this
   * version is never longer than it. [checkSpan] keeps the sum inside an `Int`.
   */
  fun unitSpan(): Int {
    return if (lvs.isEmpty()) {
      0
    } else {
      lvs[lvs.size - 1] + 1
    }
  }

  /**
   * The version after an append of a run that ends at [newLastLv] and has [parents].
   *
   * The new unit replaces every head it descends from, and the other heads stay. Only a
   * head that [parents] names literally can be replaced: a head has no children, so no
   * head is a strict ancestor of any parent.
   */
  fun advancedBy(parents: LvVersion, newLastLv: LV): LvVersion {
    var kept = 0
    for (lv in lvs) {
      if (!parents.contains(lv)) {
        kept++
      }
    }
    val advanced = IntArray(kept + 1)
    var i = 0
    for (lv in lvs) {
      if (!parents.contains(lv)) {
        advanced[i] = lv
        i++
      }
    }
    // The new lv is greater than every existing lv, so the ascending order holds.
    advanced[i] = newLastLv
    return LvVersion(advanced)
  }

  /**
   * Whether this version names [lv] as one of its heads.
   */
  fun contains(lv: LV): Boolean {
    return Arrays.binarySearch(lvs, lv) >= 0
  }

  override fun equals(other: Any?): Boolean {
    return other is LvVersion && lvs.contentEquals(other.lvs)
  }

  override fun hashCode(): Int {
    return lvs.contentHashCode()
  }

  override fun toString(): String {
    return "v${lvs.listedForMessage()}"
  }

  private fun checkSorted(lvs: Frontier) {
    for (i in 1 until lvs.size) {
      require(lvs[i - 1] < lvs[i]) {
        "The version is not sorted or not distinct at the index $i: ${lvs.listedForMessage()}"
      }
    }
    require(lvs.isEmpty() || lvs[0] >= 0) {
      "Negative lv: ${lvs.listedForMessage()}"
    }
  }

  /**
   * Fails when the version names the lv [Int.MAX_VALUE]. A graph holds at most that many units,
   * so no graph has that lv, and [unitSpan] would overflow on it.
   */
  private fun checkSpan(lvs: Frontier) {
    require(lvs.isEmpty() || lvs[lvs.size - 1] < Int.MAX_VALUE) {
      "The lv ${Int.MAX_VALUE} names no unit of any graph"
    }
  }

  companion object {
    val ROOT: LvVersion = LvVersion(IntArray(0))
  }
}

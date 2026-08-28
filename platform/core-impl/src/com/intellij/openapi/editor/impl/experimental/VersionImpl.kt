// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Version
import java.util.Arrays

/**
 * A version as a sorted array of internal event indexes. The reference implementation
 * calls the index of an event a local version (LV).
 */
internal class VersionImpl(
  val lvs: Frontier,
) : Version {

  init {
    checkSorted(lvs)
  }

  override fun isRoot(): Boolean {
    return lvs.isEmpty()
  }

  /**
   * The size of the unit space this version can name: the greatest lv plus one, and 0 for
   * the root. A graph holds this version when its size is at least this value, and the
   * document at this version is never longer than it.
   */
  fun unitSpan(): Int {
    return if (lvs.isEmpty()) 0 else lvs[lvs.size - 1] + 1
  }

  /**
   * The version after an append of a run that ends at [newLastLv] and has [parents].
   *
   * The new unit replaces every head it descends from, and the other heads stay. Only a
   * head that [parents] names literally can be replaced: a head has no children, so no
   * head is a strict ancestor of any parent.
   */
  fun advancedBy(parents: VersionImpl, newLastLv: LV): VersionImpl {
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
    return VersionImpl(advanced)
  }

  /** Whether this version names [lv] as one of its heads. */
  fun contains(lv: LV): Boolean {
    return Arrays.binarySearch(lvs, lv) >= 0
  }

  /** The heads as a fresh array, which the caller is free to change in place. */
  fun toLvs(): Frontier {
    return lvs.copyOf()
  }

  override fun equals(other: Any?): Boolean {
    return other is VersionImpl && lvs.contentEquals(other.lvs)
  }

  override fun hashCode(): Int {
    return lvs.contentHashCode()
  }

  override fun toString(): String {
    return lvs.joinToString(prefix = "v[", postfix = "]")
  }

  private fun checkSorted(lvs: Frontier) {
    for (i in 1 until lvs.size) {
      require(lvs[i - 1] < lvs[i]) {
        "The version is not sorted or not distinct: ${lvs.contentToString()}"
      }
    }
    require(lvs.isEmpty() || lvs[0] >= 0) {
      "Negative lv: ${lvs.contentToString()}"
    }
  }

  companion object {
    val ROOT: VersionImpl = VersionImpl(IntArray(0))

    fun implOf(version: Version): VersionImpl {
      require(version is VersionImpl) {
        "Foreign Version implementation: ${version.javaClass.name}"
      }
      return version
    }
  }
}

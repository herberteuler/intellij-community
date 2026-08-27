// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Version

/**
 * A version as a sorted array of internal event indexes. The reference implementation
 * calls the index of an event a local version (LV).
 */
internal class VersionImpl(
  val lvs: IntArray,
) : Version {

  init {
    checkSorted(lvs)
  }

  override fun isRoot(): Boolean {
    return lvs.isEmpty()
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

  private fun checkSorted(lvs: IntArray) {
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

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/** What [diff] returns: the lvs only in the history of one version, for each side, as ranges. */
internal class VersionDiff(val aOnly: LvRanges, val bOnly: LvRanges) {
  /** Whether the two versions name the same event set, so no item changes state. */
  fun isEmpty(): Boolean {
    return aOnly.isEmpty() && bOnly.isEmpty()
  }

  override fun toString(): String {
    return "VersionDiff(aOnly=$aOnly, bOnly=$bOnly)"
  }
}

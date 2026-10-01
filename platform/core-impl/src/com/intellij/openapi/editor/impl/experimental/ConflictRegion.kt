// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/**
 * What [findConflicting] returns: the common ancestor, and the region above it, split by which side
 * holds it.
 *
 * [commonAncestor] is a version, while [conflictRanges] and [newRanges] name every unit to walk. A
 * real type marks the difference here, because the two are used side by side and a swap would
 * replay the wrong thing.
 */
internal class ConflictRegion(
  val commonAncestor: LvVersion,
  val conflictRanges: LvRanges,
  val newRanges: LvRanges,
) {
  override fun toString(): String {
    return "ConflictRegion(ancestor=$commonAncestor, conflict=$conflictRanges, new=$newRanges)"
  }
}

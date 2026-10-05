// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vcs.changes

import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.history.ShortVcsRevisionNumber
import org.jetbrains.annotations.ApiStatus

/**
 * Describes a file change between two revisions.
 */
@ApiStatus.Experimental
data class RefComparisonChange(
  val revisionNumberBefore: ShortVcsRevisionNumber,
  val filePathBefore: FilePath?,
  val revisionNumberAfter: ShortVcsRevisionNumber,
  val filePathAfter: FilePath?,
) {
  companion object {
    val KEY: Key<RefComparisonChange> = Key.create("RefComparisonChange")
  }
}

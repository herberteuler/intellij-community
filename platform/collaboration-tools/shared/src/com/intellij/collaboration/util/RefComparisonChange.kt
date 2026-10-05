// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.collaboration.util

import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus

typealias RefComparisonChange = com.intellij.openapi.vcs.changes.RefComparisonChange

val RefComparisonChange.fileStatus: FileStatus
  get() = when {
    filePathBefore == null -> FileStatus.ADDED
    filePathAfter == null -> FileStatus.DELETED
    else -> FileStatus.MODIFIED
  }

val RefComparisonChange.filePath: FilePath
  get() = (filePathAfter ?: filePathBefore)!!

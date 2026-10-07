// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves directory membership without accessing Swing. */
internal open class DirectoryGroupResolver(private val project: Project?) {
  open suspend fun resolve(files: List<VirtualFile>): Map<VirtualFile, TabGroup?> = withContext(Dispatchers.Default) {
    val project = project ?: return@withContext emptyMap()
    readAction {
      files.associateWith { file ->
        if (file.isValid && !project.isDisposed) EditorTabGroupingProvider.resolveDirectoryGroup(file, project) else null
      }
    }
  }
}

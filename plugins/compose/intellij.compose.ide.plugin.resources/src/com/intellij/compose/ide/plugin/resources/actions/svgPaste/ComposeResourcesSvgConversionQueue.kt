// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.compose.ide.plugin.resources.actions.svgPaste

import com.intellij.compose.ide.plugin.resources.ComposeResourcesDataProvider
import com.intellij.compose.ide.plugin.resources.ResourceType
import com.intellij.compose.ide.plugin.resources.isValidInnerComposeResourcesDirNameFor
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.ui.update.DebouncedUpdates
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.milliseconds

@Service(Level.PROJECT)
internal class ComposeResourcesSvgConversionQueue(private val project: Project, scope: CoroutineScope) {

  private val queue = DebouncedUpdates.forScope<VirtualFile>(scope, "compose-resources-svg-conversion", BATCH_DELAY)
    .runBatchedDistinct { files -> processFiles(files.filter { it.isValid }) }

  fun queueFiles(files: List<VirtualFile>) {
    if (project.isDisposed) return
    files.forEach(queue::queue)
  }

  private suspend fun processFiles(files: List<VirtualFile>) {
    if (files.isEmpty()) return
    val composeDataProvider = ComposeResourcesDataProvider.findProviderForProject(project) ?: return
    val svgsInDrawableFolders = readAction { filterFilesInDrawableFolders(files, composeDataProvider) }
    if (svgsInDrawableFolders.isEmpty()) return
    showBatchConversionDialog(project, svgsInDrawableFolders)
  }

  private fun filterFilesInDrawableFolders(files: List<VirtualFile>, provider: ComposeResourcesDataProvider): List<VirtualFile> {
    if (project.isDisposed) return emptyList()
    val psiManager = PsiManager.getInstance(project)
    val drawableFolders = files.mapNotNullTo(mutableSetOf()) { it.parent }
      .filterTo(mutableSetOf()) { dir ->
        if (dir.name != ResourceType.DRAWABLE.dirName) return@filterTo false
        val psiFolder = psiManager.findDirectory(dir)
        psiFolder != null && provider.getComposeDataForResourceFolder(psiFolder) != null
      }
    return files.filter { it.parent in drawableFolders }
  }

  companion object {
    private val BATCH_DELAY = 500.milliseconds
  }
}
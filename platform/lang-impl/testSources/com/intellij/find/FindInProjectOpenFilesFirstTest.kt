// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find

import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IndexingTestUtil.Companion.waitUntilIndexesAreReady
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.assertions.Assertions.assertThat
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.usageView.UsageInfo
import com.intellij.util.CommonProcessors.CollectProcessor
import com.intellij.util.Processor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Collections.synchronizedList

/**
 * Pins the exclusion policy of the open files that Find-in-Files checks first (see `FindInProjectTask`).
 * A directory search skips an excluded subdirectory. An open file under it must stay skipped too.
 * The previous-search files (`filesToScanInitially`) pass the same scope and exclusion filter.
 */
@TestApplication
class FindInProjectOpenFilesFirstTest {
  @RegisterExtension
  private val projectModel: ProjectModelExtension = ProjectModelExtension()
  private val baseDir get() = projectModel.baseProjectDir
  private val project get() = projectModel.project

  private lateinit var root: VirtualFile
  private lateinit var keptFile: VirtualFile
  private lateinit var hiddenFile: VirtualFile
  private lateinit var outsideFile: VirtualFile

  @BeforeEach
  fun setup(): Unit = runBlocking {
    val module = projectModel.createModule()
    root = baseDir.newVirtualDirectory("root")
    PsiTestUtil.addContentRoot(module, root)
    val excluded = baseDir.newVirtualDirectory("root/excluded")
    PsiTestUtil.addExcludedRoot(module, excluded)
    keptFile = baseDir.newVirtualFile("root/kept.txt", "needle zq data".toByteArray())
    hiddenFile = baseDir.newVirtualFile("root/excluded/hidden.txt", "needle zq data".toByteArray())
    outsideFile = baseDir.newVirtualFile("outside/other.txt", "needle zq data".toByteArray())
    VfsTestUtil.syncRefresh()
    waitUntilIndexesAreReady(project)
  }

  @AfterEach
  fun closeOpenFiles(): Unit = runBlocking {
    withContext(Dispatchers.EDT) {
      val editorManager = FileEditorManager.getInstance(project)
      editorManager.openFiles.forEach { editorManager.closeFile(it) }
    }
  }

  @Test
  fun `an open file under an excluded directory is not reported in a directory search`(): Unit = runBlocking {
    open(hiddenFile)

    assertThat(findFileNames()).containsExactly("kept.txt")
  }

  @Test
  fun `an open file in the directory is reported`(): Unit = runBlocking {
    open(keptFile)

    assertThat(findFileNames()).containsExactly("kept.txt")
  }

  @Test
  fun `a previous-search file outside the directory is not reported`(): Unit = runBlocking {
    assertThat(findFileNames(filesToScanInitially = setOf(outsideFile))).containsExactly("kept.txt")
  }

  @Test
  fun `a previous-search file under an excluded directory is not reported`(): Unit = runBlocking {
    assertThat(findFileNames(filesToScanInitially = setOf(hiddenFile))).containsExactly("kept.txt")
  }

  private suspend fun open(file: VirtualFile) {
    withContext(Dispatchers.EDT) {
      FileEditorManager.getInstance(project).openFile(file, true)
    }
    assertThat(FileEditorManager.getInstance(project).openFiles).contains(file)
  }

  private fun createFindModel(): FindModel {
    return FindModel().apply {
      stringToFind = "needle"
      stringToReplace = ""
      isReplaceState = false
      isWholeWordsOnly = false
      searchContext = FindModel.SearchContext.ANY
      isFromCursor = false
      isForward = true
      isGlobal = true
      isRegularExpressions = false
      regExpFlags = 0
      isCaseSensitive = false
      isMultipleFiles = true
      isPromptOnReplace = true
      isReplaceAll = false
      isProjectScope = false
      directoryName = root.path
      isWithSubdirectories = true
      isSearchInProjectFiles = false
      fileFilter = null
      moduleName = null
      customScopeName = null
    }
  }

  private fun findFileNames(filesToScanInitially: Set<VirtualFile> = emptySet()): List<String> {
    val model = createFindModel()
    val usages = synchronizedList<UsageInfo?>(ArrayList())
    val consumer: Processor<UsageInfo?> = CollectProcessor(usages)
    val presentation = FindInProjectUtil.setupProcessPresentation(false, FindInProjectUtil.setupViewPresentation(false, model))
    FindInProjectUtil.findUsages(model, project, presentation, filesToScanInitially, consumer)
    return usages.map { it!!.virtualFile!!.name }.distinct()
  }
}

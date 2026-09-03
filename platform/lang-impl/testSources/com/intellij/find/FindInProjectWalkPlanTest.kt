// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find

import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.CacheAvoidingVirtualFile
import com.intellij.platform.backend.workspace.toVirtualFileUrl
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil.Companion.waitUntilIndexesAreReady
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.assertions.Assertions.assertThat
import com.intellij.testFramework.junit5.RegistryKey
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.usageView.UsageInfo
import com.intellij.util.CommonProcessors.CollectProcessor
import com.intellij.util.Processor
import com.intellij.util.indexing.testEntities.NonIndexableKindFileSetTestContributor
import com.intellij.util.indexing.testEntities.NonIndexableTestEntity
import com.intellij.workspaceModel.core.fileIndex.impl.WorkspaceFileIndexImpl
import com.intellij.workspaceModel.ide.NonPersistentEntitySource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Collections.synchronizedList

/**
 * Pins the walk plan of `ScopeWalker.roots` with the real search engines: which roots a custom scope walks and how.
 * The files under `non-indexable/` are never indexed, so only the walk can find them.
 */
@TestApplication
@RegistryKey("find.in.files.in.non.indexable.enable", "true")
class FindInProjectWalkPlanTest {
  @RegisterExtension
  private val projectModel: ProjectModelExtension = ProjectModelExtension()
  private val baseDir get() = projectModel.baseProjectDir
  private val project get() = projectModel.project

  @TestDisposable
  private lateinit var disposable: Disposable

  private lateinit var nonIndexableFile: VirtualFile

  @BeforeEach
  fun setup(): Unit = runBlocking {
    WorkspaceFileIndexImpl.EP_NAME.point.registerExtension(NonIndexableKindFileSetTestContributor(), disposable)

    val root = baseDir.newVirtualDirectory("non-indexable")
    nonIndexableFile = baseDir.newVirtualFile("non-indexable/walked.txt", "walked file with data".toByteArray())

    val urlManager = project.workspaceModel.getVirtualFileUrlManager()
    project.workspaceModel.update("add non-indexable root") { storage ->
      storage.addEntity(NonIndexableTestEntity(root.toVirtualFileUrl(urlManager), NonPersistentEntitySource))
    }
    VfsTestUtil.syncRefresh()
    waitUntilIndexesAreReady(project)
  }

  /**
   * `zq` has no trigrams, and the library file holds it only inside a word, so neither the trigram nor the word index
   * finds it: only the walk of the library iterator does, and that walk runs only when the plan searches libraries.
   */
  @Test
  fun `a library root is walked only when the custom scope searches libraries`(): Unit = runBlocking {
    val libraryRoot = baseDir.newVirtualDirectory("lib")
    baseDir.newVirtualFile("lib/lib.txt", "library xzqx text".toByteArray())
    VfsTestUtil.syncRefresh()
    // the module has no content root, so the library root is not under module content
    val module = projectModel.createModule()
    projectModel.addModuleLevelLibrary(module, "lib") { it.addRoot(libraryRoot, OrderRootType.CLASSES) }

    assertThat(findUsages(createFindModel("zq")).map { it.virtualFile!!.name }).doesNotContain("lib.txt")

    val allScopeModel = createFindModel("zq").apply {
      isProjectScope = false
      isCustomScope = true
      customScope = GlobalSearchScope.allScope(project)
    }
    assertThat(findUsages(allScopeModel).map { it.virtualFile!!.name }).contains("lib.txt")
  }

  @Test
  fun `a files scope is walked through cache-avoiding files`(): Unit = runBlocking {
    val model = createFindModel("data").apply {
      isProjectScope = false
      isCustomScope = true
      customScope = GlobalSearchScope.filesScope(project, listOf(nonIndexableFile))
    }

    val usages = findUsages(model)

    assertThat(usages.map { it.virtualFile!!.name }).containsExactly("walked.txt")
    assertThat(usages.single().virtualFile).isInstanceOf(CacheAvoidingVirtualFile::class.java)
  }

  private fun createFindModel(pattern: String): FindModel {
    return FindModel().apply {
      stringToFind = pattern
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
      isProjectScope = true
      directoryName = null
      isWithSubdirectories = true
      isSearchInProjectFiles = false
      fileFilter = null
      moduleName = null
      customScopeName = null
    }
  }

  private fun findUsages(model: FindModel): List<UsageInfo> {
    val usages = synchronizedList(ArrayList<UsageInfo>())
    val consumer: Processor<UsageInfo> = CollectProcessor(usages)
    val presentation = FindInProjectUtil.setupProcessPresentation(false, FindInProjectUtil.setupViewPresentation(false, model))
    FindInProjectUtil.findUsages(model, project, consumer, presentation)
    return usages.toList()
  }
}

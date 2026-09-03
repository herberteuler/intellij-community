// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find

import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.testFramework.IndexingTestUtil.Companion.waitUntilIndexesAreReady
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.assertions.Assertions.assertThat
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.usageView.UsageInfo
import com.intellij.util.CommonProcessors.CollectProcessor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Collections.synchronizedList
import java.util.concurrent.CancellationException

/**
 * Pins that the "Find in Path" scan reads the text of a file, not its AST (see `FileScanner`), and that the usage consumer
 * is free to load any AST.
 * With the AST loading filter on, a tree load during the scan logs an error, which fails the test.
 */
@TestApplication
class FindInProjectAstLoadingTest {
  @RegisterExtension
  private val projectModel: ProjectModelExtension = ProjectModelExtension()

  @TestDisposable
  private lateinit var disposable: Disposable

  @Test
  fun `the scan finds usages without loading the AST of the file`(): Unit = runBlocking {
    Registry.get("ast.loading.filter").setValue(true, disposable)
    val module = projectModel.createModule()
    val root = projectModel.baseProjectDir.newVirtualDirectory("root")
    PsiTestUtil.addContentRoot(module, root)
    val file = projectModel.baseProjectDir.newVirtualFile("root/a.txt", "one needle\ntwo needle\n".toByteArray())
    VfsTestUtil.syncRefresh()
    waitUntilIndexesAreReady(projectModel.project)

    val usages = synchronizedList<UsageInfo?>(ArrayList())
    val model = FindModel().apply {
      stringToFind = "needle"
      isMultipleFiles = true
      isProjectScope = false
      directoryName = root.path
      isWithSubdirectories = true
    }
    val presentation = FindInProjectUtil.setupProcessPresentation(false, FindInProjectUtil.setupViewPresentation(false, model))
    FindInProjectUtil.findUsages(model, projectModel.project, presentation, emptySet(), CollectProcessor(usages))

    assertThat(usages).hasSize(2)
    val psiFile = readAction { PsiManager.getInstance(projectModel.project).findFile(file) } as PsiFileImpl
    assertThat(psiFile.treeElement).describedAs("the AST of the scanned file").isNull()
  }

  @Test
  fun `a usage consumer loads the AST of the usage file and of another file`(): Unit = runBlocking {
    Registry.get("ast.loading.filter").setValue(true, disposable)
    val module = projectModel.createModule()
    val root = projectModel.baseProjectDir.newVirtualDirectory("root")
    PsiTestUtil.addContentRoot(module, root)
    val file = projectModel.baseProjectDir.newVirtualFile("root/a.txt", "one needle\ntwo needle\n".toByteArray())
    val other = projectModel.baseProjectDir.newVirtualFile("root/b.txt", "no match here\n".toByteArray())
    VfsTestUtil.syncRefresh()
    waitUntilIndexesAreReady(projectModel.project)

    val psiManager = PsiManager.getInstance(projectModel.project)
    val usages = synchronizedList<UsageInfo?>(ArrayList())
    val failures = synchronizedList<Throwable>(ArrayList())
    val model = FindModel().apply {
      stringToFind = "needle"
      isMultipleFiles = true
      isProjectScope = false
      directoryName = root.path
      isWithSubdirectories = true
    }
    val presentation = FindInProjectUtil.setupProcessPresentation(false, FindInProjectUtil.setupViewPresentation(false, model))
    //each load on its own: a fix that re-allows the usage file only must fail on the other file
    fun recordFailure(load: () -> Unit) {
      try {
        load()
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Throwable) {
        failures.add(e)
      }
    }
    FindInProjectUtil.findUsages(model, projectModel.project, presentation, emptySet()) { usage ->
      //a reference search resolves the usage in the tree of its file (findElementAt) and may resolve into another file:
      recordFailure { usage.element!!.findElementAt(usage.navigationOffset) }
      recordFailure { psiManager.findFile(other)!!.node }
      usages.add(usage)
    }

    assertThat(failures).describedAs("errors of the AST loads in the usage consumer").isEmpty()
    assertThat(usages).hasSize(2)
    val psiFile = readAction { psiManager.findFile(file) } as PsiFileImpl
    assertThat(psiFile.treeElement).describedAs("the AST of the usage file").isNotNull()
    val otherPsiFile = readAction { psiManager.findFile(other) } as PsiFileImpl
    assertThat(otherPsiFile.treeElement).describedAs("the AST of the other file").isNotNull()
  }
}

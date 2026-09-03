// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find

import com.intellij.find.FindInProjectSearchEngine.Coverage
import com.intellij.find.FindInProjectSearchEngine.FindInProjectSearcher
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.toVirtualFileUrl
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.testFramework.IndexingTestUtil.Companion.waitUntilIndexesAreReady
import com.intellij.testFramework.VfsTestUtil
import com.intellij.testFramework.assertions.Assertions.assertThat
import com.intellij.testFramework.junit5.RegistryKey
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.usageView.UsageInfo
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pins the streaming searcher contract of `FindInProjectTask`
 * (see [FindInProjectSearcher.processOccurrences] and [FindInProjectSearcher.isStreaming]).
 * The files live in a non-indexable root, so the brute-force phase walks them unless a searcher returns [Coverage.ALL_CANDIDATES].
 */
@TestApplication
@RegistryKey("find.in.files.in.non.indexable.enable", "true")
class FindInProjectStreamingSearcherTest {
  @RegisterExtension
  private val projectModel: ProjectModelExtension = ProjectModelExtension()
  private val baseDir get() = projectModel.baseProjectDir
  private val project get() = projectModel.project

  @TestDisposable
  private lateinit var disposable: Disposable

  private lateinit var streamedFile: VirtualFile
  private lateinit var walkedFile: VirtualFile

  @BeforeEach
  fun setup(): Unit = runBlocking {
    WorkspaceFileIndexImpl.EP_NAME.point.registerExtension(NonIndexableKindFileSetTestContributor(), disposable)

    val root = baseDir.newVirtualDirectory("non-indexable")
    streamedFile = baseDir.newVirtualFile("non-indexable/streamed.txt", "streamed file with data".toByteArray())
    walkedFile = baseDir.newVirtualFile("non-indexable/walked.txt", "walked file with data".toByteArray())

    val urlManager = project.workspaceModel.getVirtualFileUrlManager()
    project.workspaceModel.update("add non-indexable root") { storage ->
      storage.addEntity(NonIndexableTestEntity(root.toVirtualFileUrl(urlManager), NonPersistentEntitySource))
    }
    VfsTestUtil.syncRefresh()
    waitUntilIndexesAreReady(project)
  }

  @Test
  fun `a streamed file reaches the usage processor before the searcher returns`() {
    val firstUsage = CountDownLatch(1)
    var usageBeforeReturn = false
    registerSearcher { processor ->
      processor.process(streamedFile)
      usageBeforeReturn = firstUsage.await(30, TimeUnit.SECONDS)
      true
    }

    val usages = synchronizedList(ArrayList<UsageInfo>())
    findUsages("data") { usage ->
      usages.add(usage)
      if (usage.virtualFile == streamedFile) firstUsage.countDown()
      true
    }

    assertThat(usageBeforeReturn).isTrue()
    assertThat(usages.map { it.virtualFile!!.name }).containsExactlyInAnyOrder("streamed.txt", "walked.txt")
  }

  @Test
  fun `a stopped usage processor makes the searcher's processor return false`() {
    var processorResult = true
    registerSearcher { processor ->
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
      while (processorResult && System.nanoTime() < deadline) {
        processorResult = processor.process(streamedFile)
        Thread.sleep(1)
      }
      processorResult
    }

    findUsages("data") { false }

    assertThat(processorResult).isFalse()
  }

  @Test
  fun `the brute-force phase does not search a streamed file again`() {
    registerSearcher { processor -> processor.process(streamedFile) && processor.process(streamedFile) }

    val usages = synchronizedList(ArrayList<UsageInfo>())
    findUsages("data") { usages.add(it) }

    assertThat(usages.map { it.virtualFile!!.name }).containsExactlyInAnyOrder("streamed.txt", "walked.txt")
  }

  @Test
  fun `a streaming searcher that returns ALL_CANDIDATES skips the non-indexable walk`() {
    registerSearcher(coverage = Coverage.ALL_CANDIDATES) { processor -> processor.process(streamedFile) }

    val usages = synchronizedList(ArrayList<UsageInfo>())
    findUsages("data") { usages.add(it) }

    assertThat(usages.map { it.virtualFile!!.name }).containsExactly("streamed.txt")
  }

  @Test
  fun `a streaming searcher keeps the file mask`() {
    val maskedFile = baseDir.newVirtualFile("non-indexable/masked.md", "masked file with data".toByteArray())
    registerSearcher(coverage = Coverage.ALL_CANDIDATES) { processor -> processor.process(maskedFile) && processor.process(streamedFile) }

    val usages = synchronizedList(ArrayList<UsageInfo>())
    findUsages("data", fileMask = "*.txt") { usages.add(it) }

    assertThat(usages.map { it.virtualFile!!.name }).containsExactly("streamed.txt")
  }

  @Test
  fun `the default processOccurrences feeds searchForOccurrences and stops with the processor`() {
    val searcher = object : FindInProjectSearcher {
      override fun searchForOccurrences(): Collection<VirtualFile> = listOf(streamedFile, walkedFile)
      override fun isReliable(): Boolean = true
      override fun isCovered(file: VirtualFile): Boolean = false
    }
    assertThat(searcher.isStreaming).isFalse()

    val all = ArrayList<VirtualFile>()
    assertThat(searcher.processOccurrences { all.add(it) }).isEqualTo(Coverage.INDEXED_ONLY)
    assertThat(all).containsExactly(streamedFile, walkedFile)

    val first = ArrayList<VirtualFile>()
    assertThat(searcher.processOccurrences { first.add(it); false }).isEqualTo(Coverage.STOPPED)
    assertThat(first).containsExactly(streamedFile)
  }

  /**
   * Registers a reliable streaming searcher that covers no file.
   * Its [FindInProjectSearcher.processOccurrences] returns [coverage] unless [stream] returned false.
   */
  private fun registerSearcher(coverage: Coverage = Coverage.INDEXED_ONLY, stream: (Processor<in VirtualFile>) -> Boolean) {
    val engine = object : FindInProjectSearchEngine {
      override fun createSearcher(findModel: FindModel, project: Project): FindInProjectSearcher {
        return object : FindInProjectSearcher {
          override fun searchForOccurrences(): Collection<VirtualFile> = throw AssertionError("a streaming searcher is not asked to collect")
          override fun processOccurrences(processor: Processor<in VirtualFile>): Coverage =
            if (stream(processor)) coverage else Coverage.STOPPED
          override fun isStreaming(): Boolean = true
          override fun isReliable(): Boolean = true
          override fun isCovered(file: VirtualFile): Boolean = false
        }
      }
    }
    FindInProjectSearchEngine.EP_NAME.point.registerExtension(engine, disposable)
  }

  private fun findUsages(pattern: String, fileMask: String? = null, usageProcessor: Processor<UsageInfo>) {
    val model = FindModel().apply {
      stringToFind = pattern
      isCaseSensitive = false
      isMultipleFiles = true
      isProjectScope = true
      isWithSubdirectories = true
      fileFilter = fileMask
    }
    val presentation = FindInProjectUtil.setupProcessPresentation(false, FindInProjectUtil.setupViewPresentation(false, model))
    FindInProjectUtil.findUsages(model, project, usageProcessor, presentation)
  }
}

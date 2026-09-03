// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find

import com.intellij.find.FindInProjectSearchEngine.Coverage
import com.intellij.find.FindInProjectSearchEngine.FindInProjectSearcher
import com.intellij.find.FindInProjectSearchEngine.ScanObserver
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.TrigramBuilder
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the Find-in-Files skip gate for non-indexable files (see `FindInProjectTask`).
 * A reliable searcher skips a non-indexable file that it covers (see [FindInProjectSearcher.isCovered]);
 * the brute-force phase scans every other non-indexable file.
 */
@TestApplication
@RegistryKey("find.in.files.in.non.indexable.enable", "true")
class FindInProjectNonIndexableSkipTest {
  @RegisterExtension
  private val projectModel: ProjectModelExtension = ProjectModelExtension()
  private val baseDir get() = projectModel.baseProjectDir
  private val project get() = projectModel.project

  @TestDisposable
  private lateinit var disposable: Disposable

  private lateinit var coveredFile: VirtualFile
  private lateinit var uncoveredFile: VirtualFile

  @BeforeEach
  fun setup(): Unit = runBlocking {
    WorkspaceFileIndexImpl.EP_NAME.point.registerExtension(NonIndexableKindFileSetTestContributor(), disposable)

    val root = baseDir.newVirtualDirectory("non-indexable")
    coveredFile = baseDir.newVirtualFile("non-indexable/covered.txt", "covered file with zq data".toByteArray())
    uncoveredFile = baseDir.newVirtualFile("non-indexable/uncovered.txt", "uncovered file with zq data".toByteArray())

    val urlManager = project.workspaceModel.getVirtualFileUrlManager()
    project.workspaceModel.update("add non-indexable root") { storage ->
      storage.addEntity(NonIndexableTestEntity(root.toVirtualFileUrl(urlManager), NonPersistentEntitySource))
    }
    VfsTestUtil.syncRefresh()
    waitUntilIndexesAreReady(project)
  }

  @Test
  fun `reliable searcher skips covered files and brute-forces uncovered ones`(): Unit = runBlocking {
    registerSearcher(reliable = true, coveredNames = setOf("covered.txt"))

    assertThat(findFileNames("data")).containsExactlyInAnyOrder("uncovered.txt")
  }

  @Test
  fun `reliable searcher that covers no non-indexable file skips nothing`(): Unit = runBlocking {
    registerSearcher(reliable = true, coveredNames = emptySet())

    assertThat(findFileNames("data")).containsExactlyInAnyOrder("covered.txt", "uncovered.txt")
  }

  @Test
  fun `unreliable searcher skips nothing`(): Unit = runBlocking {
    registerSearcher(reliable = false, coveredNames = setOf("covered.txt", "uncovered.txt"))

    assertThat(findFileNames("data")).containsExactlyInAnyOrder("covered.txt", "uncovered.txt")
  }

  @Test
  fun `trigram-less pattern is found by the brute-force phase`(): Unit = runBlocking {
    registerSearcher(reliable = true, coveredNames = setOf("covered.txt", "uncovered.txt"))

    assertThat(findFileNames("zq")).containsExactlyInAnyOrder("covered.txt", "uncovered.txt")
  }

  @Test
  fun `fileScanned reports the disk text of brute-forced files and skips covered files`(): Unit = runBlocking {
    val recorder = HookRecorder()
    registerSearcher(reliable = true, coveredNames = setOf("covered.txt"), recorder = recorder)

    findFileNames("data")

    assertThat(recorder.scannedNames()).contains("uncovered.txt")
    assertThat(recorder.scannedNames()).doesNotContain("covered.txt")
    assertThat(recorder.scannedTexts("uncovered.txt")).containsExactly("uncovered file with zq data")
  }

  @Test
  fun `fileScanned does not fire for a file with a cached document`(): Unit = runBlocking {
    val recorder = HookRecorder()
    registerSearcher(reliable = true, coveredNames = emptySet(), recorder = recorder)
    val document = readAction { FileDocumentManager.getInstance().getDocument(coveredFile)!! }

    assertThat(findFileNames("data")).containsExactlyInAnyOrder("covered.txt", "uncovered.txt")

    assertThat(recorder.scannedNames()).contains("uncovered.txt")
    assertThat(recorder.scannedNames()).doesNotContain("covered.txt")
    assertThat(document.charsSequence.toString()).isEqualTo("covered file with zq data")
  }

  @Test
  fun `fileScanned does not fire for masked or binary files`(): Unit = runBlocking {
    baseDir.newVirtualFile("non-indexable/masked.md", "masked file with zq data".toByteArray())
    baseDir.newVirtualFile("non-indexable/binary.bin", "binary zq data".toByteArray() + byteArrayOf(0, 1, 2, 3))
    val recorder = HookRecorder()
    registerSearcher(reliable = true, coveredNames = emptySet(), recorder = recorder)

    assertThat(findFileNames("data", fileMask = "*.txt,*.bin")).containsExactlyInAnyOrder("covered.txt", "uncovered.txt")

    assertThat(recorder.scannedNames()).contains("covered.txt", "uncovered.txt")
    assertThat(recorder.scannedNames()).doesNotContain("masked.md", "binary.bin")
  }

  @Test
  fun `a searcher that returns ALL_CANDIDATES skips the non-indexable walk`(): Unit = runBlocking {
    val recorder = HookRecorder()
    registerSearcher(reliable = true, coveredNames = emptySet(),
                     recorder = recorder, coverage = Coverage.ALL_CANDIDATES, occurrences = listOf(coveredFile))

    assertThat(findFileNames("data")).containsExactlyInAnyOrder("covered.txt")
    assertThat(recorder.scannedNames()).doesNotContain("uncovered.txt")
    assertThat(recorder.completions.get()).isZero()
  }

  @Test
  @RegistryKey("find.in.files.in.non.indexable.enable", "false")
  fun `completion hook does not fire when the registry skips the non-indexable walk`(): Unit = runBlocking {
    val recorder = HookRecorder()
    registerSearcher(reliable = true, coveredNames = emptySet(), recorder = recorder)

    assertThat(findFileNames("data")).doesNotContain("uncovered.txt")
    assertThat(recorder.completions.get()).isZero()
  }

  @Test
  fun `a searcher that returns INDEXED_ONLY with occurrences still walks`(): Unit = runBlocking {
    registerSearcher(reliable = true, coveredNames = emptySet(),
                     coverage = Coverage.INDEXED_ONLY, occurrences = listOf(coveredFile))

    assertThat(findFileNames("data")).containsExactlyInAnyOrder("covered.txt", "uncovered.txt")
  }

  @Test
  fun `ALL_CANDIDATES of an unreliable searcher does not skip the walk`(): Unit = runBlocking {
    registerSearcher(reliable = false, coveredNames = emptySet(),
                     coverage = Coverage.ALL_CANDIDATES, occurrences = listOf(coveredFile))

    assertThat(findFileNames("data")).containsExactlyInAnyOrder("covered.txt", "uncovered.txt")
  }

  @Test
  fun `a search that skips the walk keeps the file mask`(): Unit = runBlocking {
    val maskedFile = baseDir.newVirtualFile("non-indexable/masked.md", "masked file with zq data".toByteArray())
    registerSearcher(reliable = true, coveredNames = emptySet(),
                     coverage = Coverage.ALL_CANDIDATES, occurrences = listOf(coveredFile, maskedFile))

    assertThat(findFileNames("data", fileMask = "*.txt")).containsExactlyInAnyOrder("covered.txt")
  }

  @Test
  fun `FindModelExtension files are searched when the walk is skipped`(): Unit = runBlocking {
    val extensionFile = baseDir.newVirtualFile("extension.txt", "extension file with zq data".toByteArray())
    FindModelExtension.EP_NAME.point.registerExtension(
      FindModelExtension { _, _, consumer -> consumer.process(extensionFile) },
      disposable)
    registerSearcher(reliable = true, coveredNames = emptySet(),
                     coverage = Coverage.ALL_CANDIDATES, occurrences = listOf(coveredFile))

    assertThat(findFileNames("data")).contains("covered.txt", "extension.txt")
    assertThat(findFileNames("data")).doesNotContain("uncovered.txt")
  }

  @Test
  fun `completion hook fires after a full scan`(): Unit = runBlocking {
    val recorder = HookRecorder()
    registerSearcher(reliable = true, coveredNames = emptySet(), recorder = recorder)

    findFileNames("data")

    assertThat(recorder.completions.get()).isEqualTo(1)
  }

  @Test
  fun `completion hook does not fire when the usage processor stops early`(): Unit = runBlocking {
    val recorder = HookRecorder()
    registerSearcher(reliable = true, coveredNames = emptySet(), recorder = recorder)

    findUsagesStoppingAtFirstUsage()

    assertThat(recorder.completions.get()).isZero()
  }

  @Test
  fun `a stop in the fast phase ends the search before the walk`(): Unit = runBlocking {
    val recorder = HookRecorder()
    registerSearcher(reliable = true, coveredNames = emptySet(),
                     recorder = recorder, occurrences = listOf(coveredFile))

    findUsagesStoppingAtFirstUsage()

    assertThat(recorder.completions.get()).isZero()
    assertThat(recorder.scannedNames()).doesNotContain("uncovered.txt")
  }

  /** Records the [ScanObserver.fileScanned] and [ScanObserver.nonIndexedScanCompleted] notifications. */
  private class HookRecorder {
    val scanned: MutableList<Pair<String, String>> = synchronizedList(ArrayList())
    val completions: AtomicInteger = AtomicInteger()

    fun scannedNames(): List<String> = scanned.map { it.first }.distinct()
    fun scannedTexts(fileName: String): List<String> = scanned.filter { it.first == fileName }.map { it.second }.distinct()
  }

  /**
   * Registers a searcher that returns the [occurrences] from [FindInProjectSearcher.searchForOccurrences].
   * Like the trigram engine, it covers a file only when the pattern has trigrams.
   * Its [FindInProjectSearcher.processOccurrences] feeds the [occurrences], then returns [coverage].
   * The optional [recorder] collects the scan notifications.
   */
  private fun registerSearcher(reliable: Boolean,
                               coveredNames: Set<String>,
                               recorder: HookRecorder? = null,
                               coverage: Coverage = Coverage.INDEXED_ONLY,
                               occurrences: Collection<VirtualFile> = emptyList()) {
    val engine = object : FindInProjectSearchEngine {
      override fun createSearcher(findModel: FindModel, project: Project): FindInProjectSearcher {
        val patternHasTrigrams = !TrigramBuilder.getTrigrams(findModel.stringToFind).isEmpty()
        fun covers(file: VirtualFile) = patternHasTrigrams && file.name in coveredNames
        return object : ScanObserver {
          override fun searchForOccurrences(): Collection<VirtualFile> = occurrences
          override fun processOccurrences(processor: Processor<in VirtualFile>): Coverage =
            if (super.processOccurrences(processor) == Coverage.STOPPED) Coverage.STOPPED else coverage
          override fun isReliable(): Boolean = reliable
          override fun isCovered(file: VirtualFile): Boolean = covers(file)
          override fun fileScanned(file: VirtualFile, text: CharSequence) {
            recorder?.scanned?.add(file.name to text.toString())
          }
          override fun nonIndexedScanCompleted() {
            recorder?.completions?.incrementAndGet()
          }
        }
      }
    }
    FindInProjectSearchEngine.EP_NAME.point.registerExtension(engine, disposable)
  }

  private fun createFindModel(pattern: String, fileMask: String?): FindModel {
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
      fileFilter = fileMask
      moduleName = null
      customScopeName = null
    }
  }

  private fun findFileNames(pattern: String, fileMask: String? = null): List<String> {
    val model = createFindModel(pattern, fileMask)
    val usages = synchronizedList<UsageInfo?>(ArrayList())
    val consumer: Processor<UsageInfo?> = CollectProcessor(usages)
    val presentation = FindInProjectUtil.setupProcessPresentation(false, FindInProjectUtil.setupViewPresentation(false, model))
    FindInProjectUtil.findUsages(model, project, consumer, presentation)
    return usages.map { it!!.virtualFile!!.name }.distinct()
  }

  private fun findUsagesStoppingAtFirstUsage() {
    val model = createFindModel("data", fileMask = null)
    val presentation = FindInProjectUtil.setupProcessPresentation(false, FindInProjectUtil.setupViewPresentation(false, model))
    FindInProjectUtil.findUsages(model, project, { false }, presentation)
  }
}

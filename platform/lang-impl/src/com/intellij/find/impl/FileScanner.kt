// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.codeWithMe.ClientId
import com.intellij.concurrency.ConcurrentCollectionFactory
import com.intellij.find.FindBundle
import com.intellij.find.FindModel
import com.intellij.find.findInProject.FindInProjectManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.TooManyUsagesStatus
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.isProjectOrWorkspaceFile
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.DiskQueryRelay
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.isTooLarge
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtilCore
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.impl.UsageViewManagerImpl
import com.intellij.util.Processor
import com.intellij.util.TimeoutUtil
import com.intellij.util.text.StringSearcher
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<FileScanner>()

/** Total size of processed files before asking the user 'too many files, should we continue?' */
private const val TOTAL_FILES_SIZE_LIMIT_BEFORE_ASKING = 70 * 1024 * 1024 // megabytes.

/** The progress text formatting is too costly for every file, so the text updates on a time budget. */
private val PROGRESS_TEXT_UPDATE_INTERVAL_NS = TimeUnit.MILLISECONDS.toNanos(100)

/**
 * Checks one candidate file of a "Find in Path" search for the pattern and delivers its usages to the usage processor.
 * Also does the counting (occurrences found, total size of the files with usages) and the progress text updates.
 * One instance serves the whole search; [process] is called from many workers, each under its read action.
 * A restarted work item (a write action interrupted its read action) scans its file again; the usages delivered before
 * are not delivered twice. The disk-loaded text of a scanned file goes to [SearcherSet.fileScanned].
 */
internal class FileScanner(
  private val findModel: FindModel,
  private val project: Project,
  private val searchers: SearcherSet,
  private val progressIndicator: ProgressIndicator,
  private val processPresentation: FindUsagesProcessPresentation,
  private val usageConsumer: Processor<in UsageInfo>,
  private val searchStartedAtNs: Long,
) : Processor<VirtualFile> {
  private val psiManager = PsiManager.getInstance(project)
  private val stringSearcher: StringSearcher? =
    if (findModel.isRegularExpressions || StringUtil.isEmpty(findModel.stringToFind)) null
    else StringSearcher(findModel.stringToFind, findModel.isCaseSensitive, true)
  private val clientId = ClientId.current

  private val occurrenceCount = AtomicInteger()

  /** The usages already delivered for a file; a file re-scanned after a cancellation does not deliver them twice. */
  private val usagesBeingProcessed = ConcurrentHashMap<VirtualFile, MutableSet<UsageInfo>>()
  private val reportedFirst = AtomicBoolean()

  /** The files skipped because they are too large. */
  val largeFiles: MutableSet<VirtualFile> = Collections.synchronizedSet(HashSet())
  private val totalFilesSize = AtomicLong()
  private val lastProgressTextUpdateNs = AtomicLong()

  /**
   * Looks up the search pattern in the file, and delivers all the usages found (if any) to the usage processor.
   *
   * @return false if the usage processor returns false for any of the occurrences found, true otherwise
   */
  override fun process(virtualFile: VirtualFile): Boolean {
    ClientId.withClientId(clientId).use {
      return scan(virtualFile)
    }
  }

  private fun scan(virtualFile: VirtualFile): Boolean {
    if (!virtualFile.isValid) return true

    val fileLength = UsageViewManagerImpl.getFileLength(virtualFile)
    if (fileLength == -1L) return true

    val skipProjectFile = isProjectOrWorkspaceFile(virtualFile) && !findModel.isSearchInProjectFiles
    if (skipProjectFile && !Registry.`is`("find.search.in.project.files")) return true

    if (virtualFile.isTooLarge()) {
      largeFiles.add(virtualFile)
      return true
    }

    progressIndicator.checkCanceled()
    val nowNs = System.nanoTime()
    val lastNs = lastProgressTextUpdateNs.get()
    if (nowNs - lastNs >= PROGRESS_TEXT_UPDATE_INTERVAL_NS && lastProgressTextUpdateNs.compareAndSet(lastNs, nowNs)) {
      progressIndicator.text = FindBundle.message("find.searching.for.string.in.file.progress",
                                                  findModel.stringToFind, virtualFile.presentableUrl)
      progressIndicator.text2 = FindBundle.message("find.searching.for.string.in.file.occurrences.progress", occurrenceCount)
    }

    //the text scan is much cheaper than the PSI lookup in findFile, so it runs first;
    // an admitted file is not binary (CandidateFilter maps a binary file to its source), the binary check is a guard:
    if (stringSearcher != null && !virtualFile.fileType.isBinary && !containsPattern(virtualFile, stringSearcher)) {
      return true
    }

    val pair = ReadAction.computeBlocking<Pair.NonNull<PsiFile, VirtualFile>?, RuntimeException> {
      findFile(psiManager, virtualFile)
    } ?: return true

    val processedUsages = usagesBeingProcessed.computeIfAbsent(virtualFile) { ConcurrentCollectionFactory.createConcurrentSet() }
    val psiFile = pair.first
    val sourceVirtualFile = pair.second

    if (stringSearcher != null && sourceVirtualFile != virtualFile && !containsPattern(sourceVirtualFile, stringSearcher)) {
      return true
    }
    val projectFileUsagesFound = AtomicBoolean()
    val processedSuccessfully = FindInProjectUtil.processUsagesInFile(psiFile, sourceVirtualFile, findModel) { info ->
      if (skipProjectFile) {
        projectFileUsagesFound.set(true)
        return@processUsagesInFile true
      }
      if (reportedFirst.compareAndSet(false, true)) {
        LOG.debug { "First usage found in ${TimeoutUtil.getDurationMillis(searchStartedAtNs)} ms" }
      }
      if (processedUsages.contains(info)) {
        return@processUsagesInFile true
      }
      val success = usageConsumer.process(info)
      processedUsages.add(info)
      success
    }
    if (!processedSuccessfully) {
      return false
    }
    usagesBeingProcessed.remove(virtualFile) // after the whole virtualFile processed successfully, remove mapping to save memory

    if (projectFileUsagesFound.get()) {
      processPresentation.projectFileUsagesFound {
        val model = findModel.clone()
        model.isSearchInProjectFiles = true
        FindInProjectManager.getInstance(project).startFindInProject(model)
      }
      return true
    }

    val totalSize = if (processedUsages.isEmpty()) {
      totalFilesSize.get()
    }
    else {
      occurrenceCount.addAndGet(processedUsages.size)
      totalFilesSize.addAndGet(fileLength)
    }

    if (totalSize > TOTAL_FILES_SIZE_LIMIT_BEFORE_ASKING) {
      val tooManyUsagesStatus = TooManyUsagesStatus.getFrom(progressIndicator)
      if (tooManyUsagesStatus.switchTooManyUsagesStatus()) {
        UsageViewManagerImpl.showTooManyUsagesWarningLater(project, tooManyUsagesStatus, progressIndicator, null, {
          FindBundle.message("find.excessive.total.size.prompt",
                             UsageViewManagerImpl.presentableSize(totalFilesSize.get()),
                             ApplicationNamesInfo.getInstance().productName)
        }, null)
      }
      tooManyUsagesStatus.pauseProcessingIfTooManyUsages()
      progressIndicator.checkCanceled()
    }
    return true
  }

  /** @return true when the file text contains the pattern. The cached document text wins over the disk content. */
  private fun containsPattern(file: VirtualFile, searcher: StringSearcher): Boolean {
    val document = FileDocumentManager.getInstance().getCachedDocument(file)
    val text = if (document != null) {
      document.charsSequence
    }
    else {
      DiskQueryRelay.compute<CharSequence, RuntimeException> { LoadTextUtil.loadText(file, -1) }
        .also { searchers.fileScanned(file, it) }
    }
    return text.isNotEmpty() && searcher.scan(text) >= 0
  }

  companion object {
    /** @return [psiFile, sourceFile] corresponding to the virtualFile; null when either is binary. Needs a read action. */
    @JvmStatic
    fun findFile(psiManager: PsiManager, virtualFile: VirtualFile): Pair.NonNull<PsiFile, VirtualFile>? {
      var psiFile = psiManager.findFile(virtualFile)
      if (psiFile != null) {
        val sourceFile = psiFile.navigationElement
        if (sourceFile is PsiFile) psiFile = sourceFile
        if (psiFile.fileType.isBinary) {
          psiFile = null
        }
      }
      val sourceVirtualFile = PsiUtilCore.getVirtualFile(psiFile)
      if (psiFile == null || psiFile.fileType.isBinary
          || sourceVirtualFile == null || sourceVirtualFile.fileType.isBinary) {
        return null
      }

      return Pair.createNonNull(psiFile, sourceVirtualFile)
    }
  }
}

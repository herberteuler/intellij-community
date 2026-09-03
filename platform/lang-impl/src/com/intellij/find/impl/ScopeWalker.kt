// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.find.DirectorySearchEngine
import com.intellij.find.FindModel
import com.intellij.find.FindModelExtension
import com.intellij.find.impl.CandidateFilter.Source
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileFilter
import com.intellij.openapi.vfs.newvfs.CacheAvoidingVirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.ide.productMode.IdeProductMode
import com.intellij.platform.workspace.jps.entities.ModuleId
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopeUtil
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.impl.VirtualFileEnumeration
import com.intellij.util.indexing.ConcurrentFileTraversal
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexEx
import com.intellij.util.indexing.roots.IndexableEntityProviderMethods
import com.intellij.util.indexing.roots.IndexableFilesIterator
import com.intellij.util.indexing.roots.kind.ContentOrigin

private val LOG = logger<ScopeWalker>()

/**
 * Walks the scope of a "Find in Path" search: phase 3 of [FindSearchRun], the brute-force search.
 * [roots] builds the [WalkPlan] on the producer thread; [expand] unfolds one walk item of that plan on a
 * worker, under its read action, into more walk payloads and candidate files.
 * The walker holds only the facts of the model; everything a search decides at its start lives in its [WalkPlan].
 * ```
 * payload := { VirtualFile | IndexableFilesIterator | FindModelExtension | TraversalItem }
 * ```
 */
internal class ScopeWalker(
  private val findModel: FindModel,
  private val project: Project,
  private val candidateFilter: CandidateFilter,
  private val moduleToSearchIn: Module?,
  private val directoryToSearchIn: VirtualFile?,
  private val withSubdirectories: Boolean,
  private val customScope: GlobalSearchScope?,
) {
  /**
   * Receives what [expand] finds. Both methods return false when the search stopped.
   * The contract is synchronous: everything an item finds reaches the sink before [expand] returns, because the item
   * finishes then and its phase may drain. So a [DirectorySearchEngine] or an iterator must call its callback before
   * it returns, never later from another thread.
   */
  interface Sink {
    fun addItem(payload: Any): Boolean

    fun addCandidate(file: VirtualFile, source: Source): Boolean
  }

  /**
   * What [roots] decided for one search; [expand] reads it on the workers.
   *
   * @param items        the root walk payloads
   * @param searchInLibs true when the custom scope searches in libraries, so a library iterator is walked too
   * @param engines      the [DirectorySearchEngine]s whose [DirectorySearchEngine.canSearch] accepted the model
   */
  data class WalkPlan(
    val items: List<Any>,
    val searchInLibs: Boolean,
    val engines: List<DirectorySearchEngine>,
    /**
     * False when a project search skips the walk of the non-indexable files: the registry key is off, or a reliable
     * searcher reported [com.intellij.find.FindInProjectSearchEngine.Coverage.ALL_CANDIDATES]; true in every other branch.
     * Only a walked plan earns [com.intellij.find.FindInProjectSearchEngine.ScanObserver.nonIndexedScanCompleted].
     */
    val nonIndexableWalked: Boolean,
  )

  /**
   * Builds the walk items from _one of_ {custom scope | directory | module | indexable providers}, plus the
   * [FindModelExtension]s. Runs on the producer thread, once per search and only when the walk phase runs; asks
   * [DirectorySearchEngine.canSearch] here, outside a read action.
   *
   * @param skipNonIndexableRoots true when a reliable searcher reported
   *                              [com.intellij.find.FindInProjectSearchEngine.Coverage.ALL_CANDIDATES]
   *                              (see [SearcherSet.nonIndexableCovered]), so the walk of the non-indexable files is unnecessary
   */
  fun roots(skipNonIndexableRoots: Boolean): WalkPlan {
    val globalCustomScope = customScope
    val searchInLibs = globalCustomScope != null &&
                       ReadAction.computeBlocking<Boolean, RuntimeException> { globalCustomScope.isSearchInLibraries }
    val engines = DirectorySearchEngine.EP_NAME.extensionList.filter { it.canSearch(findModel) }

    val modelCustomScope = if (findModel.isCustomScope) findModel.customScope else null
    val items = ArrayList<Any>()
    var nonIndexableWalked = true

    if (modelCustomScope is LocalSearchScope) {
      items.addAll(GlobalSearchScopeUtil.getLocalScopeFiles(modelCustomScope))
    }
    else if (modelCustomScope is VirtualFileEnumeration) {
      // GlobalSearchScope can include files out of project roots e.g., FileScope / FilesScope. The starting files are all
      // in VFS already (because they have ids), but file-tree down from them could be not in VFS cache yet -- so it is worth
      // wrapping all the files into cache-avoiding wrappers here, and avoid trashing VFS cache with new entries during lookup:
      for (file in FileBasedIndexEx.toFileIterable(modelCustomScope.asArray())) {
        items.add(NewVirtualFile.asCacheAvoiding(file))
      }
    }
    else if (directoryToSearchIn != null) {
      //Directory could be anywhere outside the project, hence it is worth wrapping it into a cache-avoiding wrapper,
      // so walking through its children won't trash VFS cache with new entries from some rarely used file-tree:
      val cacheAvoidingDirectory = NewVirtualFile.asCacheAvoiding(directoryToSearchIn)
      if (withSubdirectories) {
        items.add(cacheAvoidingDirectory)

        // DirectorySearchEngine is not obliged to add unsaved documents to the search queue - do it now.
        items.addAll(getUnsavedDocumentsUnderDirectory(directoryToSearchIn))
      }
      else {
        items.addAll(cacheAvoidingDirectory.children)
      }
      //MAYBE RC: should we return early here? Should FindModelExtension be added if user explicitly
      //          request a search in a specific directory _only_?
    }
    else if (moduleToSearchIn != null) {
      LOG.assertTrue(!IdeProductMode.isLight, "Search in module should not happen in ijLight. Searched module: $moduleToSearchIn")

      val storage = WorkspaceModel.getInstance(project).currentSnapshot
      val moduleEntity = checkNotNull(storage.resolve(ModuleId(moduleToSearchIn.name)))
      //MAYBE RC: wrap files into a cache-avoiding wrappers?
      //          It seems useless, since files are all indexable, so they are already scanned and cached in VFS -- but is it true?
      items.addAll(IndexableEntityProviderMethods.createIterators(moduleEntity, storage, project))
    }
    else {
      LOG.assertTrue(!IdeProductMode.isLight, "Search in project should not happen in ijLight. Please use search in directory instead.")

      val indexes = FileBasedIndex.getInstance() as FileBasedIndexEx
      //Don't wrap those files in cache-avoiding wrappers: indexable files are scanned, and hence (will be) cached in VFS anyway:
      items.addAll(indexes.getIndexableFilesProviders(project))

      if (Registry.`is`("find.in.files.in.non.indexable.enable") && !skipNonIndexableRoots) {
        val searchInLibraries = when (modelCustomScope) {
          null -> false // default scope is 'Project', no libraries there
          is GlobalSearchScope -> modelCustomScope.isSearchInLibraries
          else -> true
        }

        //MAYBE RC: currently nonIndexableFiles() returns transient files already -- but maybe it is safer to return _regular_ files
        //          from nonIndexableFiles(), and wrap them all into transient here, in a unified way?
        items.addAll(ReadAction.nonBlocking<Collection<ConcurrentFileTraversal.TraversalItem>> {
          ConcurrentFileTraversal.nonIndexableTraversal(project, searchInLibraries).roots
        }.executeSynchronously())
      }
      else {
        nonIndexableWalked = false
      }
    }

    items.addAll(FindModelExtension.EP_NAME.extensionList)
    return WalkPlan(items, searchInLibs, engines, nonIndexableWalked)
  }

  /**
   * Unfolds one walk item of the plan into more walk payloads and candidate files. Runs on a worker, under its read action.
   * A directory item claims its directory ([CandidateFilter.claimDirectory]); a restarted run keeps the claim and
   * expands the directory again.
   */
  fun expand(plan: WalkPlan, item: WorkItem.Walk, sink: Sink) {
    ProgressManager.checkCanceled()

    when (val payload = item.payload) {
      is IndexableFilesIterator -> {
        if (plan.searchInLibs || payload.origin is ContentOrigin) {
          payload.iterateFiles(project, { file -> file.isDirectory || sink.addCandidate(file, Source.WALK) }, VirtualFileFilter.ALL)
        }
      }
      is ConcurrentFileTraversal.TraversalItem -> {
        val shouldProcessFile = payload.expand { children ->
          for (child in children) {
            sink.addItem(child)
          }
        }
        if (shouldProcessFile && !payload.file.isDirectory) {
          sink.addCandidate(payload.file, Source.WALK)
        }
      }
      is FindModelExtension -> {
        //the extension files pass the mask and are searched once, but skip the exclusion and scope checks (Rider's external scope):
        //MAYBE RC: withSubdirs is ignored here: all the directories are just skipped.
        payload.iterateAdditionalFiles(findModel, project) { file -> sink.addCandidate(file, Source.EXTENSION) }
      }
      is VirtualFile -> {
        if (payload.isDirectory) expandDirectory(plan, item, payload, sink) else sink.addCandidate(payload, Source.WALK)
      }
      else -> throw AssertionError("unknown walk payload: $payload")
    }
  }

  private fun expandDirectory(plan: WalkPlan, item: WorkItem.Walk, directory: VirtualFile, sink: Sink) {
    if (!candidateFilter.claimDirectory(item, directory)) return
    if (directoryToSearchIn != null && withSubdirectories) {
      // note that search engine may unfold more than one level and eventually visit already indexed, excluded or ignored
      // files or directories. This might be a performance problem, but does not affect correctness: all the files
      // will be checked against the WSM and requested scope later when this search task deals with individual files.
      // MAYBE-ANK: While it is searcher's responsibility to work at least faster than the default implementation if it
      // claims positive weight, it is also a good idea to not pass directories with excludes and indexed files into them.
      val directorySearchEngine = DirectorySearchEngine.selectDirectorySearchEngine(directory, plan.engines)
      if (directorySearchEngine != null) {
        directorySearchEngine.searchDirectory(directory, findModel) { files ->
          for (file in files) {
            sink.addItem(file)
          }
        }
      }
      else {
        LOG.error("At least DefaultDirectorySearchEngine must be available. No directory search engine for $directory")
      }
    }
  }
}

private fun getUnsavedDocumentsUnderDirectory(directory: VirtualFile): Collection<VirtualFile> {
  val cacheableDirectory = if (directory is CacheAvoidingVirtualFile) directory.asCacheable() ?: return emptyList() else directory

  val fileDocumentManager = FileDocumentManager.getInstance()
  return fileDocumentManager.unsavedDocuments.mapNotNull { document ->
    fileDocumentManager.getFile(document)?.takeIf { VfsUtilCore.isAncestor(cacheableDirectory, it, false) }
  }
}

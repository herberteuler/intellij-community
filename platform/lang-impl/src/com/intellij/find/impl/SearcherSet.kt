// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.find.FindInProjectSearchEngine
import com.intellij.find.FindInProjectSearchEngine.Coverage
import com.intellij.find.FindInProjectSearchEngine.FindInProjectSearcher
import com.intellij.find.FindInProjectSearchEngine.ScanObserver
import com.intellij.find.FindModel
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Processor
import java.util.concurrent.CancellationException

private val LOG = logger<SearcherSet>()

/**
 * The searchers of one "Find in Path" search (see [FindInProjectSearcher]).
 * Runs them, records whether a reliable one covers every non-indexable candidate,
 * asks them for covered files, and delivers the scan hooks to the [ScanObserver]s among them.
 * [FindInProjectSearcher.isReliable] is read once, at construction.
 */
internal class SearcherSet(searchers: List<FindInProjectSearcher>) {
  private val searchers: Array<FindInProjectSearcher> = searchers.toTypedArray()

  /** Cached value of [searchers[i].isReliable() for all i]. */
  private val isSearcherReliable = BooleanArray(this.searchers.size) { this.searchers[it].isReliable }

  /** Is there at least one reliable searcher? */
  private val hasReliableSearchers = isSearcherReliable.any { it }

  /** The searchers that observe the scan. */
  private val observers: List<ScanObserver> = this.searchers.filterIsInstance<ScanObserver>()

  /** Did a reliable searcher return [Coverage.ALL_CANDIDATES]? */
  @Volatile
  private var allCandidatesCovered = false

  companion object {
    /** @return the searchers that every [FindInProjectSearchEngine] creates for the model */
    @JvmStatic
    fun create(findModel: FindModel, project: Project): SearcherSet {
      return SearcherSet(FindInProjectSearchEngine.EP_NAME.extensionList.mapNotNull { it.createSearcher(findModel, project) })
    }
  }

  /** @return the searchers whose files are collected before they are checked (see [FindInProjectSearcher.isStreaming]) */
  fun collecting(): List<FindInProjectSearcher> = searchers.filter { !it.isStreaming }

  /** @return the searchers whose files are checked while they search (see [FindInProjectSearcher.isStreaming]) */
  fun streaming(): List<FindInProjectSearcher> = searchers.filter { it.isStreaming }

  /**
   * @return true when a reliable searcher covers the file (see [FindInProjectSearcher.isCovered]):
   * the file is then either found by that searcher already, or guaranteed to not contain the pattern
   */
  fun isCoveredByReliable(file: VirtualFile): Boolean {
    if (!hasReliableSearchers) return false
    for (i in searchers.indices) {
      if (isSearcherReliable[i] && searchers[i].isCovered(file)) {
        return true
      }
    }
    return false
  }

  /**
   * Runs [FindInProjectSearcher.processOccurrences] of one of the searchers of this set
   * and records its [Coverage.ALL_CANDIDATES], which counts only for a reliable searcher.
   */
  fun process(searcher: FindInProjectSearcher, processor: Processor<in VirtualFile>): Coverage {
    val index = indexOf(searcher)
    val coverage = searcher.processOccurrences(processor)
    if (isSearcherReliable[index] && coverage == Coverage.ALL_CANDIDATES) {
      allCandidatesCovered = true
    }
    return coverage
  }

  private fun indexOf(searcher: FindInProjectSearcher): Int {
    val index = searchers.indexOfFirst { it === searcher }
    require(index >= 0) { "Not a searcher of this set: $searcher" }
    return index
  }

  /**
   * @return true when a reliable searcher returned [Coverage.ALL_CANDIDATES] from [process]:
   * it fed or covers every non-indexable candidate, so the brute-force walk of the non-indexable files is skipped
   */
  fun nonIndexableCovered(): Boolean = allCandidatesCovered

  /** Delivers the disk-loaded text to every observer. A hook error must not fail the scan. */
  fun fileScanned(file: VirtualFile, text: CharSequence) {
    notifyObservers { it.fileScanned(file, text) }
  }

  /** Reports the brute-force scan completion to every observer. A hook error must not fail the search. */
  fun nonIndexedScanCompleted() {
    notifyObservers { it.nonIndexedScanCompleted() }
  }

  private inline fun notifyObservers(hook: (ScanObserver) -> Unit) {
    for (observer in observers) {
      try {
        hook(observer)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: Exception) {
        LOG.error(e)
      }
    }
  }
}

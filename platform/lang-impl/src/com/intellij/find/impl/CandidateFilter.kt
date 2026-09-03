// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.concurrency.ConcurrentCollectionFactory
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.util.containers.ConcurrentBitSet
import java.util.concurrent.atomic.AtomicIntegerArray

private val LOG = logger<CandidateFilter>()

/**
 * The only admission rule for Find in Files candidates, in two stages, and the one claim registry of a search.
 * - [admitOnProducer]: cheap checks without a read action: valid, not a directory, the file mask,
 *   and offered once per [Source]. An offered file is queued once per source, and its item is restarted, not offered again.
 * - [admitOnWorker]: the checks that need a read action, chosen by the [Source]; maps the file to the file
 *   to scan (a binary file from any source maps to its source, or is dropped), then claims that file,
 *   so it is scanned once across all sources.
 *   A rejected item takes no claim, so another source with other rules can still scan the file.
 * - [claimDirectory]: the walk enters each directory once; directories have their own key space.
 *
 * No key is ever released. A claim is recorded on its [WorkItem] ([WorkItem.claimed]) right where its key is added,
 * so a restarted run of the item (a write action interrupted its read action) keeps its admission and skips the checks.
 * Files are keyed by id, so a cache-avoiding wrapper and its twin are one file; a file without id is keyed by URL.
 *
 * @param fileMask           the file mask of the model
 * @param excluded           true for a file excluded from the search; not applied to [Source.EXTENSION]
 * @param inModelScope       the search scope of the model; applied to [Source.PRIORITY] only
 * @param inCustomScope      the custom scope of the model; applied to [Source.WALK] only
 * @param coveredByReliable  true when a reliable searcher covers the file; applied to [Source.WALK] only
 * @param locateClassSources true when a walked binary file maps to its source, false when it is dropped
 * @param sourceOf           the non-binary source of a binary file, or null; applied to every source
 */
internal class CandidateFilter(
  private val fileMask: (VirtualFile) -> Boolean,
  private val excluded: (VirtualFile) -> Boolean,
  private val inModelScope: (VirtualFile) -> Boolean,
  private val inCustomScope: (VirtualFile) -> Boolean,
  private val coveredByReliable: (VirtualFile) -> Boolean,
  private val locateClassSources: Boolean,
  private val sourceOf: (VirtualFile) -> VirtualFile?,
) {
  /** Where a candidate comes from; the source decides which checks [admitOnWorker] applies. */
  enum class Source {
    /** Open files and the files of the previous search. */
    PRIORITY,

    /** Files found by a [com.intellij.find.FindInProjectSearchEngine.FindInProjectSearcher]. */
    SEARCHER,

    /** Files found by the walk of the search scope. */
    WALK,

    /** Files from a [com.intellij.find.FindModelExtension]. */
    EXTENSION,
  }

  private val offered = Array(Source.entries.size) { FileKeys() }
  private val claimedFiles = FileKeys()
  private val claimedDirectories = FileKeys()
  private val admittedCounts = AtomicIntegerArray(Source.entries.size)

  /**
   * @return true when the file may be queued: it is valid, not a directory, passes the mask, and the source did not offer it yet.
   * No read action.
   */
  fun admitOnProducer(file: VirtualFile, source: Source): Boolean {
    return file.isValid && !file.isDirectory && fileMask(file) && offered[source.ordinal].add(file)
  }

  /**
   * Applies the checks of the source to a queued file, then claims the file to scan for the item. Needs a read action.
   * A restarted run of an admitted item gets its claimed file again, without the checks.
   *
   * @return the file to scan, or null when the file is rejected or the file to scan is claimed by another item
   */
  fun admitOnWorker(item: WorkItem.Candidate): VirtualFile? {
    item.claimed?.let { return it }
    val file = item.file
    val source = item.source
    //a claimed file is never admitted again, so its source checks (e.g. a searcher's isCovered) are not asked:
    if (!file.isValid || claimedFiles.contains(file)) return null
    val toScan = check(file, source)
    if (toScan == null || !claim(item, claimedFiles, toScan)) return null
    admittedCounts.incrementAndGet(source.ordinal)
    LOG.debug { "Admitted $source ${toScan.presentableUrl}" }
    return toScan
  }

  /**
   * Claims a directory for the walk item that expands it. Needs a read action.
   * A restarted run of the item that claimed it gets true again, without the checks.
   *
   * @return true when the item may expand the directory; false when it is invalid, excluded, or claimed by another item
   */
  fun claimDirectory(item: WorkItem.Walk, directory: VirtualFile): Boolean {
    if (item.claimed != null) return true
    if (!directory.isValid || excluded(directory)) return false
    return claim(item, claimedDirectories, directory)
  }

  fun admittedCount(source: Source): Int = admittedCounts.get(source.ordinal)

  private fun check(file: VirtualFile, source: Source): VirtualFile? {
    if (source != Source.EXTENSION && excluded(file)) return null
    if (source == Source.PRIORITY && !inModelScope(file)) return null
    //a covered file is either searched already, or guaranteed to not contain the pattern:
    if (source == Source.WALK && (!inCustomScope(file) || coveredByReliable(file))) return null

    if (!file.fileType.isBinary) return file
    if (source == Source.WALK && !locateClassSources) return null
    //every source claims the scanned file, so a class and its walked source are scanned once:
    return sourceOf(file)
  }

  /** Adds the key and records it on the item; nothing between the two may throw, so a restarted run always sees its claim. */
  private fun claim(item: WorkItem, keys: FileKeys, file: VirtualFile): Boolean {
    if (!keys.add(file)) return false
    item.claimed = file
    return true
  }

  /** A concurrent set of files, keyed by id, or by URL for a file without id. */
  private class FileKeys {
    private val ids = ConcurrentBitSet.create()
    private val urls = ConcurrentCollectionFactory.createConcurrentSet<String>()

    fun add(file: VirtualFile): Boolean = if (file is VirtualFileWithId) !ids.set(file.id) else urls.add(file.url)

    fun contains(file: VirtualFile): Boolean = if (file is VirtualFileWithId) ids.get(file.id) else urls.contains(file.url)
  }
}

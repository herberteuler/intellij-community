// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find;

import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Processor;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * Defines a search engine which will be used to find results in "Find in Path" and "Replace in Path" actions.
 * Several search engines can be used at the same moment to achieve best performance (time-to-result).
 */
@ApiStatus.Experimental
public interface FindInProjectSearchEngine {
  @ApiStatus.Internal
  ExtensionPointName<FindInProjectSearchEngine> EP_NAME = ExtensionPointName.create("com.intellij.findInProjectSearchEngine");

  /**
   * Constructs a searcher for a given {@param findModel} which serves as a input query.
   */
  @Nullable
  FindInProjectSearcher createSearcher(@NotNull FindModel findModel, @NotNull Project project);

  @ApiStatus.Experimental
  interface FindInProjectSearcher {
    /**
     * @return files that contain non-trivial search results for corresponding {@link FindModel}.
     * Returned files are _likely_ contain occurrences of the query, but it's not 100% guaranteed, so additional check may be needed
     */
    @NotNull
    Collection<VirtualFile> searchForOccurrences();

    /**
     * Feeds the same files as {@link #searchForOccurrences()} to the {@code processor}.
     * "Find in Path" calls this method once per search, instead of {@link #searchForOccurrences()}.
     * The default implementation feeds the result of {@link #searchForOccurrences()}.
     * <p>
     * A streaming searcher (see {@link #isStreaming()}) overrides this method to feed each file as soon as it finds it,
     * so "Find in Path" checks the file while the search goes on.
     * The searcher may call the processor from any thread.
     *
     * @return {@link Coverage#STOPPED} when the {@code processor} returned false (the searcher must stop then);
     * otherwise what the fed files cover, {@link Coverage#INDEXED_ONLY} by default
     */
    @ApiStatus.Experimental
    default @NotNull Coverage processOccurrences(@NotNull Processor<? super VirtualFile> processor) {
      for (VirtualFile file : searchForOccurrences()) {
        if (!processor.process(file)) {
          return Coverage.STOPPED;
        }
      }
      return Coverage.INDEXED_ONLY;
    }

    /**
     * Returns true when {@link #processOccurrences(Processor)} feeds files while it still searches.
     * "Find in Path" then checks each file at once, in the order fed.
     * Otherwise, it collects all the files of all non-streaming searchers first and checks them in a stable order.
     */
    @ApiStatus.Experimental
    default boolean isStreaming() {
      return false;
    }

    /**
     * @return true if there are no occurrences can be found outside the result of {@link FindInProjectSearcher#searchForOccurrences()},
     * <p>
     * More specifically: if this method returns true, and {@link #searchForOccurrences()} does NOT return file X, and
     * {@code isCovered(X)==true} => file X is guaranteed to NOT contain a search pattern.
     * If this method returns false, then even if {@code isCovered(X)==true} and {@link #searchForOccurrences()} does NOT return
     * the file X -- it is still possible that the file X contains a search pattern.
     */
    boolean isReliable();

    /**
     * Returns true if {@param file} is a part of "indexed" scope of corresponding search engine and no need to open file's content to find a query,
     * otherwise false.
     * <p>
     * Called for reliable searchers (see {@link FindInProjectSearcher#isReliable()}).
     * The answer must be truthful for any file, indexable or not.
     */
    boolean isCovered(@NotNull VirtualFile file);
  }

  /**
   * What the files fed by {@link FindInProjectSearcher#processOccurrences(Processor)} cover.
   */
  @ApiStatus.Experimental
  enum Coverage {
    /** The processor returned false, so the searcher stopped. */
    STOPPED,
    /**
     * The searcher fed its candidates, but a non-indexable file outside them may still contain the pattern,
     * so "Find in Path" walks the non-indexable files.
     */
    INDEXED_ONLY,
    /**
     * The searcher is complete for the current {@link FindModel} over the non-indexable files:
     * every non-indexable file that can contain the pattern was either fed to the processor
     * or is covered (see {@link FindInProjectSearcher#isCovered(VirtualFile)}).
     * "Find in Path" then skips the brute-force walk of the non-indexable files.
     * The searcher decides it when its enumeration ends, so it may decide from what the enumeration observed.
     * It counts only when the searcher is reliable (see {@link FindInProjectSearcher#isReliable()}).
     */
    ALL_CANDIDATES
  }

  /**
   * A searcher that also implements this interface learns what the "Find in Path" scan read.
   */
  @ApiStatus.Experimental
  interface ScanObserver {
    /**
     * Notifies the searcher that "Find in Path" loaded the {@code text} of the {@code file} from disk during the scan.
     * The hook does not fire when a cached document provides the text.
     * The scan can report the same file more than once.
     * The implementation must be cheap and non-blocking, and it must not throw.
     * A thrown {@link com.intellij.openapi.progress.ProcessCanceledException} makes the scan process the file again.
     */
    default void fileScanned(@NotNull VirtualFile file, @NotNull CharSequence text) {
    }

    /**
     * Notifies the searcher that "Find in Path" completed the brute-force scan of the non-indexed files.
     * The hook fires only after a full brute-force scan that processed every candidate file;
     * it does not fire when the search is canceled, and it does not fire when the usage processor stops the search.
     * It does not fire when a reliable searcher's {@link Coverage#ALL_CANDIDATES} or the registry skipped the walk of the non-indexable files.
     */
    default void nonIndexedScanCompleted() {
    }
  }
}

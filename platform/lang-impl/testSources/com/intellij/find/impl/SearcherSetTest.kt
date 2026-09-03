// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.find.FindInProjectSearchEngine.Coverage
import com.intellij.find.FindInProjectSearchEngine.FindInProjectSearcher
import com.intellij.find.FindInProjectSearchEngine.ScanObserver
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.Processor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Pins the searcher contract that [SearcherSet] keeps for `FindInProjectTask`. */
@TestApplication
class SearcherSetTest {
  private val file: VirtualFile = LightVirtualFile("a.txt", "text")

  @Test
  fun `isReliable is read once`() {
    val searcher = FakeSearcher(reliable = true, covers = true, coverage = Coverage.ALL_CANDIDATES)
    val set = SearcherSet(listOf(searcher))

    set.isCoveredByReliable(file)
    set.process(searcher) { true }
    set.nonIndexableCovered()
    set.isCoveredByReliable(file)

    assertThat(searcher.isReliableCalls).isEqualTo(1)
  }

  @Test
  fun `isCovered is asked only for reliable searchers`() {
    val unreliable = FakeSearcher(reliable = false, covers = true)
    val reliable = FakeSearcher(reliable = true, covers = false)

    assertThat(SearcherSet(listOf(unreliable, reliable)).isCoveredByReliable(file)).isFalse()
    assertThat(unreliable.isCoveredCalls).isZero()
    assertThat(reliable.isCoveredCalls).isEqualTo(1)
  }

  @Test
  fun `a reliable searcher that covers the file covers it`() {
    assertThat(SearcherSet(listOf(FakeSearcher(reliable = true, covers = true))).isCoveredByReliable(file)).isTrue()
  }

  @Test
  fun `ALL_CANDIDATES of a reliable searcher covers the non-indexable files`() {
    val indexedOnly = FakeSearcher(reliable = true)
    val allCandidates = FakeSearcher(reliable = true, coverage = Coverage.ALL_CANDIDATES)
    val set = SearcherSet(listOf(indexedOnly, allCandidates))

    assertThat(set.nonIndexableCovered()).describedAs("before any searcher ran").isFalse()
    assertThat(set.process(indexedOnly) { true }).isEqualTo(Coverage.INDEXED_ONLY)
    assertThat(set.nonIndexableCovered()).describedAs("after INDEXED_ONLY").isFalse()
    assertThat(set.process(allCandidates) { true }).isEqualTo(Coverage.ALL_CANDIDATES)
    assertThat(set.nonIndexableCovered()).describedAs("after ALL_CANDIDATES").isTrue()
    assertThat(indexedOnly.processOccurrencesCalls + allCandidates.processOccurrencesCalls).isEqualTo(2)
  }

  @Test
  fun `ALL_CANDIDATES of an unreliable searcher is ignored`() {
    val unreliable = FakeSearcher(reliable = false, coverage = Coverage.ALL_CANDIDATES)
    val set = SearcherSet(listOf(unreliable))

    assertThat(set.process(unreliable) { true }).isEqualTo(Coverage.ALL_CANDIDATES)
    assertThat(set.nonIndexableCovered()).isFalse()
  }

  @Test
  fun `STOPPED never covers the non-indexable files`() {
    val stopped = FakeSearcher(reliable = true, coverage = Coverage.STOPPED)
    val set = SearcherSet(listOf(stopped))

    assertThat(set.process(stopped) { true }).isEqualTo(Coverage.STOPPED)
    assertThat(set.nonIndexableCovered()).isFalse()
  }

  @Test
  fun `process passes the processor to the searcher`() {
    val searcher = FakeSearcher(reliable = true, files = listOf(file))
    val fed = ArrayList<VirtualFile>()

    assertThat(SearcherSet(listOf(searcher)).process(searcher) { fed.add(it) }).isEqualTo(Coverage.INDEXED_ONLY)
    assertThat(fed).containsExactly(file)
  }

  @Test
  fun `process rejects a searcher of another set`() {
    val set = SearcherSet(listOf(FakeSearcher(reliable = true)))

    assertThatThrownBy { set.process(FakeSearcher(reliable = true)) { true } }.isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun `collecting and streaming partition the searchers`() {
    val collecting = FakeSearcher(reliable = false)
    val streaming = FakeSearcher(reliable = false, streaming = true)
    val set = SearcherSet(listOf(collecting, streaming))

    assertThat(set.collecting()).containsExactly(collecting)
    assertThat(set.streaming()).containsExactly(streaming)
  }

  @Test
  fun `a hook error is logged, not thrown, and the other observers still get the hook`() {
    val failing = ObservingSearcher(hookError = IllegalStateException("hook failed"))
    val next = ObservingSearcher()
    val set = SearcherSet(listOf(failing, FakeSearcher(reliable = false), next))

    val scannedError = LoggedErrorProcessor.executeAndReturnLoggedError { set.fileScanned(file, "text") }
    assertThat(scannedError).hasMessage("hook failed")
    assertThat(next.fileScannedCalls).isEqualTo(1)

    val completedError = LoggedErrorProcessor.executeAndReturnLoggedError { set.nonIndexedScanCompleted() }
    assertThat(completedError).hasMessage("hook failed")
    assertThat(next.nonIndexedScanCompletedCalls).isEqualTo(1)
  }

  @Test
  fun `a hook cancellation is rethrown`() {
    val set = SearcherSet(listOf(ObservingSearcher(hookError = ProcessCanceledException())))

    assertThatThrownBy { set.fileScanned(file, "text") }.isInstanceOf(ProcessCanceledException::class.java)
    assertThatThrownBy { set.nonIndexedScanCompleted() }.isInstanceOf(ProcessCanceledException::class.java)
  }

  private open class FakeSearcher(
    private val reliable: Boolean,
    private val covers: Boolean = false,
    private val coverage: Coverage = Coverage.INDEXED_ONLY,
    private val streaming: Boolean = false,
    private val files: List<VirtualFile> = emptyList(),
  ) : FindInProjectSearcher {
    var isReliableCalls = 0
    var isCoveredCalls = 0
    var processOccurrencesCalls = 0

    override fun searchForOccurrences(): Collection<VirtualFile> = files

    override fun processOccurrences(processor: Processor<in VirtualFile>): Coverage {
      processOccurrencesCalls++
      if (!files.all { processor.process(it) }) return Coverage.STOPPED
      return coverage
    }

    override fun isReliable(): Boolean {
      isReliableCalls++
      return reliable
    }

    override fun isCovered(file: VirtualFile): Boolean {
      isCoveredCalls++
      return covers
    }

    override fun isStreaming(): Boolean = streaming
  }

  /** A searcher that observes the scan. */
  private class ObservingSearcher(private val hookError: RuntimeException? = null) : FakeSearcher(reliable = false), ScanObserver {
    var fileScannedCalls = 0
    var nonIndexedScanCompletedCalls = 0

    override fun fileScanned(file: VirtualFile, text: CharSequence) {
      fileScannedCalls++
      hookError?.let { throw it }
    }

    override fun nonIndexedScanCompleted() {
      nonIndexedScanCompletedCalls++
      hookError?.let { throw it }
    }
  }
}

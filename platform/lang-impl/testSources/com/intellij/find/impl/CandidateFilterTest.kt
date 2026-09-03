// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.find.impl.CandidateFilter.Source
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.openapi.vfs.newvfs.CacheAvoidingVirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.junit5.TestApplication
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeText

/** Pins the admission rules of [CandidateFilter] per [Source]: offers once per source, claims once across sources, claims kept on the item. */
@TestApplication
class CandidateFilterTest {
  private val text: VirtualFile = LightVirtualFile("a.txt", "text")
  private val source: VirtualFile = LightVirtualFile("Lib.txt", "class Lib")
  private val binary: VirtualFile = LightVirtualFile("Lib.class", UnknownFileType.INSTANCE, "")

  @Test
  fun `a plain text file is admitted from every source`() {
    for (s in Source.entries) {
      val filter = filter()
      assertThat(filter.admitOnProducer(text, s)).describedAs("$s").isTrue()
      assertThat(filter.admit(text, s)).describedAs("$s").isSameAs(text)
      assertThat(filter.admittedCount(s)).describedAs("$s").isEqualTo(1)
    }
  }

  @Test
  fun `a masked file is rejected for every source`() {
    val masked = LightVirtualFile("masked.md", "text")
    for (s in Source.entries) {
      assertThat(filter().admitOnProducer(masked, s)).describedAs("$s").isFalse()
    }
  }

  @Test
  fun `each source offers a file once, and the offer is not released`() {
    val filter = filter(inModelScope = false)
    for (s in Source.entries) {
      assertThat(filter.admitOnProducer(text, s)).describedAs("first $s offer").isTrue()
      assertThat(filter.admitOnProducer(text, s)).describedAs("second $s offer").isFalse()
    }
    assertThat(filter.admit(text, Source.PRIORITY)).isNull()
    assertThat(filter.admitOnProducer(text, Source.PRIORITY)).describedAs("offer after a rejection").isFalse()
  }

  @Test
  fun `two files without id and with one URL are two files`() {
    val other = LightVirtualFile("a.txt", "other text")
    assertThat(other).isNotEqualTo(text).isNotInstanceOf(VirtualFileWithId::class.java)
    assertThat(other.url).isEqualTo(text.url)

    val filter = filter()
    assertThat(filter.admitOnProducer(text, Source.WALK)).isTrue()
    assertThat(filter.admitOnProducer(other, Source.WALK)).isTrue()

    assertThat(filter.admit(text, Source.WALK)).isSameAs(text)
    assertThat(filter.admit(other, Source.WALK)).isSameAs(other)
    assertThat(filter.admitOnProducer(text, Source.WALK)).describedAs("second offer").isFalse()
    assertThat(filter.admit(text, Source.SEARCHER)).describedAs("second claim").isNull()
  }

  @Test
  fun `two cache-avoiding instances of one disk file without id are one file`(@TempDir dir: Path) {
    val path = dir.resolve("sub/t.txt")
    path.createParentDirectories().writeText("text")
    val root = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir) as NewVirtualFile
    assertThat(root.findChildIfCached("sub")).isNull()
    //found below an uncached parent, so its parent is a transient file:
    val belowTransient = root.fileSystem.findFileByPathWithoutCaching(path.invariantSeparatorsPathString)!!
    //listed from a cache-avoiding wrapper of the cached parent:
    val cachedSub = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.parent) as NewVirtualFile
    val belowWrapper = NewVirtualFile.asCacheAvoiding(cachedSub).findChild("t.txt")!!
    for (instance in listOf(belowTransient, belowWrapper)) {
      assertThat(instance).isInstanceOf(CacheAvoidingVirtualFile::class.java).isNotInstanceOf(VirtualFileWithId::class.java)
    }
    assertThat(belowWrapper).isNotEqualTo(belowTransient)
    assertThat(belowWrapper.url).isEqualTo(belowTransient.url)

    val filter = filter()
    assertThat(filter.admitOnProducer(belowTransient, Source.WALK)).isTrue()
    assertThat(filter.admitOnProducer(belowWrapper, Source.WALK)).isFalse()

    assertThat(filter.admit(belowWrapper, Source.WALK)).isSameAs(belowWrapper)
    assertThat(filter.admit(belowTransient, Source.SEARCHER)).isNull()
  }

  @Test
  fun `a cache-avoiding wrapper and its twin are one file`(@TempDir dir: Path) {
    val file = localFile(dir, "w.txt")
    val wrapper = NewVirtualFile.asCacheAvoiding(file)
    assertThat(wrapper).isNotSameAs(file).isInstanceOf(VirtualFileWithId::class.java)

    val filter = filter()
    assertThat(filter.admitOnProducer(file, Source.WALK)).isTrue()
    assertThat(filter.admitOnProducer(wrapper, Source.WALK)).isFalse()

    assertThat(filter.admit(wrapper, Source.WALK)).isSameAs(wrapper)
    assertThat(filter.admit(file, Source.SEARCHER)).isNull()
  }

  @Test
  fun `excluded applies to every source except EXTENSION`() {
    for (s in Source.entries) {
      val claim = filter(excluded = true).admit(text, s)
      if (s == Source.EXTENSION) assertThat(claim).isSameAs(text) else assertThat(claim).describedAs("$s").isNull()
    }
  }

  @Test
  fun `model scope applies to PRIORITY only`() {
    for (s in Source.entries) {
      val claim = filter(inModelScope = false).admit(text, s)
      if (s == Source.PRIORITY) assertThat(claim).isNull() else assertThat(claim).describedAs("$s").isSameAs(text)
    }
  }

  @Test
  fun `custom scope and coverage apply to WALK only`() {
    for (s in Source.entries) {
      val outOfCustomScope = filter(inCustomScope = false).admit(text, s)
      val covered = filter(covered = { true }).admit(text, s)
      if (s == Source.WALK) {
        assertThat(outOfCustomScope).isNull()
        assertThat(covered).isNull()
      }
      else {
        assertThat(outOfCustomScope).describedAs("$s").isSameAs(text)
        assertThat(covered).describedAs("$s").isSameAs(text)
      }
    }
  }

  @Test
  fun `binary rule per source`() {
    assertThat(binary.fileType.isBinary).isTrue()

    assertThat(filter().admit(binary, Source.PRIORITY)).isSameAs(source)
    assertThat(filter(sourceOf = null).admit(binary, Source.PRIORITY)).isNull()
    assertThat(filter(locateClassSources = true).admit(binary, Source.WALK)).isSameAs(source)
    assertThat(filter(locateClassSources = false).admit(binary, Source.WALK)).isNull()
    assertThat(filter().admit(binary, Source.SEARCHER)).isSameAs(source)
    assertThat(filter(sourceOf = null).admit(binary, Source.SEARCHER)).isNull()
    assertThat(filter().admit(binary, Source.EXTENSION)).isSameAs(source)
    assertThat(filter(sourceOf = null).admit(binary, Source.EXTENSION)).isNull()
  }

  @Test
  fun `a class and its source are claimed once`() {
    val filter = filter(locateClassSources = true)
    assertThat(filter.admit(binary, Source.WALK)).isSameAs(source)
    assertThat(filter.admitOnProducer(source, Source.WALK)).isTrue()
    assertThat(filter.admit(source, Source.WALK)).isNull()
  }

  @Test
  fun `a class from any source and its walked source are claimed once`() {
    for (s in listOf(Source.SEARCHER, Source.EXTENSION, Source.PRIORITY)) {
      val classFirst = filter()
      assertThat(classFirst.admit(binary, s)).describedAs("class from $s").isSameAs(source)
      assertThat(classFirst.admit(source, Source.WALK)).describedAs("walked source after the class from $s").isNull()

      val sourceFirst = filter()
      assertThat(sourceFirst.admit(source, Source.WALK)).describedAs("walked source before the class from $s").isSameAs(source)
      assertThat(sourceFirst.admit(binary, s)).describedAs("class from $s after the walked source").isNull()
    }
  }

  @Test
  fun `a rejection on the worker takes no claim, so another source scans the file`() {
    val filter = filter(inModelScope = false)
    assertThat(filter.admitOnProducer(text, Source.PRIORITY)).isTrue()
    assertThat(filter.admit(text, Source.PRIORITY)).isNull()

    assertThat(filter.admitOnProducer(text, Source.EXTENSION)).isTrue()
    assertThat(filter.admit(text, Source.EXTENSION)).isSameAs(text)
  }

  @Test
  fun `a WALK rejection on the worker does not drop the EXTENSION offer of the same file`() {
    val filter = filter(covered = { true })
    assertThat(filter.admitOnProducer(text, Source.WALK)).isTrue()
    assertThat(filter.admitOnProducer(text, Source.EXTENSION)).describedAs("EXTENSION offer after WALK").isTrue()
    assertThat(filter.admit(text, Source.WALK)).isNull()
    assertThat(filter.admit(text, Source.EXTENSION)).isSameAs(text)
  }

  @Test
  fun `a file is claimed once across sources`() {
    val filter = filter()
    for (s in Source.entries) {
      assertThat(filter.admitOnProducer(text, s)).describedAs("$s").isTrue()
    }
    assertThat(filter.admit(text, Source.SEARCHER)).isSameAs(text)
    for (s in Source.entries) {
      assertThat(filter.admit(text, s)).describedAs("$s after SEARCHER").isNull()
    }
    assertThat(filter.admittedCount(Source.SEARCHER)).isEqualTo(1)
  }

  @Test
  fun `a claimed file skips the source checks of a later item`() {
    var coverageChecks = 0
    val filter = filter(covered = { coverageChecks++; false })
    assertThat(filter.admit(text, Source.SEARCHER)).isSameAs(text)
    assertThat(filter.admit(text, Source.WALK)).isNull()
    assertThat(coverageChecks).describedAs("isCovered asked for a claimed file").isZero()
  }

  @Test
  fun `a restarted item keeps its claim and skips the checks, another item does not get the file`() {
    var coverageChecks = 0
    val filter = filter(covered = { coverageChecks++; false })
    val item = WorkItem.Candidate(text, Source.WALK)
    assertThat(filter.admit(item)).isSameAs(text)
    assertThat(item.claimed).isSameAs(text)

    assertThat(filter.admit(item)).describedAs("restarted run").isSameAs(text)
    assertThat(coverageChecks).describedAs("isCovered asked again on restart").isEqualTo(1)
    assertThat(filter.admittedCount(Source.WALK)).isEqualTo(1)
    assertThat(filter.admit(text, Source.WALK)).describedAs("another item").isNull()
  }

  @Test
  fun `a directory is claimed once, not when excluded, and again by the item that claimed it`(@TempDir dir: Path) {
    val directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!

    val filter = filter()
    assertThat(filter.admitOnProducer(directory, Source.WALK)).isFalse()
    val item = WorkItem.Walk.File(directory)
    assertThat(filter.claim(item, directory)).isTrue()
    assertThat(item.claimed).isSameAs(directory)
    assertThat(filter.claim(item, directory)).describedAs("restarted run").isTrue()
    val wrapper = NewVirtualFile.asCacheAvoiding(directory)
    assertThat(filter.claim(WorkItem.Walk.File(wrapper), wrapper)).describedAs("another item").isFalse()

    assertThat(filter(excluded = true).claim(WorkItem.Walk.File(directory), directory)).isFalse()
  }

  @Test
  fun `directories and files have separate claims`(@TempDir dir: Path) {
    val directory = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir)!!
    val file = localFile(dir, "f.txt")

    val filter = filter()
    assertThat(filter.claim(WorkItem.Walk.File(directory), directory)).isTrue()
    assertThat(filter.admit(file, Source.WALK)).isSameAs(file)
    assertThat(filter.claim(WorkItem.Walk.File(file), file)).describedAs("a file key does not block the directory key space").isTrue()
  }

  private fun filter(
    excluded: Boolean = false,
    inModelScope: Boolean = true,
    inCustomScope: Boolean = true,
    covered: () -> Boolean = { false },
    locateClassSources: Boolean = true,
    sourceOf: VirtualFile? = source,
  ): CandidateFilter {
    val mask = FindInProjectUtil.createFileMaskCondition("*.txt")
    return CandidateFilter(
      { mask.test(it.nameSequence) },
      { excluded },
      { inModelScope },
      { inCustomScope },
      { covered() },
      locateClassSources,
      { sourceOf },
    )
  }

  private fun CandidateFilter.admit(file: VirtualFile, source: Source): VirtualFile? = admit(WorkItem.Candidate(file, source))

  private fun CandidateFilter.admit(item: WorkItem.Candidate): VirtualFile? = runReadActionBlocking { admitOnWorker(item) }

  private fun CandidateFilter.claim(item: WorkItem.Walk.File, directory: VirtualFile): Boolean = runReadActionBlocking { claimDirectory(item, directory) }

  private fun localFile(dir: Path, name: String): VirtualFile {
    val path = dir.resolve(name)
    path.writeText("text")
    return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
  }
}

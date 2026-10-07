// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@TestApplication
@Timeout(30)
class DirectoryGroupResolverTest {
  private val rootFixture = tempPathFixture()
  private val outsideFixture = tempPathFixture()
  private val projectFixture = projectFixture()
  private val moduleFixture = projectFixture.moduleFixture(rootFixture, addPathToSourceRoot = true)

  @Test
  fun `resolution started on EDT with a background scope releases model access`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.Default) {
      val project = moduleFixture.get().project
      val root = VirtualFileManager.getInstance().findFileByNioPath(rootFixture.get())!!
      val file = edtWriteAction {
        root.createChildDirectory(this@DirectoryGroupResolverTest, "Group")
          .createChildData(this@DirectoryGroupResolverTest, "a.txt")
      }
      val scope = this
      val published = Channel<Unit>(Channel.UNLIMITED)
      val controller = withContext(Dispatchers.EDT) {
        TabGroupingController(project, scope, DirectoryGroupResolver(project)) { published.trySend(Unit) }
          .also { it.refresh(setOf(file), true) }
      }
      published.receive()
      withContext(Dispatchers.EDT) { assertEquals("Group", controller.group(file)?.name) }
    }
  }

  @Test
  fun `VFS moves and ancestor renames refresh retained membership`(): Unit = timeoutRunBlocking {
    val project = moduleFixture.get().project
    val root = VirtualFileManager.getInstance().findFileByNioPath(rootFixture.get())!!
    val outside = withContext(Dispatchers.IO) {
      VirtualFileManager.getInstance().refreshAndFindFileByNioPath(outsideFixture.get())!!
    }
    val (file, destination) = edtWriteAction {
      val first = root.createChildDirectory(this@DirectoryGroupResolverTest, "First")
      val second = root.createChildDirectory(this@DirectoryGroupResolverTest, "Second")
      first.createChildData(this@DirectoryGroupResolverTest, "a.txt") to second
    }
    val published = Channel<Unit>(Channel.UNLIMITED)
    val scope = this
    val controller = withContext(Dispatchers.EDT) {
      TabGroupingController(project, scope, DirectoryGroupResolver(project)) { published.trySend(Unit) }
        .also { it.refresh(setOf(file), true) }
    }

    suspend fun awaitName(expected: String?) {
      do {
        published.receive()
        val actual = withContext(Dispatchers.EDT) { controller.group(file)?.name }
      }
      while (actual != expected)
    }
    awaitName("First")
    edtWriteAction { file.move(this@DirectoryGroupResolverTest, destination) }
    awaitName("Second")
    edtWriteAction { destination.rename(this@DirectoryGroupResolverTest, "Renamed") }
    awaitName("Renamed")
    val parent = edtWriteAction { root.createChildDirectory(this@DirectoryGroupResolverTest, "Parent") }
    edtWriteAction { destination.move(this@DirectoryGroupResolverTest, parent) }
    var expected = "${parent.path}/Renamed"
    while (withContext(Dispatchers.EDT) { controller.group(file)?.key } != expected) published.receive()
    edtWriteAction { parent.rename(this@DirectoryGroupResolverTest, "MovedParent") }
    expected = "${parent.path}/Renamed"
    while (withContext(Dispatchers.EDT) { controller.group(file)?.key } != expected) published.receive()
    edtWriteAction { file.move(this@DirectoryGroupResolverTest, outside) }
    awaitName(null)
    val resolved = DirectoryGroupResolver(project).resolve(listOf(file))
    assertNull(resolved[file])
    withContext(Dispatchers.EDT) {
      controller.refresh(emptySet(), true)
      assertEquals(0, controller.retainedFileCount)
    }
  }
}

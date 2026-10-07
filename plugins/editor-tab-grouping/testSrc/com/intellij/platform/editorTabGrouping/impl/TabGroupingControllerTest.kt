// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.openapi.application.EDT
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@TestApplication
@Timeout(30)
class TabGroupingControllerTest {
  private class Request {
    val result = CompletableDeferred<Map<VirtualFile, TabGroup?>>()
  }

  private class Resolver(private val ignoreCancellation: Boolean = false) : DirectoryGroupResolver(null) {
    val requests = Channel<Request>(Channel.UNLIMITED)
    override suspend fun resolve(files: List<VirtualFile>): Map<VirtualFile, TabGroup?> {
      val request = Request()
      requests.send(request)
      return if (ignoreCancellation) withContext(NonCancellable) { request.result.await() } else request.result.await()
    }
  }

  @Test
  fun `disabled grouping never calls the resolver`(): Unit = timeoutRunBlocking {
    val resolver = Resolver()
    val scope = this
    withContext(Dispatchers.EDT) {
      val controller = TabGroupingController(null, scope, resolver) {}
      controller.refresh(setOf(LightVirtualFile("a")), false)
      assertEquals(0, controller.retainedFileCount)
    }
    assertFalse(resolver.requests.tryReceive().isSuccess)
  }

  @Test
  fun `closed files are removed from the cache`(): Unit = timeoutRunBlocking {
    val file = LightVirtualFile("a")
    val resolver = Resolver()
    val published = Channel<Unit>(Channel.UNLIMITED)
    val scope = this
    val controller = withContext(Dispatchers.EDT) {
      TabGroupingController(null, scope, resolver) { published.trySend(Unit) }.also { it.refresh(setOf(file), true) }
    }
    resolver.requests.receive().result.complete(mapOf(file to TabGroup("A", "A")))
    published.receive()
    withContext(Dispatchers.EDT) {
      assertEquals(1, controller.retainedFileCount)
      controller.refresh(emptySet(), true)
      assertEquals(0, controller.retainedFileCount)
      assertNull(controller.group(file))
    }
  }

  @Test
  fun `stale resolution cannot replace newer membership`(): Unit = timeoutRunBlocking {
    val file = LightVirtualFile("a")
    val resolver = Resolver(ignoreCancellation = true)
    val published = Channel<Unit>(Channel.UNLIMITED)
    val scope = this
    val controller = withContext(Dispatchers.EDT) {
      TabGroupingController(null, scope, resolver) { published.trySend(Unit) }.also { it.refresh(setOf(file), true) }
    }
    val old = resolver.requests.receive()
    withContext(Dispatchers.EDT) { controller.invalidate() }
    published.receive()
    val current = resolver.requests.receive()
    current.result.complete(mapOf(file to TabGroup("New", "new")))
    published.receive()
    old.result.complete(mapOf(file to TabGroup("Old", "old")))
    withContext(Dispatchers.EDT) { assertEquals("new", controller.group(file)?.key) }
  }

  @Test
  fun `disabling cancels pending resolution`(): Unit = timeoutRunBlocking {
    val file = LightVirtualFile("a")
    val resolver = Resolver()
    val scope = this
    val published = Channel<Unit>(Channel.UNLIMITED)
    val controller = withContext(Dispatchers.EDT) {
      TabGroupingController(null, scope, resolver) { published.trySend(Unit) }.also { it.refresh(setOf(file), true) }
    }
    val pending = resolver.requests.receive()
    withContext(Dispatchers.EDT) { controller.refresh(setOf(file), false) }
    pending.result.complete(mapOf(file to TabGroup("A", "A")))
    withContext(Dispatchers.EDT) {
      assertNull(controller.group(file))
      assertEquals(0, controller.retainedFileCount)
    }
    assertFalse(published.tryReceive().isSuccess)
  }

  @Test
  fun `resolution publication waits for drag and preview completion`(): Unit = timeoutRunBlocking {
    val file = LightVirtualFile("a")
    val resolver = Resolver()
    val scope = this
    val published = Channel<Unit>(Channel.UNLIMITED)
    val controller = withContext(Dispatchers.EDT) {
      TabGroupingController(null, scope, resolver) { published.trySend(Unit) }.also {
        it.dragStateChanged(true)
        it.previewStateChanged(true)
        it.refresh(setOf(file), true)
      }
    }
    resolver.requests.receive().result.complete(mapOf(file to TabGroup("A", "A")))
    withContext(Dispatchers.EDT) { controller.dragStateChanged(false) }
    assertFalse(published.tryReceive().isSuccess)
    withContext(Dispatchers.EDT) { controller.previewStateChanged(false) }
    published.receive()
    withContext(Dispatchers.EDT) { assertEquals("A", controller.group(file)?.key) }
  }

  @Test
  fun `disposal prevents late publication`(): Unit = timeoutRunBlocking {
    val owner = SupervisorJob(coroutineContext[kotlinx.coroutines.Job])
    val scope = CoroutineScope(coroutineContext + owner)
    val resolver = Resolver()
    val published = Channel<Unit>(Channel.UNLIMITED)
    withContext(Dispatchers.EDT) {
      TabGroupingController(null, scope, resolver) { published.trySend(Unit) }.refresh(setOf(LightVirtualFile("a")), true)
    }
    val request = resolver.requests.receive()
    owner.cancelAndJoin()
    request.result.complete(emptyMap())
    assertFalse(published.tryReceive().isSuccess)
  }
}

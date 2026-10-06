// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.progress.impl

import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.TaskHandle
import com.intellij.platform.ide.progress.TaskStatus
import com.intellij.platform.ide.progress.TaskStorage
import com.intellij.platform.ide.progress.suspender.TaskSuspension
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.common.waitUntil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@TestApplication
internal class TaskStorageTest {

  private val project by projectFixture()

  @Test
  fun `a background task cancelled through its status leaves no task`(): Unit = timeoutRunBlocking {
    val title = "test task ${UUID.randomUUID()}"
    val job = launchBackgroundTask(title, TaskCancellation.cancellable())
    val task = awaitTask(title)

    TaskStorage.getInstance().setStatus(task, TaskStatus.Canceled(TaskStatus.Source.USER))

    job.join()
    assertTrue(job.isCancelled, "The task coroutine must be cancelled")
    withTimeout(TIMEOUT) { task.removed.join() }
    assertNull(findTask(title), "The cancelled task must leave the storage")
  }

  @Test
  fun `a background task whose coroutine is cancelled leaves no task`(): Unit = timeoutRunBlocking {
    val title = "test task ${UUID.randomUUID()}"
    val job = launchBackgroundTask(title, TaskCancellation.nonCancellable())
    val task = awaitTask(title)

    job.cancel()

    withTimeout(TIMEOUT) { task.removed.join() }
    assertNull(findTask(title), "The cancelled task must leave the storage")
  }

  @Test
  fun `setStatus applies only the permitted changes`() {
    val storage = TaskStorage()
    val suspendable = storage.addTask(project, "suspendable", TaskCancellation.cancellable(), TaskSuspension.Suspendable("paused"),
                                      visibleInStatusBar = true)
    val fixed = storage.addTask(project, "fixed", TaskCancellation.nonCancellable(), TaskSuspension.NonSuspendable,
                                visibleInStatusBar = true)

    storage.setStatus(fixed, TaskStatus.Paused(null, TaskStatus.Source.USER))
    storage.setStatus(fixed, TaskStatus.Canceled(TaskStatus.Source.USER))
    assertEquals(TaskStatus.Running(TaskStatus.Source.SYSTEM), fixed.status.value)

    storage.setStatus(suspendable, TaskStatus.Running(TaskStatus.Source.USER))
    assertEquals(TaskStatus.Running(TaskStatus.Source.SYSTEM), suspendable.status.value, "A running task cannot resume")
    storage.setStatus(suspendable, TaskStatus.Paused("reason", TaskStatus.Source.USER))
    assertEquals(TaskStatus.Paused("reason", TaskStatus.Source.USER), suspendable.status.value)
    storage.setStatus(suspendable, TaskStatus.Canceled(TaskStatus.Source.USER))
    storage.setStatus(suspendable, TaskStatus.Running(TaskStatus.Source.USER))
    assertEquals(TaskStatus.Canceled(TaskStatus.Source.USER), suspendable.status.value, "A cancelled task cannot change its status")

    storage.removeTask(fixed)
    storage.removeTask(suspendable)
    assertTrue(storage.tasks.value.isEmpty())
  }

  @Test
  fun `collectEachTask runs for each task until the task is removed`(): Unit = timeoutRunBlocking {
    val storage = TaskStorage()
    val started = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    val stopped = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    for (title in listOf("existing", "later")) {
      started[title] = CompletableDeferred()
      stopped[title] = CompletableDeferred()
    }
    val existing = storage.addTask(project, "existing", TaskCancellation.nonCancellable(), TaskSuspension.NonSuspendable,
                                   visibleInStatusBar = true)

    val collector = storage.collectEachTask(this) { task ->
      try {
        started.getValue(task.info.title).complete(Unit)
        awaitCancellation()
      }
      finally {
        stopped.getValue(task.info.title).complete(Unit)
      }
    }
    try {
      started.getValue("existing").await()
      val later = storage.addTask(project, "later", TaskCancellation.nonCancellable(), TaskSuspension.NonSuspendable,
                                  visibleInStatusBar = true)
      started.getValue("later").await()

      storage.removeTask(existing)
      stopped.getValue("existing").await()
      assertFalse(stopped.getValue("later").isCompleted, "Only the coroutine of the removed task stops")

      storage.removeTask(later)
      stopped.getValue("later").await()
      assertFalse(collector.isCompleted, "A removed task does not stop the collector")
    }
    finally {
      collector.cancel()
    }
  }

  private fun CoroutineScope.launchBackgroundTask(title: String, cancellation: TaskCancellation): Job {
    return launch(start = CoroutineStart.UNDISPATCHED) {
      withBackgroundProgress(project, title, cancellation) {
        awaitCancellation()
      }
    }
  }

  private suspend fun awaitTask(title: String): TaskHandle {
    waitUntil("The task must appear in the storage", timeout = TIMEOUT) { findTask(title) != null }
    return checkNotNull(findTask(title))
  }

  private fun findTask(title: String): TaskHandle? {
    return TaskStorage.getInstance().tasks.value.values.firstOrNull { it.info.title == title }
  }
}

private val TIMEOUT = 10.seconds

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.progress.backend

import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.NlsContexts.ProgressText
import com.intellij.platform.ide.progress.TaskHandle
import com.intellij.platform.ide.progress.TaskId
import com.intellij.platform.ide.progress.TaskStatus
import com.intellij.platform.ide.progress.TaskStorage
import com.intellij.platform.ide.progress.rpc.RemoteTaskId
import com.intellij.platform.ide.progress.rpc.TaskInfoApi
import com.intellij.platform.ide.progress.rpc.TaskInfoEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val LOG = logger<BackendTaskInfoApi>()

/**
 * Serves the tasks of the backend [TaskStorage] to frontends over RPC (see [TaskInfoApi]).
 * Each subscription reads the same storage as the backend status bar.
 */
internal class BackendTaskInfoApi : TaskInfoApi {

  override suspend fun activeTasks(): Flow<TaskInfoEvent> = channelFlow {
    serviceAsync<TaskStorage>().collectEachTask(this) { task ->
      val taskId = RemoteTaskId(task.info.id.value)
      try {
        send(TaskInfoEvent.TaskAdded(
          taskId = taskId,
          projectId = task.info.projectId,
          title = task.info.title,
          cancellation = task.info.cancellation,
          suspension = task.suspension.value,
          status = task.status.value,
          visibleInStatusBar = task.info.visibleInStatusBar,
          ownerKind = task.info.ownerKind,
        ))

        // Each state flow sends its current value again. Thus no change between TaskAdded and the subscription is lost.
        // A state flow is conflated, so a slow subscriber gets only the latest progress.
        launch { task.progress.filterNotNull().collect { send(TaskInfoEvent.ProgressChanged(taskId, it)) } }
        launch { task.status.collect { send(TaskInfoEvent.StatusChanged(taskId, it)) } }
        launch { task.suspension.collect { send(TaskInfoEvent.SuspensionChanged(taskId, it)) } }

        awaitCancellation()
      }
      finally {
        withContext(NonCancellable) {
          // The subscriber can be gone already, with its channel closed. Then the removal does not matter.
          runCatching { send(TaskInfoEvent.TaskRemoved(taskId)) }
        }
      }
    }
  }

  override suspend fun cancelTask(taskId: RemoteTaskId) {
    val task = taskFor(taskId) ?: return
    TaskStorage.getInstance().setStatus(task, TaskStatus.Canceled(TaskStatus.Source.USER))
  }

  override suspend fun pauseTask(taskId: RemoteTaskId, reason: @ProgressText String?) {
    val task = taskFor(taskId) ?: return
    TaskStorage.getInstance().setStatus(task, TaskStatus.Paused(reason, TaskStatus.Source.USER))
  }

  override suspend fun resumeTask(taskId: RemoteTaskId) {
    val task = taskFor(taskId) ?: return
    TaskStorage.getInstance().setStatus(task, TaskStatus.Running(TaskStatus.Source.USER))
  }

  private suspend fun taskFor(taskId: RemoteTaskId): TaskHandle? {
    val task = serviceAsync<TaskStorage>().tasks.value[TaskId(taskId.value)]
    if (task == null) {
      LOG.info("No live task for $taskId, the command is dropped")
    }
    return task
  }
}

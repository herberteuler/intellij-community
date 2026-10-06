// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.progress

import com.intellij.openapi.util.NlsContexts.ProgressTitle
import com.intellij.platform.ide.progress.suspender.TaskSuspension
import com.intellij.platform.project.ProjectId
import com.intellij.platform.util.progress.ProgressState
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus

/**
 * Identifies a task in [TaskStorage]. The id is unique in the process.
 */
@ApiStatus.Internal
@JvmInline
value class TaskId(val value: Long)

/**
 * The fields of a task that do not change after [TaskStorage.addTask].
 *
 * @property projectId the id of the project of the task.
 *   It is `null` for the default project, and for an [ownerKind] other than [BackgroundTaskOwnerKind.PROJECT].
 *   It is a plain id. [TaskStorage] removes the task when the project is disposed.
 * @property ownerKind specifies the frames that show the task.
 * @property title the text that the UI shows for the task.
 * @property cancellation tells if the user can cancel the task.
 * @property visibleInStatusBar tells if the status bar shows the task in full.
 *   When it is `false`, the status bar shows only the number of tasks and lists the task in a popup.
 */
@ApiStatus.Internal
data class TaskInfo(
  val id: TaskId,
  val projectId: ProjectId?,
  val ownerKind: BackgroundTaskOwnerKind,
  val title: @ProgressTitle String,
  val cancellation: TaskCancellation,
  val visibleInStatusBar: Boolean,
)

/**
 * A task in [TaskStorage] and its mutable state.
 *
 * Use [TaskStorage.setStatus] to change [status], because it checks that the change is permitted.
 * A mirror of a task from another process writes [status] directly, because the other process checks the change.
 */
@ApiStatus.Internal
class TaskHandle internal constructor(
  val info: TaskInfo,
  status: TaskStatus,
  progress: ProgressState?,
  suspension: TaskSuspension,
) {
  /**
   * The current status of the task. See [TaskStatus] for the permitted changes.
   */
  val status: MutableStateFlow<TaskStatus> = MutableStateFlow(status)

  /**
   * The current progress of the task. The value is `null` until the task reports progress.
   */
  val progress: MutableStateFlow<ProgressState?> = MutableStateFlow(progress)

  /**
   * Tells if the user can pause and resume the task.
   */
  val suspension: MutableStateFlow<TaskSuspension> = MutableStateFlow(suspension)

  internal val removedJob: CompletableJob = Job()

  /**
   * Completes when [TaskStorage.removeTask] removes the task.
   */
  val removed: Job
    get() = removedJob

  override fun toString(): String = "Task(id=${info.id.value}, title=${info.title})"
}

/**
 * Runs [action] until [TaskStorage] removes the task.
 * When the task is removed, [action] is cancelled and this function returns normally.
 * When the task is removed already, [action] does not start.
 */
@ApiStatus.Internal
suspend fun TaskHandle.untilRemoved(action: suspend CoroutineScope.() -> Unit) {
  if (removed.isCompleted) return
  coroutineScope {
    val work = launch(block = action)
    val removalHandle = removed.invokeOnCompletion { work.cancel() }
    try {
      work.join()
    }
    finally {
      removalHandle.dispose()
    }
  }
}

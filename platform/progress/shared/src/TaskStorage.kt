// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.progress

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts.ProgressTitle
import com.intellij.platform.ide.progress.suspender.TaskSuspension
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import com.intellij.platform.project.projectId
import com.intellij.platform.util.progress.ProgressState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<TaskStorage>()

/**
 * Stores the tasks of this process in memory.
 *
 * Tasks are always local. Another process observes them over RPC
 * (see [com.intellij.platform.ide.progress.rpc.TaskInfoApi]).
 */
@ApiStatus.Internal
@Service(Service.Level.APP)
class TaskStorage {
  private val lastId = AtomicLong()
  private val _tasks = MutableStateFlow<Map<TaskId, TaskHandle>>(emptyMap())

  // The ids of the projects whose disposal removes their tasks. See [removeTasksOnDispose].
  private val projectsWithTasks = ConcurrentHashMap.newKeySet<ProjectId>()

  /**
   * The tasks of this process, in the order of [addTask] calls.
   */
  val tasks: StateFlow<Map<TaskId, TaskHandle>> = _tasks.asStateFlow()

  /**
   * Adds a new task to the storage.
   * The storage removes the task when the project of [owner] is disposed.
   * When that project is disposed already, the returned task is removed already.
   *
   * @param owner the frames in which the UI shows the task.
   * @param title the title of the task.
   * @param cancellation tells if the user can cancel the task.
   * @param suspendable tells if the user can pause the task.
   * @param visibleInStatusBar tells if the status bar shows the task in full, or only in the number of tasks and the popup.
   * @param status the initial status. A new local task is [TaskStatus.Running].
   * @param progress the initial progress.
   */
  fun addTask(
    owner: BackgroundTaskOwner,
    title: @ProgressTitle String,
    cancellation: TaskCancellation,
    suspendable: TaskSuspension,
    visibleInStatusBar: Boolean,
    status: TaskStatus = TaskStatus.Running(source = TaskStatus.Source.SYSTEM),
    progress: ProgressState? = null,
  ): TaskHandle {
    val project = (owner as? ProjectBackgroundTaskOwner)?.project
    val info = TaskInfo(
      id = TaskId(lastId.incrementAndGet()),
      projectId = if (project != null && !project.isDefault) project.projectId() else null,
      ownerKind = owner.kind,
      title = title,
      cancellation = cancellation,
      visibleInStatusBar = visibleInStatusBar,
    )
    val task = TaskHandle(info, status, progress, suspendable)
    _tasks.update { it + (info.id to task) }
    if (project != null && info.projectId != null) {
      removeTasksOnDispose(project, info.projectId)
    }
    return task
  }

  /**
   * Makes the disposal of [project] remove its tasks.
   * A project can go without `projectClosed`, for example when it never opens. Thus the storage waits for the disposal.
   * When [project] is disposed already, removes its tasks now.
   */
  private fun removeTasksOnDispose(project: Project, projectId: ProjectId) {
    if (!projectsWithTasks.add(projectId)) return
    val registered = Disposer.tryRegister(project) {
      projectsWithTasks.remove(projectId)
      removeTasksOfDisposedProject(project, projectId)
    }
    if (!registered) {
      projectsWithTasks.remove(projectId)
      removeTasksOfDisposedProject(project, projectId)
    }
  }

  /**
   * Removes the tasks of [project] with the id [projectId].
   *
   * The disposal of [project] runs this before `ProjectImpl.dispose` unregisters its ids.
   * Thus a task with an alias id of [project] resolves to [project] and goes too.
   * A task whose id resolves to no project goes as well.
   */
  private fun removeTasksOfDisposedProject(project: Project, projectId: ProjectId) {
    removeTasks { info ->
      val taskProjectId = info.projectId ?: return@removeTasks false
      if (taskProjectId == projectId) return@removeTasks true
      val owner = taskProjectId.findProjectOrNull()
      owner == null || owner === project
    }
  }

  /**
   * Adds a new task that the frame of [project] shows. See the [addTask] overload with a [BackgroundTaskOwner].
   */
  fun addTask(
    project: Project,
    title: @ProgressTitle String,
    cancellation: TaskCancellation,
    suspendable: TaskSuspension,
    visibleInStatusBar: Boolean,
    status: TaskStatus = TaskStatus.Running(source = TaskStatus.Source.SYSTEM),
    progress: ProgressState? = null,
  ): TaskHandle {
    return addTask(BackgroundTaskOwner.project(project), title, cancellation, suspendable, visibleInStatusBar, status, progress)
  }

  /**
   * Removes a task from the storage and completes [TaskHandle.removed].
   * A second call for the same task does nothing.
   * This function does not cancel a running task. To cancel a task, use [setStatus] with [TaskStatus.Canceled].
   */
  fun removeTask(task: TaskHandle) {
    _tasks.update { it - task.info.id }
    task.removedJob.complete()
  }

  /**
   * Removes each task for which [predicate] returns `true`.
   */
  fun removeTasks(predicate: (TaskInfo) -> Boolean) {
    for (task in tasks.value.values) {
      if (predicate(task.info)) {
        removeTask(task)
      }
    }
  }

  /**
   * Changes the status of [task] to [newStatus] when the change is permitted:
   * - A paused task can resume.
   * - A running task can pause when it is suspendable.
   * - A task can be cancelled when it is cancellable and not cancelled yet.
   *
   * Does nothing when the change is not permitted or the task is removed.
   */
  fun setStatus(task: TaskHandle, newStatus: TaskStatus) {
    if (task.removed.isCompleted) return
    task.status.update { current ->
      if (canChangeStatus(task, from = current, to = newStatus)) {
        LOG.trace { "Changing the status of $task from $current to $newStatus" }
        newStatus
      }
      else {
        LOG.trace {
          "The status of $task cannot change to $newStatus. " +
          "Current status=$current, " +
          "suspendable=${task.suspension.value is TaskSuspension.Suspendable}, " +
          "cancellable=${task.info.cancellation is TaskCancellation.Cancellable}"
        }
        current
      }
    }
  }

  /**
   * Runs [action] for each task, also for each task that is added later.
   * Each [action] runs in its own child coroutine, which is cancelled when its task is removed.
   * A failure of one [action] does not cancel the others.
   *
   * @return the job in [scope] that runs the child coroutines. Cancel it to stop all of them.
   */
  fun collectEachTask(scope: CoroutineScope, action: suspend CoroutineScope.(TaskHandle) -> Unit): Job {
    return scope.launch {
      supervisorScope {
        val started = HashSet<TaskId>()
        tasks.collect { current ->
          started.retainAll(current.keys)
          for ((id, task) in current) {
            if (started.add(id)) {
              launch { task.untilRemoved { action(task) } }
            }
          }
        }
      }
    }
  }

  private fun canChangeStatus(task: TaskHandle, from: TaskStatus, to: TaskStatus): Boolean {
    return when (to) {
      is TaskStatus.Running -> from is TaskStatus.Paused
      is TaskStatus.Paused -> from is TaskStatus.Running && task.suspension.value is TaskSuspension.Suspendable
      is TaskStatus.Canceled -> from !is TaskStatus.Canceled && task.info.cancellation is TaskCancellation.Cancellable
    }
  }

  companion object {
    @JvmStatic
    fun getInstance(): TaskStorage = service()
  }
}

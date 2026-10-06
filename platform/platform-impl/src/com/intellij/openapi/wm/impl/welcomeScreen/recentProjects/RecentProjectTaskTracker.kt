// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.wm.impl.welcomeScreen.recentProjects

import com.intellij.ide.RecentProjectsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts.ProgressText
import com.intellij.openapi.util.NlsContexts.ProgressTitle
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.TaskHandle
import com.intellij.platform.ide.progress.TaskId
import com.intellij.platform.ide.progress.TaskStatus
import com.intellij.platform.ide.progress.TaskStorage
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.projectIdOrNull
import com.intellij.util.text.nullize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Follows the background tasks of the recent projects the welcome screen holds, so that their rows can show what they are doing.
 *
 * [RecentProjectsService] owns the projects and tells this which ones to follow.
 */
@Service(Service.Level.APP)
internal class RecentProjectTaskTracker(private val coroutineScope: CoroutineScope) {
  // Every task running on any followed project, by task id. Flat rather than grouped by project: the id is unique on its own, a
  // held project runs a handful of tasks at most, and the value then needs no mutable state of its own.
  private val runningTasks = ConcurrentHashMap<TaskId, RunningTask>()

  // Orders the tasks of a project by when they started, so the row keeps showing the one it picked while an equal one runs beside it.
  private val startedTasks = AtomicLong()

  /**
   * Follows the tasks of [project], held for the recent project at [projectPath], until the project is disposed.
   *
   * Call it once, when the project is held: the observer belongs to that project, and disposing it stops the observer and drops its tasks,
   * so no caller has to remember to.
   */
  fun follow(projectPath: Path, project: Project) {
    if (project.isDisposed) return
    val projectId = project.projectIdOrNull() ?: return
    val followTaskJob = followTasksJob(projectId, projectPath)
    Disposer.register(project) {
      followTaskJob.cancel()
      runningTasks.values.removeIf { it.projectPath == projectPath }
    }
  }

  private fun followTasksJob(projectId: ProjectId, projectPath: Path): Job =
    TaskStorage.getInstance().collectEachTask(coroutineScope) { task ->
      if (task.info.projectId == projectId) {
        follow(task, projectPath)
      }
    }

  // Records [task] as running on the project at [projectPath], and follows its progress until the task is removed.
  private suspend fun follow(task: TaskHandle, projectPath: Path) {
    val info = task.info
    runningTasks[info.id] = RunningTask(
      projectPath = projectPath,
      task = task,
      prominent = info.visibleInStatusBar,
      startOrder = startedTasks.incrementAndGet(),
      progress = RecentProjectTaskProgress(info.title,
                                           text = null,
                                           fraction = null,
                                           cancellable = info.cancellation is TaskCancellation.Cancellable),
    )
    try {
      notifyRowsChanged()
      followTaskProgress(task)
    }
    finally {
      runningTasks.remove(info.id)
      // This coroutine is cancelled already, so the rebuild runs in the service scope.
      coroutineScope.launch { notifyRowsChanged() }
    }
  }

  /**
   * Rebuilds the recent projects tree, which is what starts and stops the welcome screen's progress bar repaint loop.
   *
   * Only a task starting or finishing does this. While one runs, that loop repaints the row on its own, so a progress tick costs no
   * rebuild.
   */
  private suspend fun notifyRowsChanged() = withContext(Dispatchers.EDT) {
    ApplicationManager.getApplication().messageBus.syncPublisher(RecentProjectsManager.RECENT_PROJECTS_CHANGE_TOPIC).change()
  }

  /** The background task the row of [item] shows, or null when its project runs none. */
  fun runningTask(item: RecentProjectItem): RecentProjectTaskProgress? = shownTask(item)?.progress

  /**
   * Cancels the background task running on the recent project of [item].
   *
   * Does nothing when the project runs none, or when the task cannot be cancelled: the row offers this only for a task whose
   * [RecentProjectTaskProgress.cancellable] is set, and [TaskStorage] refuses it for any other.
   */
  fun cancelRunningTask(item: RecentProjectItem) {
    // The same task the row shows, so the button cancels what it sits next to.
    val task = shownTask(item) ?: return
    TaskStorage.getInstance().setStatus(task.task, TaskStatus.Canceled(TaskStatus.Source.USER))
  }

  /**
   * The one task of several that the row of [item] shows, since the row has a single line for it.
   *
   * Ordered by how likely a task is to be the one the user just asked for: a task the platform keeps out of the status bar yields to one
   * meant to be seen, which is what tells indexing and the like apart from a version control operation, and a task that cannot be
   * cancelled yields to one that can. Ties keep the task that started first, so the row does not swap the line it shows while both run.
   */
  private fun shownTask(item: RecentProjectItem): RunningTask? {
    val projectPath = item.projectNioPath() ?: return null
    return runningTasks.values
      .filter { it.projectPath == projectPath }
      .minWithOrNull(compareBy({ !it.prominent }, { !it.progress.cancellable }, { it.startOrder }))
  }

  // Keeps the row's progress text and fraction current. Ends with the task: the storage removes it when it finishes.
  private suspend fun followTaskProgress(task: TaskHandle) {
    task.progress.filterNotNull().collect { state ->
      runningTasks.computeIfPresent(task.info.id) { _, running ->
        running.copy(progress = running.progress.copy(text = state.text?.nullize(nullizeSpaces = true), fraction = state.fraction))
      }
    }
  }

  companion object {
    fun getInstance(): RecentProjectTaskTracker = service()
  }
}

/** A task of a followed project: the handle, which cancelling it needs, the ordering inputs, and the snapshot the row is painted from. */
private data class RunningTask(
  val projectPath: Path,
  val task: TaskHandle,
  /** Whether the platform means the task to be seen, or keeps it out of the status bar as background noise. */
  val prominent: Boolean,
  val startOrder: Long,
  val progress: RecentProjectTaskProgress,
)

internal data class RecentProjectTaskProgress(
  val title: @ProgressTitle String,
  val text: @ProgressText String?,
  val fraction: Double?,
  val cancellable: Boolean,
)

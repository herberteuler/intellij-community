// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns membership resolution and publishes results on EDT. */
internal class TabGroupingController(
  project: Project?,
  private val scope: CoroutineScope,
  private val resolver: DirectoryGroupResolver,
  private val publish: () -> Unit,
) {
  private val groups = HashMap<VirtualFile, TabGroup?>()
  private var files: Set<VirtualFile> = emptySet()
  private var enabled = false
  private var generation = 0L
  private var job: Job? = null
  private var dragActive = false
  private var previewActive = false
  private var pendingPublication = false

  val isDragging: Boolean get() = dragActive || previewActive
  internal val retainedFileCount: Int get() = groups.size

  init {
    project?.messageBus?.connect(scope)?.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
      override fun rootsChanged(event: ModuleRootEvent) {
        scheduleInvalidation(null)
      }
    })
    ApplicationManager.getApplication().messageBus.connect(scope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        val paths = events.mapNotNull { event ->
          when (event) {
            is VFileMoveEvent, is VFileDeleteEvent -> event.path
            is VFilePropertyChangeEvent -> event.path.takeIf { event.propertyName == VirtualFile.PROP_NAME }
            else -> null
          }
        }
        if (paths.isNotEmpty()) scheduleInvalidation(paths)
      }
    })
  }

  private fun scheduleInvalidation(paths: List<String>?) {
    scope.launch(Dispatchers.EDT + ModalityState.defaultModalityState().asContextElement()) {
      // Paths can change before the callback runs. Invalidate retained descendants using both saved and current paths.
      invalidate(paths)
    }
  }

  private val resolvedPaths = HashMap<VirtualFile, String>()

  fun invalidate(paths: List<String>? = null) {
    val affected = if (paths == null) files
    else files.filter { file ->
      val previous = resolvedPaths[file]
      paths.any { path ->
        file.path == path || file.path.startsWith("$path/") ||
        previous == path || previous?.startsWith("$path/") == true
      }
    }
    if (affected.isEmpty()) return
    for (file in affected) {
      groups.remove(file)
      resolvedPaths.remove(file)
    }
    refresh(files, enabled, force = true)
    publishWhenReady()
  }

  fun isResolved(file: VirtualFile): Boolean = enabled && groups.containsKey(file)

  @org.jetbrains.annotations.TestOnly
  suspend fun awaitResolution() {
    withContext(Dispatchers.EDT) { job }?.join()
  }

  fun group(file: VirtualFile): TabGroup? = if (enabled) groups[file] else null

  fun refresh(currentFiles: Set<VirtualFile>, configured: Boolean, force: Boolean = false) {
    if (!force && files == currentFiles && enabled == configured) return
    files = currentFiles.toSet()
    enabled = configured
    generation++
    job?.cancel()
    groups.keys.retainAll(if (enabled) files else emptySet())
    resolvedPaths.keys.retainAll(if (enabled) files else emptySet())
    if (enabled) for (file in files) resolvedPaths.putIfAbsent(file, file.path)
    if (!enabled || files.isEmpty()) return
    val missing = files.filter { !groups.containsKey(it) }
    if (missing.isEmpty()) return
    val expectedGeneration = generation
    val modality = ModalityState.defaultModalityState()
    job = scope.launch(Dispatchers.EDT + modality.asContextElement(), start = CoroutineStart.UNDISPATCHED) {
      val resolved = resolver.resolve(missing)
      withContext(Dispatchers.EDT + modality.asContextElement()) {
        if (!isActive || generation != expectedGeneration) return@withContext
        for (file in missing) {
          groups[file] = resolved[file]
          resolvedPaths[file] = file.path
        }
        publishWhenReady()
      }
    }
  }

  fun dragStateChanged(active: Boolean) {
    dragActive = active
    flushPublication()
  }

  fun previewStateChanged(active: Boolean) {
    previewActive = active
    flushPublication()
  }

  private fun publishWhenReady() {
    if (isDragging) pendingPublication = true else publish()
  }

  private fun flushPublication() {
    if (!isDragging && pendingPublication) {
      pendingPublication = false
      publish()
    }
  }
}

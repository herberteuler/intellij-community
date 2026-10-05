// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.workspaceModel.ide.impl.jsonDump

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.backend.workspace.WorkspaceModelChangeListener
import com.intellij.platform.backend.workspace.WorkspaceModelTopics
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.storage.VersionedStorageChange
import com.intellij.util.PathUtil
import com.intellij.workspaceModel.ide.impl.WorkspaceModelImpl
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.PROJECT)
internal class DumpWorkspaceEntitiesWsmChangeListener(val project: Project, val scope: CoroutineScope) {
  init {
    project.messageBus.connect(project).subscribe(WorkspaceModelTopics.CHANGED, object : WorkspaceModelChangeListener {
      override fun changed(event: VersionedStorageChange) {
        if (!Registry.`is`("ide.workspace.model.dump.on.every.wsm.update")){
          return
        }

        val version = (project.workspaceModel as WorkspaceModelImpl).entityStorage.version
        val fileName = "${System.currentTimeMillis()}-workspace-model-dump-version-${PathUtil.suggestFileName(project.name)}.${project.locationHash}-$version"
        project.service<WorkspaceModelJsonDumpService>()
          .dumpWorkspaceEntitiesToLogFileAsJson(fileName, event.storageAfter, openFileInEditor = false)
      }
    })
  }
}

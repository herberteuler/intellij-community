// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.dev.core

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.workspaceModel.ide.impl.jsonDump.WorkspaceModelJsonDumpService

internal class DumpWorkspaceEntitiesToClipboardAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.project?.service<WorkspaceModelJsonDumpService>()?.dumpWorkspaceEntitiesToClipboardAsJson()
  }
}

internal class DumpWorkspaceEntitiesToLogAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.project?.service<WorkspaceModelJsonDumpService>()?.dumpWorkspaceEntitiesToLogAsJson()
  }
}

internal class DumpWorkspaceEntitiesToLogFileAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    e.project?.service<WorkspaceModelJsonDumpService>()?.dumpWorkspaceEntitiesToLogFileAsJson()
  }
}

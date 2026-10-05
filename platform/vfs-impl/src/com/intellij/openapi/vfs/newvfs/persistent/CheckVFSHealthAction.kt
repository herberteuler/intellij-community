// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.newvfs.persistent

import com.intellij.idea.ActionsBundle
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withModalProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class CheckVFSHealthAction : DumbAwareAction() {
  override fun actionPerformed(e: AnActionEvent) {

    e.coroutineScope.launch(Dispatchers.IO) {
      //Use modal dialog to prevent calling more than once:
      withModalProgress(ModalTaskOwner.guess(),
                        ActionsBundle.message("action.CheckVfsSanity.progress"),
                        TaskCancellation.nonCancellable()) {
        val checker = VFSHealthChecker(FSRecords.getInstance(), FSRecords.LOG)
        checker.checkHealth(checkForOrphanRecords = true)
      }
    }
  }

  override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
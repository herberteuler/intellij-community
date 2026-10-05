// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.dev.core

import com.intellij.CommonBundle
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.newvfs.persistent.FSRecords
import com.intellij.openapi.vfs.newvfs.persistent.VFSDefragmentationCheckerStopper

/**
 * Set VFS flag to run defragmentation on next IDE startup.
 * (Currently, 'defragmentation' is implemented as just 'rebuild VFS and Indexes from scratch')
 */
internal class RequestCachesDefragmentationAction : AnAction(), DumbAware {
  override fun actionPerformed(e: AnActionEvent) {
    val app = ApplicationManagerEx.getApplicationEx()

    val defragmentNowText = if (app.isRestartCapable) DevCoreBundle.message("vfs.defragmentation.dialog.action.restart")
    else DevCoreBundle.message("vfs.defragmentation.dialog.action.shutdown")

    val answer = Messages.showYesNoCancelDialog(
      e.project,
      DevCoreBundle.message("vfs.defragmentation.dialog.message"),
      DevCoreBundle.message("vfs.defragmentation.dialog.title"),
      defragmentNowText,
      DevCoreBundle.message("vfs.defragmentation.dialog.action.later"),
      CommonBundle.getCancelButtonText(),
      Messages.getQuestionIcon()
    )
    if (answer == Messages.CANCEL) {
      return
    }

    FSRecords.getInstance().scheduleDefragmentation()
    VFSDefragmentationCheckerStopper.stopChecking()

    if (answer == Messages.YES) {
      app.restart(true)
    }
  }
}
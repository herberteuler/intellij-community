// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.analysisignore

import com.intellij.lang.LangBundle
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import java.util.function.Function
import javax.swing.JComponent

/**
 * Tells the user that the default lines were written into a new `.analysisignore` file. The user edits or removes them in the file. A file
 * without these lines turns off the defaults of its project root.
 */
internal class AnalysisIgnoreDefaultLinesNotificationProvider : EditorNotificationProvider, DumbAware {

  override fun collectNotificationData(project: Project, file: VirtualFile): Function<in FileEditor, out JComponent?>? {
    if (!Registry.`is`(ANALYSIS_IGNORE_ENABLED_KEY, true)) return null
    if (!file.isAnalysisIgnoreFile()) return null
    val service = AnalysisIgnoreService.getInstance(project)
    if (service.defaultLinesAddedTo(file) == null) return null

    return Function { fileEditor ->
      EditorNotificationPanel(fileEditor, EditorNotificationPanel.Status.Info).apply {
        text = LangBundle.message("analysis.ignore.banner.default.lines.text")
        setCloseAction { service.forgetDefaultLinesOf(file) }
      }
    }
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.ErrorTreeView
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls

/**
 * Creates the error tree view of the Messages tool window.
 * The module `intellij.platform.ide.errorTreeView` registers the implementation as an application service.
 * Without the module, [getInstanceOrNull] returns `null`.
 */
@ApiStatus.Internal
interface ErrorTreeViewFactory {
  companion object {
    @JvmStatic
    fun getInstanceOrNull(): ErrorTreeViewFactory? = ApplicationManager.getApplication().getService(ErrorTreeViewFactory::class.java)
  }

  /**
   * @param helpId the help topic of the view
   * @param canHideWarnings `true` to show the action that hides the warnings
   */
  fun createErrorTreeView(project: Project, helpId: @NonNls String, canHideWarnings: Boolean): ErrorTreeView
}

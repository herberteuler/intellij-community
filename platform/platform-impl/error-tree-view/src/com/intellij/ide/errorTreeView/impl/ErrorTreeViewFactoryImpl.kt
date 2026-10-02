// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.errorTreeView.impl

import com.intellij.ide.ErrorTreeViewFactory
import com.intellij.ide.errorTreeView.NewErrorTreeViewPanel
import com.intellij.openapi.project.Project
import com.intellij.util.ui.ErrorTreeView

internal class ErrorTreeViewFactoryImpl : ErrorTreeViewFactory {
  override fun createErrorTreeView(project: Project, helpId: String, canHideWarnings: Boolean): ErrorTreeView {
    return if (canHideWarnings) NewErrorTreeViewPanel(project, helpId) else NoHideWarningsErrorTreeViewPanel(project, helpId)
  }
}

// the constructor of NewErrorTreeViewPanel calls canHideWarnings(), so the result must not depend on a field of the subclass
private class NoHideWarningsErrorTreeViewPanel(project: Project, helpId: String) : NewErrorTreeViewPanel(project, helpId) {
  override fun canHideWarnings(): Boolean = false
}

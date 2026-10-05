// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.dev.core

import com.intellij.ide.plugins.org.configurePluginFiltersToTrustOnlyJetBrains
import com.intellij.ide.plugins.org.resetPluginFilters
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction

internal abstract class PluginManagerFiltersConfigureDebugActionBase : DumbAwareAction() {
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = ApplicationManager.getApplication().isInternal
  }

  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.BGT
  }
}

internal class PluginManagerFiltersConfigureTrustOnlyJetBrainsDebugAction : PluginManagerFiltersConfigureDebugActionBase() {
  override fun actionPerformed(e: AnActionEvent) {
    configurePluginFiltersToTrustOnlyJetBrains()
  }
}

internal class PluginManagerFiltersConfigureResetTrustDebugAction : PluginManagerFiltersConfigureDebugActionBase() {
  override fun actionPerformed(e: AnActionEvent) {
    resetPluginFilters()
  }
}

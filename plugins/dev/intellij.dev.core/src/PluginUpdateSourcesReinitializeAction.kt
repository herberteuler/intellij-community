// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.dev.core

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.updateSettings.impl.PluginUpdateSourceInitializer
import com.intellij.openapi.updateSettings.impl.PluginUpdateSourceService
import com.intellij.openapi.updateSettings.impl.resetPluginUpdateSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PluginUpdateSourcesReinitializeAction : DumbAwareAction() {
  init {
    getTemplatePresentation().isApplicationScope = true
  }

  override fun getActionUpdateThread(): ActionUpdateThread {
    return ActionUpdateThread.BGT
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = PluginUpdateSourceService.isFunctionalitySupported()
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project
    if (!PluginUpdateSourceService.isFunctionalitySupported()) return
    e.coroutineScope.launch(Dispatchers.IO) {
      resetPluginUpdateSources()
      val result = PluginUpdateSourceInitializer.enforceInitialization()
      withContext(Dispatchers.EDT) {
        val message: String
        val notificationType: NotificationType
        when (result) {
          is PluginUpdateSourceInitializer.Result.Success -> {
            message = DevCoreBundle.message(
              "notification.content.plugin.update.sources.are.reset.and.initialized",
              result.loadedPluginsWithoutUpdateSource,
            )
            notificationType = NotificationType.INFORMATION
          }
          is PluginUpdateSourceInitializer.Result.Failure -> {
            message = result.errorMessage?.let { errorMessage ->
              DevCoreBundle.message("notification.content.plugin.update.sources.are.reset.but.not.initialized.with.error", errorMessage)
            } ?: DevCoreBundle.message("notification.content.plugin.update.sources.are.reset.but.not.initialized")
            notificationType = NotificationType.WARNING
          }
        }
        NotificationGroupManager.getInstance()
          .getNotificationGroup("Plugin Update Sources Reset")
          .createNotification(message, notificationType)
          .notify(project)
      }
    }
  }

}

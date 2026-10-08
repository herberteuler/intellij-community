// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.ide.IdeBundle
import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.ide.plugins.PluginManagementPolicy
import com.intellij.ide.util.PropertiesComponent
import com.intellij.ide.util.RunOnceUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.TestOnly
import org.jetbrains.annotations.VisibleForTesting
import java.awt.BorderLayout
import javax.swing.JPanel

private const val PLUGIN_AUTO_UPDATE_ENABLER_REGISTRY_PROPERTY: String = "plugin.auto.update.enabler.enabled"
private const val PLUGIN_AUTO_UPDATE_CHECKED_PROPERTY: String = "plugin.auto.update.checked.for.plugin.update.source"
private const val NOTIFY_ONCE_AFTER_PLUGIN_INSTALLATION_PROPERTY = "plugin.auto.update.notify.once.after.plugin.installation"
private const val SHOW_BANNER_ONCE_AFTER_PROPERTY = "plugin.auto.update.show.banner.once"

internal object PluginAutoUpdateEnabler {
  fun enablePluginAutoUpdateIfNeeded() {
    if (!Registry.`is`(PLUGIN_AUTO_UPDATE_ENABLER_REGISTRY_PROPERTY, false)) return
    if (!PluginManagementPolicy.getInstance().isPluginAutoUpdateAllowed()) return
    if (!PluginUpdateSourceService.isPluginUpdateFilteredAgainstPluginUpdateSource()) return
    RunOnceUtil.runOnceForApp(PLUGIN_AUTO_UPDATE_CHECKED_PROPERTY) {
      if (!UpdateSettings.getInstance().isPluginsAutoUpdateEnabled) {
        UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = true
        service<PluginAutoUpdateService>().onSettingsChanged()
        Feature.entries.forEach { it.enable() }
      }
    }
  }

  internal enum class Feature(private val propertyName: String) {
    Notification(NOTIFY_ONCE_AFTER_PLUGIN_INSTALLATION_PROPERTY),
    Banner(SHOW_BANNER_ONCE_AFTER_PROPERTY);

    @VisibleForTesting
    fun enable() {
      PropertiesComponent.getInstance().setValue(propertyName, true)
    }

    @VisibleForTesting
    fun disable() {
      PropertiesComponent.getInstance().unsetValue(propertyName)
    }

    @VisibleForTesting
    fun isEnabled(): Boolean {
      return PropertiesComponent.getInstance().getBoolean(propertyName, false)
    }
  }

  fun notifyAboutEnabledAutoUpdateIfNeeded(project: Project?): Boolean {
    return onceWithFeatureAndAutoUpdate(Feature.Notification) {
      val notification = UpdateCheckerFacade.getInstance().getNotificationGroupForPluginUpdateResults()
        .createNotification(IdeBundle.message("updates.plugins.autoupdate.enabled.notification.title"),
                            IdeBundle.message("updates.plugins.autoupdate.enabled.notification.content"),
                            NotificationType.INFORMATION)
        .addAction(object : NotificationAction(IdeBundle.message("updates.plugins.autoupdate.enabled.notification.got.it.action")) {
          override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            notification.expire()
          }
        }).addAction(object : NotificationAction(IdeBundle.message("updates.plugins.autoupdate.enabled.notification.settings.action")) {
          override fun actionPerformed(e: AnActionEvent, notification: Notification) {
            notification.expire()
            ShowSettingsUtilImpl.showSettingsDialog(
              project,
              "preferences.updates",
              IdeBundle.message("updates.plugins.autoupdate.settings.checkbox"),
            )
          }
        })
      notification.notify(project)
      true
    } != null
  }

  fun createBannerIfNeeded(): JPanel? {
    return onceWithFeatureAndAutoUpdate(Feature.Banner) {
      val htmlChunk = HtmlChunk.fragment(
        HtmlChunk.text(IdeBundle.message("updates.plugins.autoupdate.enabled.notification.title")).bold(),
        HtmlChunk.br(),
        HtmlChunk.text(IdeBundle.message("updates.plugins.autoupdate.enabled.banner.content")),
      )
      val banner = InlineBanner(htmlChunk.toString(), EditorNotificationPanel.Status.Info)
      JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(10)
        add(banner, BorderLayout.CENTER)
      }
    }
  }

  private fun <T> onceWithFeatureAndAutoUpdate(feature: Feature, provider: () -> T): T? {
    if (!feature.isEnabled()) return null
    try {
      if (!UpdateSettings.getInstance().isPluginsAutoUpdateEnabled) return null
      return provider()
    }
    finally {
      feature.disable()
    }
  }

  @TestOnly
  internal fun enablePluginAutoUpdateEnabler(disposable: Disposable) {
    Registry.get(PLUGIN_AUTO_UPDATE_ENABLER_REGISTRY_PROPERTY).setValue(true, disposable)
  }
}
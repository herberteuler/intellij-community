// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.updateSettings.impl

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagementPolicy
import com.intellij.ide.util.PropertiesComponent.getInstance
import com.intellij.openapi.Disposable
import com.intellij.openapi.updateSettings.impl.PluginAutoUpdateEnabler.Feature.Banner
import com.intellij.openapi.updateSettings.impl.PluginAutoUpdateEnabler.Feature.Notification
import com.intellij.openapi.util.registry.Registry
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.RegistryKey
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@RegistryKey(key = "platform.enable.plugin.update.source.feature", value = "true")
@RegistryKey(key = "platform.disable.plugin.update.sources.ui.and.filtering.for.internal.users", value = "false")
@RegistryKey(key = "plugin.auto.update.enabler.enabled", value = "false")
@TestApplication
class PluginAutoUpdateEnablerTest {

  @TestDisposable
  private lateinit var disposable: Disposable

  private val project = projectFixture()
  private var originalAutoUpdateEnabled = false

  @BeforeEach
  fun setUp() {
    originalAutoUpdateEnabled = UpdateSettings.getInstance().isPluginsAutoUpdateEnabled
    resetAutoUpdateState()
  }

  @AfterEach
  fun tearDown() {
    resetAutoUpdateState()
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = originalAutoUpdateEnabled
  }

  @Test
  fun `activity enables auto-update when all gates pass`(): Unit = timeoutRunBlocking {
    enableStartupActivity()
    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()
    assertTrue(UpdateSettings.getInstance().isPluginsAutoUpdateEnabled)
    assertNotificationShownOnRequest(true)
    assertBannerShownOnRequest(true)
  }

  @Test
  fun `activity stays idle when its registry key is off`(): Unit = timeoutRunBlocking {
    enablePluginUpdateSourceFiltering()
    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()
    assertAutoUpdateIsOff()
  }

  @Test
  fun `activity stays idle when the policy forbids auto-update`(): Unit = timeoutRunBlocking {
    enableStartupActivity()
    maskPluginManagementPolicyForbiddingAutoUpdate()
    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()
    assertAutoUpdateIsOff()
  }

  @Test
  fun `activity stays idle when plugin update-source filtering is off`(): Unit = timeoutRunBlocking {
    PluginAutoUpdateEnabler.enablePluginAutoUpdateEnabler(disposable)
    Registry.get(PLUGIN_UPDATE_SOURCE_FILTER_REGISTRY_PROPERTY).setValue(false, disposable)
    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()
    assertAutoUpdateIsOff()
  }

  @Test
  fun `activity does not show follow-up UI when auto-update is already on`(): Unit = timeoutRunBlocking {
    enableStartupActivity()
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = true

    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()

    assertTrue(UpdateSettings.getInstance().isPluginsAutoUpdateEnabled)
    assertNotificationShownOnRequest(false)
    assertBannerShownOnRequest(false)
  }

  @Test
  fun `activity enables auto-update only once`(): Unit = timeoutRunBlocking {
    enableStartupActivity()
    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()
    PluginAutoUpdateEnabler.Feature.entries.forEach { it.disable() }
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = false

    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()
    assertAutoUpdateIsOff()
  }

  @Test
  fun `a blocked activity does not consume the run-once key`(): Unit = timeoutRunBlocking {
    enablePluginUpdateSourceFiltering()
    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()
    assertAutoUpdateIsOff()

    PluginAutoUpdateEnabler.enablePluginAutoUpdateEnabler(disposable)
    PluginAutoUpdateEnabler.enablePluginAutoUpdateIfNeeded()
    assertNotificationShownOnRequest(true)
    assertBannerShownOnRequest(true)
  }

  @Test
  fun `notification is shown once when its flag is set and auto-update is on`() {
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = true
    Notification.enable()
    assertTrue(Notification.isEnabled())
    PluginAutoUpdateEnabler.notifyAboutEnabledAutoUpdateIfNeeded(project.get())
    assertFalse(Notification.isEnabled())
  }

  @Test
  fun `notification flag is cleared when auto-update is off`() {
    Notification.enable()
    assertNotificationShownOnRequest(false)
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = true
    assertFalse(Notification.isEnabled())
  }

  @Test
  fun `notification is skipped when its flag is not set`() {
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = true

    assertNotificationShownOnRequest(false)
    assertFalse(Notification.isEnabled())
  }

  @Test
  fun `banner is shown once when its flag is set and auto-update is on`() {
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = true
    Banner.enable()
    assertBannerShownOnRequest(true)
    assertBannerShownOnRequest(false)
  }

  @Test
  fun `banner flag is cleared when auto-update is off`() {
    Banner.enable()
    assertBannerShownOnRequest(false)
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = true
    assertBannerShownOnRequest(false)
  }

  @Test
  fun `banner is skipped when its flag is not set`() {
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = true
    assertBannerShownOnRequest(false)
  }

  private fun enableStartupActivity() {
    PluginAutoUpdateEnabler.enablePluginAutoUpdateEnabler(disposable)
    enablePluginUpdateSourceFiltering()
  }

  private fun enablePluginUpdateSourceFiltering() {
    Registry.get(PLUGIN_UPDATE_SOURCE_FILTER_REGISTRY_PROPERTY).setValue(true, disposable)
  }

  private fun maskPluginManagementPolicyForbiddingAutoUpdate() {
    ExtensionTestUtil.maskExtensions(
      PluginManagementPolicy.EP,
      listOf(TestPluginManagementPolicy(autoUpdateAllowed = false)),
      disposable,
    )
  }

  private fun resetAutoUpdateState() {
    UpdateSettings.getInstance().isPluginsAutoUpdateEnabled = false
    PluginAutoUpdateEnabler.Feature.entries.forEach { it.disable() }
    getInstance().unsetValue(RUN_ONCE_PROPERTY)
  }

  private fun assertAutoUpdateIsOff() {
    assertFalse(UpdateSettings.getInstance().isPluginsAutoUpdateEnabled)
    assertNotificationShownOnRequest(false)
    assertBannerShownOnRequest(false)
  }

  private fun assertNotificationShownOnRequest(expected: Boolean) {
    assertEquals(expected, PluginAutoUpdateEnabler.notifyAboutEnabledAutoUpdateIfNeeded(project.get()))
  }

  private fun assertBannerShownOnRequest(expected: Boolean) {
    assertEquals(expected, PluginAutoUpdateEnabler.createBannerIfNeeded() != null)
  }

  private class TestPluginManagementPolicy(
    private val autoUpdateAllowed: Boolean,
  ) : PluginManagementPolicy {
    override fun isUpgradeAllowed(localDescriptor: IdeaPluginDescriptor?, remoteDescriptor: IdeaPluginDescriptor?): Boolean = true
    override fun isDowngradeAllowed(localDescriptor: IdeaPluginDescriptor?, remoteDescriptor: IdeaPluginDescriptor?): Boolean = false
    override fun canEnablePlugin(descriptor: IdeaPluginDescriptor?): Boolean = true
    override fun canInstallPlugin(descriptor: IdeaPluginDescriptor?): Boolean = true
    override fun isInstallFromDiskAllowed(): Boolean = true
    override fun isPluginAutoUpdateAllowed(): Boolean = autoUpdateAllowed
  }

  private companion object {
    const val PLUGIN_UPDATE_SOURCE_FILTER_REGISTRY_PROPERTY = "platform.limit.plugin.update.source.by.configured.one"
    const val RUN_ONCE_PROPERTY = "RunOnceActivity.plugin.auto.update.checked.for.plugin.update.source"
  }
}

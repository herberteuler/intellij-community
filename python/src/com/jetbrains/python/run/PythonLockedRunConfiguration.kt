// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.configurations.WithoutOwnBeforeRunSteps
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.icons.AllIcons
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.WriteExternalException
import com.intellij.python.community.common.promotion.PyFrameworkPromoProvider
import com.intellij.ui.RowIcon
import com.intellij.util.PlatformUtils
import com.jetbrains.python.PYTHON_PROF_PLUGIN_ID
import com.jetbrains.python.PyBundle
import com.jetbrains.python.createPromoPanel
import org.jdom.Element
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.VisibleForTesting
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Icon
import javax.swing.JComponent

private class PythonLockedRunConfigurationEditor : SettingsEditor<PythonLockedRunConfiguration>(null, true) {

  override fun resetEditorFrom(s: PythonLockedRunConfiguration) {}

  override fun applyEditorTo(s: PythonLockedRunConfiguration) {}

  protected override fun createEditor(): JComponent = createPromoPanel(
    onAction = PyFrameworkPromoProvider.getInstance()?.let { provider -> { provider.onRunConfigAction() } }
  )
}

private class PythonLockedRunConfiguration(val configProject: Project, val configFactory: ConfigurationFactory) : RunConfiguration,
                                                                                                                  WithoutOwnBeforeRunSteps,
                                                                                                                  RunConfigurationWithSuppressedDefaultRunAction {

  var theName: String? = null
  var configElement: Element? = null

  override fun getFactory(): ConfigurationFactory? {
    return configFactory
  }

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration?> {
    return PythonLockedRunConfigurationEditor()
  }

  override fun getProject(): Project? {
    return configProject
  }

  override fun clone(): RunConfiguration {
    return PythonLockedRunConfiguration(configProject, configFactory)
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState? {
    throw ExecutionException(PyBundle.message("python.run.configuration.is.not.runnable.in.this.mode"))
  }

  override fun checkConfiguration() {
    throw RuntimeConfigurationWarning(PyBundle.message("python.run.configuration.is.not.runnable.in.this.mode"))
  }

  override fun setName(value: String) {
    theName = value
  }

  override fun getName(): @NlsSafe String {
    return tryGetName() ?: "LockedConfiguration${nameCounter.getAndIncrement()}"
  }

  override fun getIcon(): Icon? {
    return configFactory.icon
  }

  override fun readExternal(element: Element) {
    configElement = JDOMUtil.internElement(element)
  }

  @Throws(WriteExternalException::class)
  override fun writeExternal(element: Element) {
    val data = configElement ?: return

    for (a in data.getAttributes()) {
      element.setAttribute(a.name, a.value)
    }

    for (child in data.children) {
      element.addContent(child.clone())
    }
  }

  private fun tryGetName(): @NlsSafe String? {
    val name = theName
    if (name != null) {
      return name
    }

    val data = configElement ?: return null
    return data.getAttributeValue("name")
  }

  companion object {
    var nameCounter = AtomicInteger(1)
  }
}

private class PythonLockedRunConfigurationFactory(type: ConfigurationType) : ConfigurationFactory(type) {
  override fun getId(): String {
    return name
  }

  override fun createTemplateConfiguration(project: Project): RunConfiguration {
    return PythonLockedRunConfiguration(project, this)
  }
}

@ApiStatus.Internal
open class PythonLockedRunConfigurationTypeBase(
  val theId: String,
  @Nls val name: String,
  private val baseIconSupplier: (() -> Icon?)? = null,
) : ConfigurationType {
  private val factory: ConfigurationFactory = PythonLockedRunConfigurationFactory(this)

  init {
    // Do not enable "lock" configs for non PyCharm or Idea (as it's capable of running the Python plugin) IDEs or if the Python plugin is enabled.
    if (!forceEnableForTesting &&
        ((!PlatformUtils.isPyCharm() && !PlatformUtils.isIntelliJ()) ||
         PluginManager.getInstance().findEnabledPlugin(PluginId.getId(PYTHON_PROF_PLUGIN_ID)) != null)) {
      throw ExtensionNotApplicableException.create()
    }
  }

  companion object {
    @ApiStatus.Internal
    @VisibleForTesting
    @Volatile
    var forceEnableForTesting: Boolean = false
  }

  override fun getDisplayName(): @Nls(capitalization = Nls.Capitalization.Title) String {
    return name
  }

  override fun getConfigurationTypeDescription(): @Nls(capitalization = Nls.Capitalization.Sentence) String? {
    return name
  }

  private val lazyIcon: Icon by lazy {
    val base = baseIconSupplier?.invoke()
    if (base != null) RowIcon(base, AllIcons.Ultimate.PycharmLock) else AllIcons.Ultimate.PycharmLock
  }

  override fun getIcon(): Icon = lazyIcon

  override fun getId(): @NonNls String {
    return theId
  }

  override fun getConfigurationFactories(): Array<out ConfigurationFactory?>? {
    return arrayOf(factory)
  }

  override fun isDumbAware(): Boolean {
    return true
  }

  override fun isManaged(): Boolean {
    return false
  }
}

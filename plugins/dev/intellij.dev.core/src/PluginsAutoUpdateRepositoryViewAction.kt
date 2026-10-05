// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.dev.core

import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PluginAutoUpdateRepository
import com.intellij.openapi.application.PluginAutoUpdateRepository.PluginUpdateInfo
import com.intellij.openapi.application.PluginAutoUpdateRepository.getAutoUpdateDirPath
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ComboboxSpeedSearch
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.text
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.util.cancelOnDispose
import com.intellij.util.io.copy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.JComponent
import kotlin.io.path.absolutePathString
import kotlin.io.path.isRegularFile

/**
 * Internal action for debugging purposes
 */
internal class PluginsAutoUpdateRepositoryViewAction : AnAction() {
  override fun actionPerformed(e: AnActionEvent) {
    ApplicationManager.getApplication().invokeLater({ Viewer(e.project).showAndGet() }, ModalityState.nonModal())
  }

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = ApplicationManager.getApplication().isInternal
  }

  override fun isDumbAware(): Boolean = true
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  @Service(Service.Level.APP)
  private class ViewModel(val cs: CoroutineScope) {
    private val _state: MutableStateFlow<Result<Map<PluginId, PluginUpdateInfo>>?> = MutableStateFlow(null)
    val state: StateFlow<Result<Map<PluginId, PluginUpdateInfo>>?> = _state.asStateFlow()

    init {
      updateState()
    }

    fun updateState() {
      cs.launch(Dispatchers.IO) {
        _state.value = runCatching { PluginAutoUpdateRepository.readUpdates() }
      }
    }

    fun clear() {
      cs.launch(Dispatchers.IO) {
        PluginAutoUpdateRepository.clearUpdates()
        updateState()
      }
    }

    fun add(pluginDescriptor: IdeaPluginDescriptorImpl, updatePath: Path) {
      cs.launch(Dispatchers.IO) {
        updatePath.copy(getAutoUpdateDirPath().resolve(updatePath.fileName))
        PluginAutoUpdateRepository.addUpdates(mapOf(
          pluginDescriptor.pluginId to PluginUpdateInfo(pluginDescriptor.pluginPath.absolutePathString(), updatePath.fileName.toString())
        ))
        updateState()
      }
    }
  }

  @Suppress("HardCodedStringLiteral")
  private class Viewer(val project: Project?) : DialogWrapper(project) {
    init {
      init()
      service<ViewModel>().updateState()
    }

    override fun createCenterPanel(): JComponent? = panel {
      row("State") {
        val textArea = textArea()
        textArea.align(AlignX.FILL).applyToComponent {
          isEditable = false
          maximumSize = Dimension(700, 400)
        }
        service<ViewModel>().cs.launch(Dispatchers.EDT + ModalityState.stateForComponent(window).asContextElement()) {
          service<ViewModel>().state.collectLatest { state ->
            when {
              state == null -> textArea.text("initializing...")
              state.isFailure -> textArea.text("error: ${state.exceptionOrNull()}")
              state.isSuccess -> textArea.text(
                state.getOrNull()!!.map { it.key.idString to it.value.updateFilename }.joinToString("\n") { "${it.first}: ${it.second}" }
              )
            }
          }
        }.cancelOnDispose(disposable)
      }
      separator()
      row {
        val pluginCombobox = comboBox(PluginManagerCore.getPluginSet().allPlugins, textListCellRenderer {
          it?.name ?: it?.pluginId?.idString ?: "<unnamed plugin>"
        })
        ComboboxSpeedSearch.installOn(pluginCombobox.component)
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor().withTitle("Select Update File")
        val updateChooser = textFieldWithBrowseButton(descriptor, project)
        button("Add") {
          val selectedPlugin = pluginCombobox.component.selectedItem as? IdeaPluginDescriptorImpl ?: return@button
          val updateFilePath = updateChooser.component.text
          val updateFile = Path.of(updateFilePath).takeIf { it.isRegularFile() } ?: return@button
          service<ViewModel>().add(selectedPlugin, updateFile)
        }
      }
      row {
        button("Clear") {
          service<ViewModel>().clear()
        }
      }
    }
  }
}

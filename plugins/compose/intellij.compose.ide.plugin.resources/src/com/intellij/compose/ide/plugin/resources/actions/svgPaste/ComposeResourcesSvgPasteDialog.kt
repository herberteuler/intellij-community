// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.compose.ide.plugin.resources.actions.svgPaste

import com.intellij.compose.ide.plugin.shared.ComposeIdeBundle
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.observable.util.toEnumOrNull
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.DoNotAskOption
import com.intellij.openapi.util.NlsSafe
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

internal class ComposeResourcesSvgPasteDialog(project: Project, private val numberOfFiles: Int) : DialogWrapper(project, true) {
  init {
    title = ComposeIdeBundle.message("compose.svg.paste.dialog.title")
    setOKButtonText(ComposeIdeBundle.message("compose.svg.paste.dialog.convert.button.text"))
    setCancelButtonText(ComposeIdeBundle.message("compose.svg.paste.dialog.cancel.button.text"))
    setDoNotAskOption(RememberPasteBehaviorOption(project))
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row { text(conversionMessage()) }
  }

  @NlsSafe
  private fun conversionMessage(): String = when (numberOfFiles) {
    1 -> ComposeIdeBundle.message("compose.svg.paste.dialog.one.file")
    else -> ComposeIdeBundle.message("compose.svg.paste.dialog.multiple.files", numberOfFiles)
  }

  companion object {
    private const val SVG_PASTE_BEHAVIOR_KEY = "compose.resources.svg.paste.behavior"

    fun getPasteBehavior(project: Project): SvgPasteBehavior =
      PropertiesComponent.getInstance(project).getValue(SVG_PASTE_BEHAVIOR_KEY)?.toEnumOrNull<SvgPasteBehavior>()
      ?: SvgPasteBehavior.ASK

    fun setPasteBehavior(project: Project, behavior: SvgPasteBehavior) {
      PropertiesComponent.getInstance(project).setValue(SVG_PASTE_BEHAVIOR_KEY, behavior.name)
    }
  }
}

private class RememberPasteBehaviorOption(private val project: Project) : DoNotAskOption.Adapter() {
  override fun shouldSaveOptionsOnCancel(): Boolean = true

  override fun rememberChoice(isSelected: Boolean, exitCode: Int) {
    if (!isSelected) return
    val behavior = if (exitCode == DialogWrapper.OK_EXIT_CODE) SvgPasteBehavior.ALWAYS_CONVERT else SvgPasteBehavior.NEVER_CONVERT
    ComposeResourcesSvgPasteDialog.setPasteBehavior(project, behavior)
  }

  override fun getDoNotShowMessage(): String = ComposeIdeBundle.message("compose.svg.paste.dialog.ask.again")
}

internal enum class SvgPasteBehavior { ASK, ALWAYS_CONVERT, NEVER_CONVERT }
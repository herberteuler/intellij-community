// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import java.awt.Color

internal data class TabGroup(val name: String, val key: String)

internal const val AGENTS_GROUP_KEY = "air:agents"

private val AIR_EDITOR_TAB_CLASS_NAMES = setOf(
  "com.intellij.air.thread.view.AgentThreadViewVirtualFile",
  "com.intellij.air.frontend.agents.AgentsPageVirtualFile",
)

internal fun isAirEditorTabClass(className: String): Boolean =
  className in AIR_EDITOR_TAB_CLASS_NAMES

internal fun agentsTabGroup(): TabGroup =
  TabGroup(EditorTabGroupingBundle.message("group.agents"), AGENTS_GROUP_KEY)

@Suppress("SplitModeApiUsage")
internal object EditorTabGroupingProvider {

  @Suppress("UnregisteredNamedColor")
  private val groupColors = listOf(
    JBColor(JBColor.namedColor("ColorPalette.Blue4"), JBColor.namedColor("ColorPalette.Blue6")),
    JBColor(JBColor.namedColor("ColorPalette.Purple3"), JBColor.namedColor("ColorPalette.Purple7")),
    JBColor(JBColor.namedColor("ColorPalette.Teal3"), JBColor.namedColor("ColorPalette.Teal6")),
    JBColor(JBColor.namedColor("ColorPalette.Green3"), JBColor.namedColor("ColorPalette.Green7")),
    JBColor(JBColor.namedColor("ColorPalette.Yellow2"), JBColor.namedColor("ColorPalette.Yellow7")),
    JBColor(JBColor.namedColor("ColorPalette.Orange3"), JBColor.namedColor("ColorPalette.Orange7")),
  )

  /**
   * Returns the group of [file]: the synthetic Agents group for known AIR editor tabs,
   * otherwise the parent directory group.
   *
   * The result does not depend on a setting, so the caller can memoize it. The caller must
   * check [EditorTabGroupingSettings.groupByDirectory] itself.
   */
  fun resolveDirectoryGroup(file: VirtualFile, project: Project): TabGroup? {
    if (isAirEditorTabClass(file.javaClass.name)) {
      return agentsTabGroup()
    }
    val parent = file.parent ?: return null
    val contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(file) ?: return null
    val relative = parent.path.removePrefix(contentRoot.path).trimStart('/')
    val key = "${contentRoot.path}/${relative}"
    return TabGroup(name = parent.name, key = key)
  }

  fun colorForGroup(group: TabGroup): Color {
    return groupColors[Math.floorMod(group.key.hashCode(), groupColors.size)]
  }
}

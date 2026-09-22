// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.problemView.security

import com.intellij.analysis.problemsView.toolWindow.ProblemsView
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanelProvider
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTab
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The Problems View tab that hosts the security features. It appears only while at least one
 * [ProblemsViewSubTabProvider] contributes to [ProblemsViewSubTabs.SECURITY_TAB_ID].
 */
internal class SecurityProblemsViewTab(project: Project) : SubTabbedProblemsViewTab(
  project = project,
  hostTabId = ProblemsViewSubTabs.SECURITY_TAB_ID,
  tabTitle = SecurityProblemsViewBundle.message("security.problems.view.tab.name"),
  usagesTabId = "Security",
)

internal class SecurityProblemsViewPanelProvider(private val project: Project) : ProblemsViewPanelProvider {
  override fun create(): ProblemsViewTab? {
    // the tool window reads the providers one time, and a plugin can add one later, so the watch starts here
    project.service<SecurityProblemsViewTabWatcher>()
    return if (SubTabbedProblemsViewTab.hasSubTabs(ProblemsViewSubTabs.SECURITY_TAB_ID)) {
      SecurityProblemsViewTab(project)
    }
    else null
  }
}

/**
 * Builds the Security tab again when the set of the [ProblemsViewSubTabProvider]s changes.
 *
 * The extension point is dynamic, so a plugin that loads or unloads changes that set. A tab reads the providers one
 * time, in its constructor, so a changed set needs a new tab, and not a new list inside the old one.
 *
 * [SecurityProblemsViewPanelProvider] asks for this service, so the watch starts with the content of the tool window.
 * A tool window that built no content yet reads the providers when it builds it.
 */
@Service(Service.Level.PROJECT)
internal class SecurityProblemsViewTabWatcher(private val project: Project, private val scope: CoroutineScope) {
  init {
    // the platform changes the extension point off the EDT, and the rebuild is Swing work
    ProblemsViewSubTabProvider.EP.addChangeListener(scope) {
      scope.launch(Dispatchers.EDT) { rebuildTab() }
    }
  }

  /**
   * Takes the Security tab out of the tool window, and puts it back from the providers of this moment.
   *
   * The removal disposes the old tab, and the old tab disposes its sub-tabs. So a sub-tab of an unloaded plugin goes
   * away here. A set that became empty adds no tab back.
   *
   * The new tab lands last, because the platform adds a tab at the end. A selected tab stays selected.
   */
  private fun rebuildTab() {
    if (project.isDisposed) return
    val window = ProblemsView.getToolWindow(project) ?: return
    val manager = window.contentManagerIfCreated ?: return
    val content = ProblemsViewToolWindowUtils.getContentById(project, ProblemsViewSubTabs.SECURITY_TAB_ID)
    val wasSelected = content != null && content == manager.selectedContent
    ProblemsViewToolWindowUtils.removeTab(project, ProblemsViewSubTabs.SECURITY_TAB_ID)
    ProblemsViewToolWindowUtils.addTab(project, SecurityProblemsViewPanelProvider(project))
    if (wasSelected) {
      ProblemsViewToolWindowUtils.selectContent(manager, ProblemsViewSubTabs.SECURITY_TAB_ID)
    }
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.problemView.security

import com.intellij.analysis.problemsView.toolWindow.ProblemsView
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.ui.content.ContentManager
import com.intellij.util.concurrency.ThreadingAssertions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls

/**
 * Entry points into the tabbed tabs of the Problems View tool window.
 *
 * @see ProblemsViewSubTab
 * @see ProblemsViewSubTabProvider
 */
@ApiStatus.Internal
object ProblemsViewSubTabs {
  /**
   * The tab that collects the security features: vulnerable dependencies, taint analysis traces, and so on.
   */
  const val SECURITY_TAB_ID: @NonNls String = "SECURITY_PROBLEMS_TAB"

  /**
   * Selects the sub-tab [subTabId] of the host tab [hostTabId], and shows the tool window with the host tab selected in
   * it. The sub-tab is selected first, and the tool window is shown after: the user then never sees the sub-tab that
   * was selected before this call.
   */
  suspend fun select(project: Project, @NonNls hostTabId: String, @NonNls subTabId: String) {
    withContext(Dispatchers.EDT) {
      hostTab(contentManager(project), hostTabId)?.selectSubTab(subTabId)
    }
    ProblemsViewToolWindowUtils.selectTabAsync(project, hostTabId)
  }

  fun findSubTab(project: Project, @NonNls hostTabId: String, @NonNls subTabId: String): ProblemsViewSubTab? {
    ThreadingAssertions.assertEventDispatchThread()
    return hostTab(contentManagerIfCreated(project), hostTabId)?.findSubTab(subTabId)
  }

  fun isSubTabShown(project: Project, @NonNls hostTabId: String, @NonNls subTabId: String): Boolean {
    ThreadingAssertions.assertEventDispatchThread()
    if (project.isDisposed) return false
    val window = ProblemsView.getToolWindow(project) ?: return false
    if (!window.isVisible) return false
    val host = window.contentManagerIfCreated?.selectedContent?.component as? ProblemsViewSubTabHost ?: return false
    return host.hostTabId == hostTabId && host.shownSubTabId.value == subTabId
  }

  private fun hostTab(contentManager: ContentManager?, @NonNls hostTabId: String): ProblemsViewSubTabHost? =
    contentManager
      ?.contents
      ?.asSequence()
      ?.map { it.component }
      ?.filterIsInstance<ProblemsViewSubTabHost>()
      ?.firstOrNull { it.hostTabId == hostTabId }

  private fun contentManager(project: Project): ContentManager? =
    if (project.isDisposed) null else ProblemsView.getToolWindow(project)?.contentManager

  private fun contentManagerIfCreated(project: Project): ContentManager? =
    if (project.isDisposed) null else ProblemsView.getToolWindow(project)?.contentManagerIfCreated
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.problemView.security.frontend

import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import javax.swing.Icon
import javax.swing.JComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls

@ApiStatus.Internal
data class ProblemsViewSubTabPresentation(
  @param:NlsContexts.TabTitle val title: String,
  val problemCount: Int? = null,
  @param:Nls val detail: String? = null,
  val icon: Icon? = null,
  val foundNoIssues: Boolean = false,
)

@ApiStatus.Internal
interface ProblemsViewSubTab : Disposable {
  @get:NonNls
  val id: String

  val presentation: StateFlow<ProblemsViewSubTabPresentation>

  val component: JComponent

  @get:NonNls
  val gearActionGroupId: String?
    get() = null

  fun selectionChangedTo(selected: Boolean) {
  }

  override fun dispose() {
  }
}

/**
 * Contributes a permanent [ProblemsViewSubTab] to the tabbed Problems View tab identified by [hostTabId].
 *
 * The host tab appears only while at least one provider contributes to it, so a feature that ships on its own still
 * gets a complete tab, and no empty tab is shown in IDEs where no feature ships at all. The providers are read when
 * the host tab is built, and a provider that arrives with a plugin, or leaves with one, makes the host tab build again.
 *
 * The sub-tabs appear in the order the providers are read, and the first one is selected when the tab is built. The
 * providers of one host tab usually ship in different plugins, so declare that order with the `id` and `order`
 * attributes of the extension rather than leaving it to the order the plugins happen to load in.
 */
@ApiStatus.Internal
interface ProblemsViewSubTabProvider {
  companion object {
    @JvmStatic
    val EP: ExtensionPointName<ProblemsViewSubTabProvider> = ExtensionPointName("com.intellij.securityProblemsViewSubTab")
  }

  @get:NonNls
  val hostTabId: String

  /**
   * Makes the sub-tab, or gives `null` when this provider contributes none to [project].
   *
   * @param scope the scope of the sub-tab. The host tab owns it, and the host tab cancels it. It dispatches on the
   * event dispatch thread, because a sub-tab is a Swing component.
   */
  fun createSubTab(project: Project, scope: CoroutineScope): ProblemsViewSubTab?
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.problemView.security.frontend

import com.intellij.analysis.problemsView.toolWindow.ProblemsView
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTabWithMetrics
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewToolWindowUtils
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.text.HtmlChunk.body
import com.intellij.openapi.util.text.HtmlChunk.html
import com.intellij.openapi.util.text.HtmlChunk.tag
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.ColorUtil
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.content.Content
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls

/**
 * A tab of the Problems View tool window whose content is a set of [ProblemsViewSubTab]s.
 *
 * The sub-tabs are picked in a [ProblemsViewSubTabSelector] column on the left, and the selected one fills the rest
 * of the tab. They come from the [ProblemsViewSubTabProvider]s that declare this tab's id, and from nowhere else, so
 * the column is a fixed list of sources rather than something that grows as the user works: it is built together with
 * the tab and stays as it was built. The counter in the tab title is the sum of the counts the sub-tabs report in their
 * [ProblemsViewSubTab.presentation], and only the selected sub-tab of a selected and visible tab is told it is shown.
 *
 * Which sub-tab is selected is owned by the selector as [ProblemsViewSubTabSelector.selectedItemId], and this tab
 * shows whatever that flow says: [selectSubTab] and a click on an item are the same thing, and both are applied
 * by the collector of that flow rather than at the call site. What that collector has actually mounted is published
 * as [shownSubTabId], which is therefore what to ask about the sub-tab the user is looking at.
 *
 * A sub-tab is told that it is shown while it is the mounted sub-tab of a visible tab. That is one rule over
 * [shownSubTabId] and the visibility of the tab, so neither a new selection nor a change of the visibility has to know
 * what the other one did.
 */
@ApiStatus.Internal
open class SubTabbedProblemsViewTab(
  final override val project: Project,
  @param:NonNls final override val hostTabId: String,
  @param:NlsContexts.TabTitle private val tabTitle: String,
  final override val usagesTabId: String,
) : ProblemsViewTabWithMetrics(), ProblemsViewSubTabHost, Disposable {

  /**
   * The scope of everything that lives as long as this tab: the three collectors this tab starts, the rows of the
   * selector that read their sub-tabs, and a child scope for each sub-tab.
   */
  private val scope: CoroutineScope =
    project.service<ProblemsViewSubTabScopeService>().scope.childScope("SubTabbedProblemsViewTab($hostTabId)")
  private val entries: List<ProblemsViewSubTab> = subTabProviders(hostTabId).mapNotNull {
    it.createSubTab(project, scope.childScope(it.javaClass.name, Dispatchers.EDT))
  }
  private val selector: ProblemsViewSubTabSelector = ProblemsViewSubTabSelector(scope, entries)
  private val subTabPanel: JPanel = JPanel(BorderLayout())

  private val _shownSubTabId: MutableStateFlow<String?> = MutableStateFlow(null)

  /**
   * The id of the sub-tab whose component is mounted in this tab, or `null` while none is. Trails
   * [ProblemsViewSubTabSelector.selectedItemId], which is what the user picked, by however long it takes the collector
   * of that flow to reach the event dispatch thread — so this is the one to ask about what is on screen, and that one
   * about what was asked for.
   */
  final override val shownSubTabId: StateFlow<String?> = _shownSubTabId.asStateFlow()

  /**
   * `true` while this tab is the selected tab of a visible Problems View tool window.
   */
  private val visible: MutableStateFlow<Boolean> = MutableStateFlow(false)

  init {
    ThreadingAssertions.assertEventDispatchThread()
    setContent(createSplitter())
    selector.select(entries.firstOrNull()?.id)
    // what the selector points at is applied here and not left to initSelection(), so that the sub-tab it points at
    // is mounted before this constructor returns
    showSubTab(selector.selectedItemId.value)
    initSelectionSubscription()
    initShownSubTabSubscription()
    initProblemCountsSubscription()
  }

  final override fun getTabId(): String = hostTabId

  final override fun getName(count: Int): String = when {
    count <= 0 -> tabTitle
    else -> {
      val margin = JBUI.scale(8)
      val counterColor = ColorUtil.toHtmlColor(UIUtil.getInactiveTextColor())
      // unfortunately, there is no better way rather than use an HTML table for spacing
      // since it doesn't support full HTML4+
      html()
        .child(body()
                 .child(tag("table").attr("cellspacing", "0").attr("cellpadding", "0")
                          .child(tag("tr")
                                   .child(tag("td").addText(tabTitle))
                                   .child(tag("td").attr("width", "${margin}px"))
                                   .child(tag("td").attr("color", counterColor).addText(count.toString()))
                          )
                 )
        ).toString()
    }
  }

  final override val shownProblemsCount: Int
    get() = entries.sumOf { it.presentation.value.problemCount ?: 0 }

  override fun customizeTabContent(content: Content) {
    content.setDisposer(this)
  }

  final override fun visibilityChangedTo(visible: Boolean) {
    super.visibilityChangedTo(visible)
    this.visible.value = visible
  }

  override fun dispose() {
    scope.cancel()
    entries.forEach(Disposer::dispose)
  }

  /**
   * Requests the sub-tab [subTabId] to be selected. Ids of sub-tabs this tab does not have are ignored.
   */
  final override fun selectSubTab(@NonNls subTabId: String) {
    ThreadingAssertions.assertEventDispatchThread()
    selector.select(subTabId)
  }

  /**
   * The sub-tab [subTabId] of this tab, or `null` when this tab has none with that id.
   *
   * Call it on the event dispatch thread, as [selectSubTab] asks too.
   */
  final override fun findSubTab(@NonNls subTabId: String): ProblemsViewSubTab? {
    ThreadingAssertions.assertEventDispatchThread()
    return entries.firstOrNull { it.id == subTabId }
  }

  /**
   * Mounts whatever the selector points at.
   */
  private fun initSelectionSubscription() {
    scope.launch(Dispatchers.EDT) {
      selector.selectedItemId.collect { showSubTab(it) }
    }
  }

  /**
   * Tells the sub-tabs when they are shown, and puts the gear menu of the shown one in the tool window.
   *
   * One rule holds both: a sub-tab is shown while it is the mounted sub-tab of a visible tab. Both ends of that
   * interval are marked here, so no call site has to remember the other end.
   */
  private fun initShownSubTabSubscription() {
    scope.launch(Dispatchers.EDT) {
      var shownSubTab: ProblemsViewSubTab? = null
      combine(_shownSubTabId, visible) { id, isVisible ->
        val subTab = if (isVisible) entries.firstOrNull { it.id == id } else null
        subTab to isVisible
      }
        .distinctUntilChanged()
        .collect { (subTab, isVisible) ->
          shownSubTab?.selectionChangedTo(false)
          subTab?.selectionChangedTo(true)
          shownSubTab = subTab
          updateGearActions(subTab, isVisible)
        }
    }
  }

  /**
   * Keeps the counter in the tab title equal to the sum of what the sub-tabs report. This reads the sum, and each row
   * of the selector reads its own sub-tab.
   */
  private fun initProblemCountsSubscription() {
    if (entries.isEmpty()) return
    scope.launch(Dispatchers.EDT) {
      combine(entries.map { it.presentation }) { all -> all.sumOf { it.problemCount ?: 0 } }
        .distinctUntilChanged()
        .collect { updateTabName() }
    }
  }

  /**
   * Puts the component of the sub-tab [subTabId] in the tab, and publishes it as the sub-tab this tab shows. Whoever
   * is shown and whoever is not is worked out from that, and not here.
   *
   * The component is asked for first, before anything changes, so that a sub-tab which cannot hand one over leaves this
   * tab as it was instead of half moved to it.
   */
  private fun showSubTab(@NonNls subTabId: String?) {
    ThreadingAssertions.assertEventDispatchThread()
    val subTab = entries.firstOrNull { it.id == subTabId }
    if (_shownSubTabId.value == subTab?.id) return
    val component = subTab?.component
    subTabPanel.removeAll()
    component?.let { subTabPanel.add(it, BorderLayout.CENTER) }
    _shownSubTabId.value = subTab?.id
    subTabPanel.revalidate()
    subTabPanel.repaint()
  }

  /**
   * The tab: the selector of the sub-tabs, and the component of the shown one beside it. The user moves the divider,
   * and where it is left is remembered under a key of this host tab.
   */
  private fun createSplitter(): OnePixelSplitter =
    OnePixelSplitter(false, "ProblemsView.SubTabs.$hostTabId.proportion", SELECTOR_PROPORTION).apply {
      firstComponent = selector
      secondComponent = subTabPanel
    }

  private fun updateTabName() {
    val content = ProblemsViewToolWindowUtils.getContentById(project, hostTabId) ?: return
    content.displayName = getName(shownProblemsCount)
  }

  /**
   * Puts the gear menu of the tool window under [subTab], or takes the addition of the previous sub-tab out of it if
   * that sub-tab has none. Does nothing while [isVisible] is `false`, because the gear menu then holds what the tab
   * that replaced this one put in it.
   */
  private fun updateGearActions(subTab: ProblemsViewSubTab?, isVisible: Boolean) {
    if (!isVisible) return
    val window = ProblemsView.getToolWindow(project) as? ToolWindowEx ?: return
    val group = subTab?.gearActionGroupId?.let { ActionManager.getInstance().getAction(it) as? ActionGroup }
    window.setAdditionalGearActions(group)
  }

  companion object {
    /**
     * The share of the tab width the sub-tab selector takes by default.
     */
    private const val SELECTOR_PROPORTION: Float = 0.2F

    fun hasSubTabs(@NonNls hostTabId: String): Boolean = subTabProviders(hostTabId).isNotEmpty()

    private fun subTabProviders(@NonNls hostTabId: String): List<ProblemsViewSubTabProvider> =
      ProblemsViewSubTabProvider.EP.extensionList.filter { it.hostTabId == hostTabId }
  }
}

@Service(Service.Level.PROJECT)
internal class ProblemsViewSubTabScopeService(@JvmField val scope: CoroutineScope)

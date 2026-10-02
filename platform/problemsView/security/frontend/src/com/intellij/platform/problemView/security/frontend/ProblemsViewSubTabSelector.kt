// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.problemView.security.frontend

import com.intellij.openapi.application.EDT
import com.intellij.ui.ClientProperty
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.hover.ListHoverListener
import com.intellij.ui.render.RenderingUtil
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls

/**
 * The sub-tab selector of [SubTabbedProblemsViewTab]: a vertical list of the sources of problems the host tab collects,
 * one row each, shown next to the selected sub-tab's own component.
 *
 * The list owns the selection, because a list is what this is: picking a row with the mouse or with the arrow keys,
 * scrolling the rows, and telling a screen reader what is there are all things the platform already does.
 * [selectedItemId] is that selection projected into a flow, which is what everything outside subscribes to — the
 * component the host tab shows, above all — and [select] is the way back, moving the selection of the list rather than
 * of anything of its own. The flow is written in one place only, by the list's own selection listener, so a selection
 * that changed for whatever reason — a click, a key, [select] — is reported once and the same way.
 *
 * The rows stand for sources, and the sources are known when the tab is built, so they are given once here and never
 * added or removed afterwards: a selector is the view of one set of sub-tabs, and a set that changed is a new selector.
 * The user never closes a row either; whatever a source opens on demand it shows inside its own component, where the
 * tabs of the platform already know how to be closed.
 *
 * What a row says is not handed to it but taken from the sub-tab it stands for: each row follows its own sub-tab's
 * [ProblemsViewSubTab.presentation] in [scope], which lives as long as this selector is the one on screen. Everything
 * else here is called on the EDT.
 */
@ApiStatus.Internal
class ProblemsViewSubTabSelector(scope: CoroutineScope, subTabs: List<ProblemsViewSubTab>) : JPanel(BorderLayout()) {

  private val listModel: CollectionListModel<ProblemsViewSubTabItem> =
    CollectionListModel(subTabs.map { ProblemsViewSubTabItem(it.id, it.presentation.value) })
  private val list: SubTabList = SubTabList()

  private val _selectedItemId: MutableStateFlow<@NonNls String?> = MutableStateFlow(null)

  /**
   * The id of the selected sub-tab, or `null` while there is none — because the selector is empty, or because the
   * selection was cleared.
   */
  val selectedItemId: StateFlow<@NonNls String?> = _selectedItemId.asStateFlow()

  init {
    background = UIUtil.getPanelBackground()
    add(createScrollPane(), BorderLayout.CENTER)
    // a row keeps the index it was created with, since no row is ever added or removed, so a sub-tab that has something
    // new to say about itself is a new item put in the place of the old one
    for ((index, subTab) in subTabs.withIndex()) {
      scope.launch(Dispatchers.EDT) {
        subTab.presentation.collect { listModel.setElementAt(ProblemsViewSubTabItem(subTab.id, it), index) }
      }
    }
  }

  /**
   * Selects the row of a sub-tab, or, for `null`, selects nothing. Ids of sub-tabs that have no row are ignored.
   */
  fun select(@NonNls id: String?) {
    ThreadingAssertions.assertEventDispatchThread()
    if (id == null) {
      list.clearSelection()
      return
    }
    val index = indexOf(id)
    if (index < 0) return
    list.selectedIndex = index
    list.ensureIndexIsVisible(index)
  }

  private fun indexOf(@NonNls id: String?): Int = listModel.items.indexOfFirst { it.id == id }

  /**
   * The rows, scrolled vertically only: a row is as wide as the selector, and a title too long for it is cut rather
   * than reached by a horizontal scroll bar.
   */
  private fun createScrollPane(): JScrollPane = ScrollPaneFactory.createScrollPane(createListFiller(), true).apply {
    horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    isOpaque = false
    viewport.isOpaque = false
  }

  /**
   * The list, held at the top of a panel that takes the rest of the height. The list is as tall as its rows and no
   * taller, so a click below the last row lands on the filler panel, where it means nothing, instead of being taken
   * for a click on the last row.
   */
  private fun createListFiller(): JPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    add(list, BorderLayout.NORTH)
  }

  private inner class SubTabList : JBList<ProblemsViewSubTabItem>(listModel) {
    init {
      selectionMode = ListSelectionModel.SINGLE_SELECTION
      cellRenderer = ProblemsViewSubTabItemRenderer()
      background = UIUtil.getPanelBackground()
      border = JBUI.Borders.empty()
      // the selected sub-tab is what the host tab shows, so its row stays selected-looking while the user works in it
      ClientProperty.put(this, RenderingUtil.ALWAYS_PAINT_SELECTION_AS_FOCUSED, true)
      ListHoverListener.DEFAULT.addTo(this)
      // the list fires this for a programmatic selection too, so this is the one place the selected id is written
      addListSelectionListener { _selectedItemId.value = selectedValue?.id }
      // getAccessibleContext(), not the `accessibleContext` property: the latter is JComponent's protected field, which
      // stays null until the getter creates the context
      getAccessibleContext().accessibleName = SecurityProblemsViewBundle.message("security.problems.view.sub.tab.list.accessible.name")
    }
  }
}

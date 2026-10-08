// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.actionsOnSave

import com.intellij.icons.AllIcons
import com.intellij.ide.IdeBundle
import com.intellij.openapi.ui.panel.ComponentPanelBuilder
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.TableView
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UI
import com.intellij.util.ui.UIUtil
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

internal class ActionOnSaveColumnInfo :
  SameRendererAndEditorColumnInfo<ActionOnSaveInfo>(IdeBundle.message("actions.on.save.table.column.name.action")) {
  override fun getCellComponent(table: TableView<*>, info: ActionOnSaveInfo, hovered: Boolean): JComponent {
    val resultPanel = JPanel(GridBagLayout())
    resultPanel.border = JBUI.Borders.empty(TOP_INSET, 8, 0, 0)

    // This anchorCheckBox is not painted and doesn't appear in the UI component hierarchy. Its purpose is to make sure that the preferred
    // size of the real checkBox is calculated correctly. The problem is that com.intellij.ide.ui.laf.darcula.ui.DarculaCheckBoxBorder.getBorderInsets()
    // returns different result for a checkbox that has CellRendererPane class as its UI ancestor. We need TableCellEditor and
    // TableCellRenderer to look 100% identically.
    // Its second goal is to normalize baseline of other components.
    // The third use case is to calculate checkbox text horizontal offset - needed to align a label if label is used instead of a checkbox.
    val anchorCheckBox = JCheckBox(info.actionOnSaveName)
    val cbSize = anchorCheckBox.preferredSize
    val anchorBaseline = anchorCheckBox.getBaseline(cbSize.width, cbSize.height)

    val gbc = GridBagConstraints()
    gbc.weightx = 1.0
    gbc.weighty = 1.0
    gbc.anchor = GridBagConstraints.NORTHWEST
    gbc.fill = GridBagConstraints.NONE

    resultPanel.add(createActionNamePanel(table, info, anchorCheckBox, anchorBaseline), gbc)

    gbc.weightx = 0.0
    gbc.anchor = GridBagConstraints.NORTHEAST

    if (hovered) {
      for (link in info.actionLinks) {
        val linkSize = link.preferredSize
        val baselineDelta = anchorBaseline - link.getBaseline(linkSize.width, linkSize.height)
        gbc.insets = Insets(baselineDelta, JBUI.scale(5), 0, JBUI.scale(7))
        resultPanel.add(link, gbc)
      }
    }

    for (control in info.dropDownLinks) {
      val linkSize = control.preferredSize
      val baselineDelta =
        anchorBaseline - control.getBaseline(linkSize.width, linkSize.height)
      gbc.insets = Insets(baselineDelta, JBUI.scale(5), 0, JBUI.scale(7))
      resultPanel.add(control, gbc)
    }

    setupTableCellBackground(resultPanel, hovered)
    return resultPanel
  }

  companion object {
    const val TOP_INSET: Int = 5

    private fun createActionNamePanel(
      table: TableView<*>,
      info: ActionOnSaveInfo,
      anchorCheckBox: JCheckBox,
      anchorBaseline: Int,
    ): JPanel {
      if (info.isSaveActionApplicable) {
        val checkBox = JBCheckBox(info.actionOnSaveName)
        checkBox.anchor = anchorCheckBox

        checkBox.isSelected = info.isActionOnSaveEnabled
        checkBox.addActionListener {
          info.isActionOnSaveEnabled = checkBox.isSelected
          val row = table.editingRow
          val column = table.editingColumn
          if (row >= 0 && column >= 0) {
            // Comment under the checkbox may depend on the checkbox state. Need to re-create the cell editor component.
            table.stopEditing()
            table.editCellAt(row, column)
          }
        }

        val builder = UI.PanelFactory.panel(checkBox)
        val comment = info.comment
        if (comment != null) {
          builder.withComment(comment.commentText, false)
          if (comment.isWarning) {
            builder.withCommentIcon(AllIcons.General.Warning)
          }
        }

        return builder.createPanel()
      }

      val panel = JPanel(GridLayout(2, 1, 0, JBUI.scale(3)))
      val label = JBLabel(info.actionOnSaveName)
      // `setEnabled(false)` is not called for this label because on Windows disabled label looks just the same as its comment

      // The label should have the same indent and baseline as the checkbox text
      val leftInsetScaled = UIUtil.getCheckBoxTextHorizontalOffset(anchorCheckBox) // already scaled

      val labelSize = label.preferredSize
      val baselineDelta = anchorBaseline - label.getBaseline(labelSize.width, labelSize.height)

      panel.border = EmptyBorder(baselineDelta, leftInsetScaled, 0, 0)

      panel.add(label)

      val comment = info.comment
      if (comment != null) {
        val commentComponent = ComponentPanelBuilder.createCommentComponent(comment.commentText, true, -1, false)
        if (comment.isWarning) {
          commentComponent.icon = AllIcons.General.Warning
        }
        panel.add(commentComponent)
      }

      return panel
    }

    @JvmStatic
    fun setupTableCellBackground(component: JComponent, hovered: Boolean) {
      val bgColor = if (hovered)
        JBUI.CurrentTheme.Table.Hover.background(true)
      else
        UIUtil.getTableBackground(false, false)
      UIUtil.setOpaqueRecursively(component, false)
      component.isOpaque = true
      component.background = bgColor
    }
  }
}

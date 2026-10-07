// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.ui

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.TableUtil
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.annotations.ApiStatus
import java.awt.EventQueue
import java.util.function.Function
import javax.swing.JComponent

class ListEditForm {

  val contentPanel: JComponent

  @get:ApiStatus.Internal
  val table: ListTable

  private val newElementSupplier: Function<Project, String?>?

  constructor(@NlsContexts.ColumnName title: String, stringList: MutableList<String>) {
    table = ListTable(ListWrappingTableModel(stringList, title))
    newElementSupplier = null
    contentPanel = setupActions(ToolbarDecorator.createDecorator(table), "").createPanel()
  }

  /**
   * Creates a form for editing a list of strings.
   *
   * @param title The title of the form.
   * @param label The label for the content panel.
   * @param stringList The list of strings to be edited.
   * @param defaultElement The default element to be used in the list.
   * @param newElementSupplier A function that supplies a new element for the list. If it returns null, defaultElement is used.
   */
  @JvmOverloads
  constructor(
    @NlsContexts.ColumnName title: String,
    @NlsContexts.Label label: String?,
    stringList: MutableList<String>,
    defaultElement: String = "",
    newElementSupplier: Function<Project, String?>? = null,
  ) {
    table = ListTable(ListWrappingTableModel(stringList, title))
    this.newElementSupplier = newElementSupplier
    table.tableHeader = null
    table.showHorizontalLines = false

    val toolbar = setupActions(ToolbarDecorator.createDecorator(table), defaultElement)
      .setToolbarPosition(ActionToolbarPosition.LEFT)
    contentPanel = panel {
      row {
        cell(toolbar.createPanel())
          .align(Align.FILL)
          .apply {
            if (!label.isNullOrEmpty()) {
              label(label, LabelPosition.TOP)
            }
          }
      }.resizableRow()
    }
    contentPanel.minimumSize = InspectionOptionsPanel.getMinimumListSize()
  }

  private fun setupActions(decorator: ToolbarDecorator, defaultElement: String): ToolbarDecorator {
    return decorator
      .setAddAction { addElement(defaultElement) }
      .setRemoveAction { TableUtil.removeSelectedItems(table) }
      .disableUpDownActions()
  }

  private fun addElement(defaultElement: String) {
    if (newElementSupplier != null) {
      val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(table))
      if (project != null) {
        val newElement = newElementSupplier.apply(project)
        if (newElement == null) {
          addDefaultElement(defaultElement)
          return
        }
        val tableModel = table.model
        val index = tableModel.indexOf(newElement, 0)
        val rowIndex = if (index < 0) {
          tableModel.addRow(newElement)
          tableModel.rowCount - 1
        }
        else {
          index
        }
        table.setRowSelectionInterval(rowIndex, rowIndex)
        return
      }
    }
    addDefaultElement(defaultElement)
  }

  private fun addDefaultElement(defaultElement: String) {
    val tableModel = table.model
    tableModel.addRow(defaultElement)
    EventQueue.invokeLater {
      val lastRowIndex = tableModel.rowCount - 1
      table.scrollRectToVisible(table.getCellRect(lastRowIndex, 0, true))
      table.editCellAt(lastRowIndex, 0)
      table.selectionModel.setSelectionInterval(lastRowIndex, lastRowIndex)
      val component = table.cellEditor.getTableCellEditorComponent(table, defaultElement, true, lastRowIndex, 0)
      IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown {
        IdeFocusManager.getGlobalInstance().requestFocus(component, true)
      }
    }
  }
}

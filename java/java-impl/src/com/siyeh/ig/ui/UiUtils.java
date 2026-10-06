// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.siyeh.ig.ui;

import com.intellij.codeInspection.ui.InspectionOptionsPanel;
import com.intellij.codeInspection.ui.ListTable;
import com.intellij.codeInspection.ui.ListWrappingTableModel;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.AnActionButton;
import com.intellij.ui.AnActionButtonRunnable;
import com.intellij.ui.TableUtil;
import com.intellij.ui.ToolbarDecorator;

import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import javax.swing.table.TableCellEditor;
import java.awt.Component;
import java.awt.EventQueue;
import java.awt.Rectangle;

/**
 * @deprecated This class is not used anymore. Please inline usages of its helper methods.
 */
@Deprecated(forRemoval = true)
public final class UiUtils {

  private UiUtils() {
  }

  public static JPanel createAddRemovePanel(final ListTable table) {
    final JPanel panel = ToolbarDecorator.createDecorator(table)
      .setToolbarPosition(ActionToolbarPosition.LEFT)
      .setAddAction(new AnActionButtonRunnable() {
        @Override
        public void run(AnActionButton button) {
          final ListWrappingTableModel tableModel = table.getModel();
          tableModel.addRow();
          EventQueue.invokeLater(() -> {
            final int lastRowIndex = tableModel.getRowCount() - 1;
            editTableCell(table, lastRowIndex, 0);
          });
        }
      })
      .setRemoveAction(button -> TableUtil.removeSelectedItems(table))
      .disableUpDownActions().createPanel();
    panel.setMinimumSize(InspectionOptionsPanel.getMinimumListSize());
    return panel;
  }

  private static void editTableCell(final ListTable table, final int row, final int column) {
    final ListSelectionModel selectionModel = table.getSelectionModel();
    selectionModel.setSelectionInterval(row, row);
    EventQueue.invokeLater(() -> {
      final ListWrappingTableModel tableModel = table.getModel();
      IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown(() -> IdeFocusManager.getGlobalInstance().requestFocus(table, true));
      final Rectangle rectangle = table.getCellRect(row, column, true);
      table.scrollRectToVisible(rectangle);
      table.editCellAt(row, column);
      final TableCellEditor editor = table.getCellEditor();
      final Component component = editor.getTableCellEditorComponent(table, tableModel.getValueAt(row, column), true, row, column);
      IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown(() -> IdeFocusManager.getGlobalInstance().requestFocus(component, true));
    });
  }
}

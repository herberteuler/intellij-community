// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.ui;

import com.intellij.openapi.util.NlsContexts.ColumnName;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.ItemRemovable;
import org.jetbrains.annotations.NotNull;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ListWrappingTableModel extends AbstractTableModel implements ItemRemovable {

  private final @NotNull List<@NotNull List<@NotNull String>> list;
  private final @NotNull List<@NotNull String> columnNames = new ArrayList<>();

  public ListWrappingTableModel(@NotNull List<@NotNull List<@NotNull String>> list,
                                @NotNull String @NotNull @ColumnName ... columnNames) {
    this.list = list;
    ContainerUtil.addAll(this.columnNames, columnNames);
  }

  /**
   * Constructor to create a single column model.
   *
   * @param list       the rows of the table
   * @param columnName the name in the column header
   */
  public ListWrappingTableModel(@NotNull List<@NotNull String> list, @NotNull @ColumnName String columnName) {
    this.list = new ArrayList<>();
    this.list.add(list);
    columnNames.add(columnName);
  }

  public void addRow(@NotNull String @NotNull ... values) {
    if (list.size() < values.length) {
      throw new IllegalArgumentException("number of table columns: " +
                                         list.size() + " does not match number of argument " +
                                         "columns: " + values.length);
    }
    int i = 0;
    for (; i < values.length; i++) {
      String value = values[i];
      list.get(i).add(value);
    }
    for (int max = list.size(); i < max; i++) {
      list.get(i).add("");
    }
    int index = getRowCount() - 1;
    fireTableRowsInserted(index, index);
  }

  public void addRow() {
    int columnCount = list.size();
    String[] strings = new String[columnCount];
    Arrays.fill(strings, "");
    addRow(strings);
  }

  @Override
  public @NotNull Class<@NotNull String> getColumnClass(int columnIndex) {
    return String.class;
  }

  @Override
  public int getColumnCount() {
    return columnNames.size();
  }

  @Override
  public @NotNull String getColumnName(int columnIndex) {
    return columnNames.get(columnIndex);
  }

  @Override
  public int getRowCount() {
    return list.getFirst().size();
  }

  @Override
  public @NotNull Object getValueAt(int rowIndex, int columnIndex) {
    return list.get(columnIndex).get(rowIndex);
  }

  public int indexOf(@NotNull String value, int columnIndex) {
    return list.get(columnIndex).indexOf(value);
  }

  @Override
  public boolean isCellEditable(int rowIndex, int columnIndex) {
    return true;
  }

  @Override
  public void removeRow(int rowIndex) {
    for (List<@NotNull String> column : list) {
      column.remove(rowIndex);
    }
    fireTableRowsDeleted(rowIndex, rowIndex);
  }

  @Override
  public void setValueAt(Object value, int rowIndex, int columnIndex) {
    List<@NotNull String> strings = list.get(columnIndex);
    if (rowIndex >= 0 && rowIndex < strings.size()) {
      strings.set(rowIndex, String.valueOf(value));
      fireTableCellUpdated(rowIndex, columnIndex);
    }
  }
}

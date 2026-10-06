// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection.util

import com.intellij.codeInspection.ui.InspectionOptionsPanel
import com.intellij.ide.DataManager
import com.intellij.ide.util.ClassFilter
import com.intellij.ide.util.TreeClassChooserFactory
import com.intellij.java.JavaBundle
import com.intellij.openapi.actionSystem.ActionToolbarPosition
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.NlsContexts
import com.intellij.psi.PsiClass
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.SortedListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import java.util.function.Predicate
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.ListDataEvent
import javax.swing.event.ListDataListener

/**
 * @author Gregory.Shrago
 */
public object SpecialAnnotationsUtil {

  @JvmStatic
  @JvmOverloads
  public fun createSpecialAnnotationsListControl(
    list: MutableList<String>,
    @NlsContexts.Label borderTitle: String,
    acceptPatterns: Boolean,
    isApplicable: Predicate<in PsiClass> = Predicate(PsiClass::isAnnotationType),
  ): JPanel {
    val listModel = SortedListModel(list, Comparator.naturalOrder())
    listModel.addListDataListener(object : ListDataListener {
      override fun intervalAdded(e: ListDataEvent) {
        listChanged()
      }

      override fun intervalRemoved(e: ListDataEvent) {
        listChanged()
      }

      override fun contentsChanged(e: ListDataEvent) {
        listChanged()
      }

      private fun listChanged() {
        list.clear()
        list.addAll(listModel.items)
      }
    })
    return createSpecialAnnotationsListControl(borderTitle, acceptPatterns, listModel, isApplicable)
  }

  @JvmStatic
  public fun createSpecialAnnotationsListControl(
    @NlsContexts.Label borderTitle: String,
    acceptPatterns: Boolean,
    listModel: SortedListModel<String>,
    isApplicable: Predicate<in PsiClass>,
  ): JPanel {
    val injectionList = JBList(listModel)
    injectionList.border = JBUI.Borders.empty()
    injectionList.selectionMode = ListSelectionModel.SINGLE_INTERVAL_SELECTION

    val toolbarDecorator = ToolbarDecorator
      .createDecorator(injectionList)
      .setAddAction {
        val project = CommonDataKeys.PROJECT.getData(DataManager.getInstance().getDataContext(injectionList))
                      ?: ProjectManager.getInstance().defaultProject
        val chooser = TreeClassChooserFactory.getInstance(project)
          .createWithInnerClassesScopeChooser(JavaBundle.message("special.annotations.list.annotation.class"),
                                              GlobalSearchScope.allScope(project), ClassFilter(isApplicable::test), null)
        chooser.showDialog()
        chooser.selected?.qualifiedName?.let { listModel.add(it) }
      }
      .setAddActionName(JavaBundle.message("special.annotations.list.add.annotation.class"))
      .disableUpDownActions()
      .setToolbarPosition(ActionToolbarPosition.LEFT)

    if (acceptPatterns) {
      toolbarDecorator
        .setAddIcon(IconUtil.addClassIcon)
        .addExtraAction(
          object : DumbAwareAction(JavaBundle.message("special.annotations.list.annotation.pattern"), null, IconUtil.addPatternIcon) {
            override fun actionPerformed(e: AnActionEvent) {
              val selectedPattern = Messages.showInputDialog(JavaBundle.message("special.annotations.list.annotation.pattern.message"),
                                                             JavaBundle.message("special.annotations.list.annotation.pattern"),
                                                             Messages.getQuestionIcon())
              if (selectedPattern != null) {
                listModel.add(selectedPattern)
              }
            }
          })
        .setButtonComparator(JavaBundle.message("special.annotations.list.add.annotation.class"),
                             JavaBundle.message("special.annotations.list.annotation.pattern"),
                             JavaBundle.message("special.annotations.list.remove.pattern"))
    }

    return panel {
      row {
        cell(toolbarDecorator.createPanel())
          .label(borderTitle, LabelPosition.TOP)
          .align(Align.FILL)
          .applyToComponent {
            val size = if (acceptPatterns) InspectionOptionsPanel.getMinimumLongListSize() else InspectionOptionsPanel.getMinimumListSize()
            minimumSize = size
            preferredSize = size
          }
      }.resizableRow()
    }
  }
}

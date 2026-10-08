// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.popup.ActionPopupOptions
import com.intellij.ui.popup.ActionPopupStep
import com.intellij.ui.popup.list.ListPopupImpl
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Point
import java.awt.Rectangle
import java.util.function.Supplier
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

@TestApplication
@Timeout(30)
internal class ActionPopupIconTest {
  @Test
  fun `secondary icon follows the right edge and resets for other rows`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val lock = AllIcons.Ultimate.PycharmLock
      fun action(secondaryIcon: Boolean, rightAligned: Boolean) = object : AnAction() {
        init {
          templatePresentation.text = "Docker"
          templatePresentation.icon = AllIcons.FileTypes.Docker
          templatePresentation.putClientProperty(ActionUtil.SECONDARY_ICON, lock.takeIf { secondaryIcon })
          templatePresentation.putClientProperty(ActionUtil.SECONDARY_ICON_RIGHT_ALIGNED, rightAligned)
        }

        override fun actionPerformed(e: AnActionEvent) {}
      }

      val group = DefaultActionGroup(action(true, true), action(true, false), action(false, false))
      val step = ActionPopupStep.createActionsStep(
        null, group, DataContext.EMPTY_CONTEXT, ActionPlaces.POPUP, PresentationFactory(), Supplier { DataContext.EMPTY_CONTEXT },
        ActionPopupOptions.forStepAndItems(false, false, true, false, false, null, 0),
      )
      val popup = ListPopupImpl(null, step)
      try {
        val list = popup.list
        for (selected in listOf(false, true)) {
          val row = list.cellRenderer.getListCellRendererComponent(list, list.model.getElementAt(0), 0, selected, false) as Container
          val label = UIUtil.uiTraverser(row).filter(JLabel::class.java).first { it.icon === lock }
          assertEquals(SwingConstants.RIGHT, label.horizontalAlignment)
          assertEquals(BorderLayout.CENTER, (label.parent.layout as BorderLayout).getConstraints(label))

          fun iconPosition(width: Int): Point {
            row.setSize(width, row.preferredSize.height)
            fun layout(container: Container) {
              container.doLayout()
              container.components.filterIsInstance<Container>().forEach { layout(it) }
            }
            layout(row)
            val insets = label.insets
            val view = Rectangle(insets.left, insets.top, label.width - insets.left - insets.right,
                                 label.height - insets.top - insets.bottom)
            val iconBounds = Rectangle()
            SwingUtilities.layoutCompoundLabel(label, label.getFontMetrics(label.font), label.text, label.icon,
                                               label.verticalAlignment, label.horizontalAlignment,
                                               label.verticalTextPosition, label.horizontalTextPosition,
                                               view, iconBounds, Rectangle(), label.iconTextGap)
            return SwingUtilities.convertPoint(label, iconBounds.location, row)
          }
          assertEquals(100, iconPosition(500).x - iconPosition(400).x)

          list.cellRenderer.getListCellRendererComponent(list, list.model.getElementAt(1), 1, selected, false)
          assertEquals(SwingConstants.LEADING, label.horizontalAlignment)
          assertEquals(BorderLayout.WEST, (label.parent.layout as BorderLayout).getConstraints(label))
          assertEquals(0, iconPosition(500).x - iconPosition(400).x)
          list.cellRenderer.getListCellRendererComponent(list, list.model.getElementAt(2), 2, selected, false)
          assertFalse(label.isVisible)
        }
      }
      finally {
        Disposer.dispose(popup)
      }
    }
  }
}

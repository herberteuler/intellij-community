// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.awt.Component
import java.awt.Point
import javax.swing.JLabel

@TestApplication
@Timeout(30)
class JBTabsDropOverTest {
  @Test
  fun `drop preview shows the position after the last tab`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val disposable = Disposer.newDisposable()
      try {
        val tabs = JBTabsImpl(null, disposable)
        tabs.addTab(TabInfo(JLabel()).setText("A"))
        tabs.addTab(TabInfo(JLabel()).setText("B"))
        val last = tabs.addTab(TabInfo(JLabel()).setText("C"))
        tabs.setSize(1000, 200)
        tabs.doLayout()

        val lastBounds = tabs.getTabLabel(last)!!.bounds
        val pointAfterLastTab = pointOn(tabs, Point(lastBounds.x + lastBounds.width + 20, lastBounds.y + lastBounds.height / 2))
        val dropInfo = TabInfo(JLabel()).setText("dropped")

        tabs.startDropOver(dropInfo, pointAfterLastTab)
        tabs.doLayout()
        assertEquals(listOf("A", "B", "C", "dropped"), tabs.lastLayoutPass!!.visibleInfos.map { it.text })

        tabs.processDropOver(dropInfo, pointAfterLastTab)
        tabs.doLayout()
        assertEquals(listOf("A", "B", "C", "dropped"), tabs.lastLayoutPass!!.visibleInfos.map { it.text })

        tabs.resetDropOver(dropInfo)
      }
      finally {
        Disposer.dispose(disposable)
      }
    }
  }
}

/**
 * Returns a point on [component] that keeps its coordinates.
 *
 * [RelativePoint] returns `(0, 0)` for a component without a window, and a test component has no window.
 */
private fun pointOn(component: Component, point: Point): RelativePoint = object : RelativePoint(component, point) {
  override fun getPoint(aTargetComponent: Component?): Point {
    return if (aTargetComponent === component) Point(point) else super.getPoint(aTargetComponent)
  }
}

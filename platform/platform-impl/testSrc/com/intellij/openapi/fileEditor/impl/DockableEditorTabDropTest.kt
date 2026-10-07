// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.UiWithModelAccess
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.common.waitUntil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.fileEditorManagerFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.TabInfo
import kotlinx.coroutines.Dispatchers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JLabel

/**
 * Tests a tab drop into the editor window that the drag started from.
 *
 * The test runs the drag-out, the drop preview, the close of the source tab, and the drop in the same order as a mouse drag.
 */
@TestApplication
internal class DockableEditorTabDropTest {
  private val projectFixture = projectFixture(
    openProjectTask = OpenProjectTask {
      beforeInitTasks += { it.putUserData(FileEditorManagerKeys.ALLOW_IN_LIGHT_PROJECT, true) }
    },
    openAfterCreation = true,
  )
  private val managerFixture = projectFixture.fileEditorManagerFixture(initDockableContentFactory = true)

  @Test
  fun `drop on the preview after the last tab moves the tab to the end`(): Unit = timeoutRunBlocking(context = Dispatchers.UiWithModelAccess) {
    val order = dragAndDrop(dragged = "B") { tabs -> pointJustAfter(tabs.labelBounds("D")) }
    assertThat(order).containsExactly("A", "C", "D", "B")
  }

  @Test
  fun `drop on the first tab moves the tab to the start`(): Unit = timeoutRunBlocking(context = Dispatchers.UiWithModelAccess) {
    val order = dragAndDrop(dragged = "D") { tabs -> center(tabs.labelBounds("A")) }
    assertThat(order).containsExactly("D", "A", "B", "C")
  }

  @Test
  fun `drop on a later tab moves the tab before it`(): Unit = timeoutRunBlocking(context = Dispatchers.UiWithModelAccess) {
    val order = dragAndDrop(dragged = "A") { tabs -> center(tabs.labelBounds("D")) }
    assertThat(order).containsExactly("B", "C", "A", "D")
  }

  /**
   * Opens the tabs `A`, `B`, `C`, `D`, drags the [dragged] tab out, and drops it at the point that [dropPoint] returns.
   *
   * @return the tab names after the drop
   */
  private suspend fun dragAndDrop(dragged: String, dropPoint: (JBTabs) -> Point): List<String> {
    val manager = managerFixture.get()
    val files = listOf("A", "B", "C", "D").associateWith { LightVirtualFile("$it.txt") }
    for (file in files.values) {
      manager.openFile(file, true)
    }
    val window = requireNotNull(manager.currentWindow)
    val tabs = window.tabbedPane.editorTabs
    tabs.size = Dimension(1000, 200)
    tabs.doLayout()
    assertThat(window.tabNames()).containsExactly("A", "B", "C", "D")

    val file = files.getValue(dragged)
    val source = requireNotNull(tabs.findInfo(file))
    val isPinned = window.isFilePinned(file)
    (source.dragOutDelegate as EditorTabbedContainerDragOutDelegate).hideDragSource(source)
    tabs.doLayout()

    val container = manager.dockContainer as DockableEditorTabbedContainer
    val dropInfo = TabInfo(JLabel()).setText(dragged).setObject(file)
    container.setDropTarget(tabs, dropInfo)
    val point = pointOn(tabs, dropPoint(tabs))
    tabs.startDropOver(dropInfo, point)
    tabs.doLayout()
    // The next mouse move sees the drop preview under the pointer.
    tabs.processDropOver(dropInfo, point)
    tabs.doLayout()

    file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true)
    manager.closeFile(window = window, composite = source.composite, runChecks = false)
    val content = DockableEditor(img = null, file = file, presentation = Presentation(dragged), preferredSize = Dimension(),
                                 isPinned = isPinned, isSingletonEditorInWindow = false)
    container.add(content, point)
    container.resetDropOver(content)
    file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null)

    waitUntil { window.fileList.size == files.size }
    return window.tabNames()
  }
}

private fun EditorWindow.tabNames(): List<String> = fileList.map { it.nameWithoutExtension }

private fun JBTabs.labelBounds(name: String): Rectangle {
  val info = tabs.first { (it.`object` as VirtualFile).nameWithoutExtension == name }
  return requireNotNull(getTabLabel(info)).bounds
}

/** Returns a point just after [bounds], where the drop preview appears. */
private fun pointJustAfter(bounds: Rectangle): Point = Point(bounds.x + bounds.width + 5, bounds.y + bounds.height / 2)

private fun center(bounds: Rectangle): Point = Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)

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

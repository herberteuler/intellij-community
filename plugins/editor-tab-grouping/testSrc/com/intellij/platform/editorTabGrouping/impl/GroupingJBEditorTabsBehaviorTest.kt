// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.ide.ui.UISettings
import com.intellij.mock.MockProjectEx
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.DragHelper
import com.intellij.ui.tabs.impl.TabListOptions
import java.awt.Dimension
import java.awt.Point
import javax.swing.JLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

@TestApplication
@Timeout(30)
class GroupingJBEditorTabsBehaviorTest {

  @TestDisposable
  private lateinit var disposable: Disposable
  private lateinit var tabsDisposable: Disposable
  private lateinit var project: MockProjectEx
  private lateinit var contentRoot: VirtualFile
  private var savedGroupByDirectory: Boolean = false
  private var savedShowGroupNames: Boolean = false
  private lateinit var savedGroupLabelStyle: GroupLabelStyle

  @BeforeEach
  fun setUp(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      tabsDisposable = Disposer.newDisposable()
      Disposer.register(disposable, tabsDisposable)
      project = MockProjectEx(disposable)
      contentRoot = makeDir("/project")
      project.registerService(ProjectFileIndex::class.java, StubProjectFileIndex(contentRoot))
      savedGroupByDirectory = EditorTabGroupingSettings.getInstance().groupByDirectory
      savedShowGroupNames = EditorTabGroupingSettings.getInstance().showGroupNames
      savedGroupLabelStyle = EditorTabGroupingSettings.getInstance().groupLabelStyle
      EditorTabGroupingSettings.getInstance().groupByDirectory = true
      EditorTabGroupingSettings.getInstance().showGroupNames = true
      EditorTabGroupingSettings.getInstance().groupLabelStyle = GroupLabelStyle.OUTLINE
    }
  }

  @AfterEach
  fun tearDown(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      EditorTabGroupingSettings.getInstance().groupByDirectory = savedGroupByDirectory
      EditorTabGroupingSettings.getInstance().showGroupNames = savedShowGroupNames
      EditorTabGroupingSettings.getInstance().groupLabelStyle = savedGroupLabelStyle
      Disposer.dispose(tabsDisposable)
    }
  }

  @Test
  fun `hiding group names removes reserved space without disabling grouping`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabsWithAlphaGroup()
      val firstTab = tabs.getTabAt(0)
      assertTrue(tabs.getGroupHeaderHeight() > 0)

      EditorTabGroupingSettings.getInstance().showGroupNames = false

      assertEquals(0, tabs.getGroupHeaderHeight())
      assertEquals(0, tabs.additionalHeaderHeight())
      assertEquals(0, tabs.additionalTabLabelHeight(firstTab))
      assertNotNull(tabs.getPreferredInsertionIndex(firstTab.`object` as VirtualFile, 0, firstTab.isPinned))
    }
  }

  @Test
  fun `group label styles reserve the same height only when names are shown`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabsWithAlphaGroup()

      EditorTabGroupingSettings.getInstance().groupLabelStyle = GroupLabelStyle.OUTLINE
      val outlineHeight = tabs.getGroupHeaderHeight()
      val outlineContainerHeight = tabs.additionalHeaderHeight()
      EditorTabGroupingSettings.getInstance().groupLabelStyle = GroupLabelStyle.RAIL

      assertEquals(outlineHeight, tabs.getGroupHeaderHeight())
      assertEquals(outlineContainerHeight, tabs.additionalHeaderHeight())

      EditorTabGroupingSettings.getInstance().showGroupNames = false

      assertEquals(0, tabs.getGroupHeaderHeight())
    }
  }

  @Test
  fun `non-islands theme forces rail without group names`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs(isIslandsTheme = false).apply {
        addTabSilently(tab("alpha1", "/project/Alpha/Alpha1.kt"), tabCount)
        addTabSilently(tab("alpha2", "/project/Alpha/Alpha2.kt"), tabCount)
        addTabSilently(tab("beta1", "/project/Beta/Beta1.kt"), tabCount)
        addTabSilently(tab("beta2", "/project/Beta/Beta2.kt"), tabCount)
      }
      EditorTabGroupingSettings.getInstance().showGroupNames = true
      EditorTabGroupingSettings.getInstance().groupLabelStyle = GroupLabelStyle.OUTLINE

      assertEquals(0, tabs.getGroupHeaderHeight())
      assertEquals(GroupLabelStyle.RAIL, tabs.effectiveGroupLabelStyle())
    }
  }

  @Test
  fun `setTabsFromWindow hydrates restored tabs without reordering`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val beta = tab("beta", "/project/Beta/Beta.kt")
      val alpha = tab("alpha", "/project/Alpha/Alpha.kt")

      tabs.setTabsFromWindow(listOf(beta, alpha))

      assertEquals(listOf("beta", "alpha"), tabs.tabTexts())
      assertNotNull(tabs.groupFor(beta))
      assertNotNull(tabs.groupFor(alpha))
    }
  }

  @Test
  fun `addTabSilently inserts an ungrouped tab after the group`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
      val beta2 = tab("beta2", "/project/Beta/Beta2.kt")
      val gamma = tab("gamma", "/project/Gamma/Gamma.kt")
      val alpha = tab("alpha", "/project/Alpha/Alpha.kt")

      tabs.addTabSilently(beta1, tabs.tabCount)
      tabs.addTabSilently(beta2, tabs.tabCount)
      tabs.addTabSilently(gamma, tabs.tabCount)
      tabs.addTabSilently(alpha, tabs.tabCount)

      assertEquals(listOf("beta1", "beta2", "gamma", "alpha"), tabs.tabTexts())
    }
  }

  @Test
  fun `addTabSilently moves group before ungrouped tabs when second tab appears`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
      val beta2 = tab("beta2", "/project/Beta/Beta2.kt")
      val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
      val gamma = tab("gamma", "/project/Gamma/Gamma.kt")
      val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")

      tabs.addTabSilently(beta1, tabs.tabCount)
      tabs.addTabSilently(beta2, tabs.tabCount)
      tabs.addTabSilently(alpha1, tabs.tabCount)
      tabs.addTabSilently(gamma, tabs.tabCount)
      tabs.addTabSilently(alpha2, tabs.tabCount)

      assertEquals(listOf("alpha1", "alpha2", "beta1", "beta2", "gamma"), tabs.tabTexts())
      assertTrue(tabs.getGroupHeaderHeight() > 0)
    }
  }

  @Test
  fun `addTabSilently preserves explicit position at start of existing group`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabsWithAlphaGroup()
      val dropped = tab("dropped", "/project/Alpha/Dropped.kt")

      tabs.addTabSilently(dropped, 0)

      assertEquals(listOf("dropped", "alpha1", "alpha2", "alpha3", "beta1", "beta2"), tabs.tabTexts())
    }
  }

  @Test
  fun `addTabSilently preserves explicit position in middle of existing group`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabsWithAlphaGroup()
      val dropped = tab("dropped", "/project/Alpha/Dropped.kt")

      tabs.addTabSilently(dropped, 2)

      assertEquals(listOf("alpha1", "alpha2", "dropped", "alpha3", "beta1", "beta2"), tabs.tabTexts())
    }
  }

  @Test
  fun `addTabSilently preserves explicit position at end of existing group`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabsWithAlphaGroup()
      val dropped = tab("dropped", "/project/Alpha/Dropped.kt")

      tabs.addTabSilently(dropped, 3)

      assertEquals(listOf("alpha1", "alpha2", "alpha3", "dropped", "beta1", "beta2"), tabs.tabTexts())
    }
  }

  @Test
  fun `removeTab keeps the remaining group before the singleton`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
      val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
      val beta2 = tab("beta2", "/project/Beta/Beta2.kt")

      tabs.addTabSilently(alpha1, tabs.tabCount)
      tabs.addTabSilently(alpha2, tabs.tabCount)
      tabs.addTabSilently(beta1, tabs.tabCount)
      tabs.addTabSilently(beta2, tabs.tabCount)
      tabs.removeTab(alpha2)

      assertEquals(listOf("beta1", "beta2", "alpha1"), tabs.tabTexts())
      assertTrue(tabs.getGroupHeaderHeight() > 0)
    }
  }

  @Test
  fun `removeTab with selection transfer moves remaining singleton group member after later shown groups`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
      val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
      val beta2 = tab("beta2", "/project/Beta/Beta2.kt")
      val gamma1 = tab("gamma1", "/project/Gamma/Gamma1.kt")
      val gamma2 = tab("gamma2", "/project/Gamma/Gamma2.kt")

      tabs.addTabSilently(alpha1, tabs.tabCount)
      tabs.addTabSilently(alpha2, tabs.tabCount)
      tabs.addTabSilently(beta1, tabs.tabCount)
      tabs.addTabSilently(beta2, tabs.tabCount)
      tabs.addTabSilently(gamma1, tabs.tabCount)
      tabs.addTabSilently(gamma2, tabs.tabCount)
      tabs.removeTab(info = alpha2, forcedSelectionTransfer = beta1)

      assertEquals(listOf("beta1", "beta2", "gamma1", "gamma2", "alpha1"), tabs.tabTexts())
    }
  }

  @Test
  fun `left and right tabs reserve group header only for visible groups`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      for (position in listOf(JBTabsPosition.left, JBTabsPosition.right)) {
        val tabs = makeTabs()
        val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
        val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")
        val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
        val beta2 = tab("beta2", "/project/Beta/Beta2.kt")

        tabs.setTabsPosition(position)
        tabs.addTabSilently(alpha1, tabs.tabCount)
        tabs.addTabSilently(alpha2, tabs.tabCount)
        tabs.addTabSilently(beta1, tabs.tabCount)
        tabs.addTabSilently(beta2, tabs.tabCount)

        assertTrue(tabs.additionalTabLabelHeight(alpha1) > 0, position.name)
        assertEquals(0, tabs.additionalTabLabelHeight(alpha2), position.name)
        assertTrue(tabs.additionalTabLabelHeight(beta1) > 0, position.name)
        assertEquals(0, tabs.additionalTabLabelHeight(beta2), position.name)
      }
    }
  }

  @Test
  fun `enabling grouping refreshes group layout state immediately`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      EditorTabGroupingSettings.getInstance().groupByDirectory = false
      val tabs = makeTabs()
      tabs.setTabsPosition(JBTabsPosition.left)
      val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
      val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
      val beta2 = tab("beta2", "/project/Beta/Beta2.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, beta1, beta2))
      assertEquals(0, tabs.additionalTabLabelHeight(alpha1))

      EditorTabGroupingSettings.getInstance().groupByDirectory = true
      tabs.uiSettingsChanged(UISettings.getInstance())

      assertTrue(tabs.additionalTabLabelHeight(alpha1) > 0)
      assertEquals(0, tabs.additionalTabLabelHeight(alpha2))
    }
  }

  @Test
  fun `alphabetical mode delegates to regular tabs until grouping becomes active`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs().apply { setAlphabeticalMode(true) }
      val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
      val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2))
      assertTrue(tabs.isAlphabeticalMode())

      tabs.addTabSilently(beta1, tabs.tabCount)
      assertFalse(tabs.isAlphabeticalMode())
    }
  }

  @Test
  fun `active grouping applies alphabetical order within groups`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val settings = UISettings.getInstance()
      val savedSortTabsAlphabetically = settings.sortTabsAlphabetically
      val tabs = makeTabs()
      val alphaZ = tab("Z", "/project/Alpha/Z.kt")
      val alphaA = tab("A", "/project/Alpha/A.kt")
      val betaY = tab("Y", "/project/Beta/Y.kt")
      val betaB = tab("B", "/project/Beta/B.kt")

      try {
        tabs.setTabsFromWindow(listOf(betaY, alphaZ, betaB, alphaA))
        settings.sortTabsAlphabetically = true
        tabs.rearrangeTabsByGroup(rebuildCache = false)

        assertEquals(listOf("A", "Z", "B", "Y"), tabs.visibleTabTexts())
      }
      finally {
        settings.sortTabsAlphabetically = savedSortTabsAlphabetically
      }
    }
  }

  @Test
  fun `dock insertion cannot reorder an alphabetically sorted group`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val settings = UISettings.getInstance()
      val savedSortTabsAlphabetically = settings.sortTabsAlphabetically
      val tabs = makeTabsWithAlphaGroup()
      val dropped = tab("alpha0", "/project/Alpha/Alpha0.kt")

      try {
        settings.sortTabsAlphabetically = true
        tabs.rearrangeTabsByGroup(rebuildCache = false)
        tabs.addTabSilently(dropped, 3)

        assertEquals(listOf("alpha0", "alpha1", "alpha2", "alpha3", "beta1", "beta2"), tabs.visibleTabTexts())
      }
      finally {
        settings.sortTabsAlphabetically = savedSortTabsAlphabetically
      }
    }
  }

  @Test
  fun `grouping blocks same-bar reorder into foreign group only`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
      val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")
      val alpha3 = tab("alpha3", "/project/Alpha/Alpha3.kt")
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
      val beta2 = tab("beta2", "/project/Beta/Beta2.kt")

      tabs.addTabSilently(alpha1, tabs.tabCount)
      tabs.addTabSilently(alpha2, tabs.tabCount)
      tabs.addTabSilently(alpha3, tabs.tabCount)
      tabs.addTabSilently(beta1, tabs.tabCount)
      tabs.addTabSilently(beta2, tabs.tabCount)

      assertTrue(tabs.canReallocateTo(alpha1, alpha2))
      assertFalse(tabs.canReallocateTo(alpha1, beta1))
      assertFalse(tabs.canReallocateTo(alpha1, beta2))
    }
  }

  @Test
  fun `same-bar reorder allows only remaining source-group boundaries`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("A", "/project/Alpha/A.kt")
      val alpha2 = tab("B", "/project/Alpha/B.kt")
      val alpha3 = tab("C", "/project/Alpha/C.kt")
      val delta = tab("D", "/project/Delta/D.kt")
      val delta2 = tab("D2", "/project/Delta/D2.kt")
      val epsilon = tab("E", "/project/Epsilon/E.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, alpha3, delta, delta2, epsilon))

      assertTrue(tabs.canReallocateTo(alpha1, alpha2))
      assertTrue(tabs.canReallocateTo(alpha1, alpha3))
      assertFalse(tabs.canReallocateTo(alpha1, delta))
      assertFalse(tabs.canReallocateTo(alpha1, epsilon))
    }
  }

  @Test
  fun `dragging cannot split a group from the other tabs`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("A", "/project/Alpha/A.kt")
      val alpha2 = tab("B", "/project/Alpha/B.kt")
      val alpha3 = tab("C", "/project/Alpha/C.kt")
      val delta = tab("D", "/project/Delta/D.kt")
      val epsilon = tab("E", "/project/Epsilon/E.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, alpha3, delta, epsilon))

      assertFalse(tabs.canReallocateTo(alpha1, epsilon))
      assertFalse(tabs.isDropIndexAllowedForTest(4, alpha1))

      tabs.reallocate(alpha1, epsilon)

      assertEquals(listOf("A", "B", "C", "D", "E"), tabs.visibleTabTexts())
    }
  }

  @Test
  fun `dragging in the smallest active layout keeps the group together`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val first = tab("A1", "/project/Alpha/A1.kt")
      val second = tab("A2", "/project/Alpha/A2.kt")
      val singleton = tab("B", "/project/Beta/B.kt")
      tabs.setTabsFromWindow(listOf(first, second, singleton))

      assertTrue(tabs.canReallocateTo(first, second))
      assertFalse(tabs.canReallocateTo(first, singleton))
      assertFalse(tabs.canReallocateTo(singleton, second))
      assertFalse(tabs.isDropIndexAllowedForTest(1, singleton))
      tabs.reallocate(singleton, second)
      assertEquals(listOf("A1", "A2", "B"), tabs.visibleTabTexts())
    }
  }

  @Test
  fun `drop insertion keeps the only group together`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("A", "/project/Alpha/A.kt")
      val alpha2 = tab("B", "/project/Alpha/B.kt")
      val delta = tab("D", "/project/Delta/D.kt")
      val epsilon = tab("E", "/project/Epsilon/E.kt")
      val droppedAlpha = tab("dropped A", "/project/Alpha/Dropped.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, delta, epsilon))

      tabs.addTabSilently(droppedAlpha, tabs.tabCount)

      assertEquals(listOf("A", "B", "dropped A", "D", "E"), tabs.tabTexts())
    }
  }

  @Test
  fun `hidden tabs remain logical group members`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      //Tabs become hidden during dragging.
      //If we're to ignore hidden tabs, dragging out of the two-tabs group may result in breaking drop off lookup logic
      val tabs = makeTabs()
      val alpha1 = tab("A", "/project/Alpha/A.kt")
      val alpha2 = tab("B", "/project/Alpha/B.kt")
      val delta = tab("D", "/project/Delta/D.kt")
      val epsilon = tab("E", "/project/Epsilon/E.kt")
      val hiddenBeta1 = tab("hidden B1", "/project/Beta/B1.kt")
      val hiddenBeta2 = tab("hidden B2", "/project/Beta/B2.kt")

      for (info in listOf(alpha1, alpha2, delta, epsilon, hiddenBeta1, hiddenBeta2)) {
        tabs.addTabSilently(info, tabs.tabCount)
      }
      assertTrue(tabs.getGroupHeaderHeight() > 0)

      hiddenBeta1.isHidden = true
      hiddenBeta2.isHidden = true

      assertEquals(listOf("A", "B", "D", "E"), tabs.visibleTabTexts())
      assertTrue(tabs.getGroupHeaderHeight() > 0)
    }
  }

  @Test
  fun `hidden drag source preserves its two-tab group lookup`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      tabs.setTabsPosition(JBTabsPosition.left)
      val alpha1 = tab("A1", "/project/Alpha/A1.kt")
      val alpha2 = tab("A2", "/project/Alpha/A2.kt")
      val beta1 = tab("B1", "/project/Beta/B1.kt")
      val beta2 = tab("B2", "/project/Beta/B2.kt")
      val gamma1 = tab("G1", "/project/Gamma/G1.kt")
      val gamma2 = tab("G2", "/project/Gamma/G2.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, beta1, beta2, gamma1, gamma2))
      alpha1.isHidden = true

      assertEquals(listOf("A2", "B1", "B2", "G1", "G2"), tabs.visibleTabTexts())
      assertTrue(tabs.additionalTabLabelHeight(alpha2) > 0)
      assertTrue(tabs.isDropIndexAllowedForTest(0, alpha1))
      assertTrue(tabs.isDropIndexAllowedForTest(1, alpha1))
      assertFalse(tabs.isDropIndexAllowedForTest(2, alpha1))
    }
  }

  @Test
  fun `grouped insertion is unavailable while grouping is inactive`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("A1", "/project/Alpha/A1.kt")
      val alpha2 = tab("A2", "/project/Alpha/A2.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2))

      assertNull(tabs.getPreferredInsertionIndex(tab("A3", "/project/Alpha/A3.kt").`object` as VirtualFile, 0, false))
    }
  }

  @Test
  fun `unresolved files use the default insertion index`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabsWithAlphaGroup()

      val index = tabs.getPreferredInsertionIndex(tab("A4", "/project/Alpha/A4.kt").`object` as VirtualFile, 0, false)

      assertNull(index)
    }
  }

  @Test
  fun `drop preview stays constrained when another group remains`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha = tab("A", "/project/Alpha/A.kt")
      val beta1 = tab("B1", "/project/Beta/B1.kt")
      val beta2 = tab("B2", "/project/Beta/B2.kt")
      val draggedAlpha = tab("dragged A", "/project/Alpha/Dragged.kt")

      tabs.setTabsFromWindow(listOf(alpha, beta1, beta2, draggedAlpha))

      assertTrue(tabs.isDropIndexAllowedForTest(0, draggedAlpha))
      assertFalse(tabs.isDropIndexAllowedForTest(3, draggedAlpha))
    }
  }

  @Test
  fun `drop preview rejects raw boundary after remaining source group`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("A", "/project/Alpha/A.kt")
      val alpha2 = tab("B", "/project/Alpha/B.kt")
      val alpha3 = tab("C", "/project/Alpha/C.kt")
      val delta = tab("D", "/project/Delta/D.kt")
      val delta2 = tab("D2", "/project/Delta/D2.kt")
      val epsilon = tab("E", "/project/Epsilon/E.kt")
      val draggedAlpha = tab("dragged A", "/project/Alpha/A.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, alpha3, delta, delta2, epsilon))

      tabs.startDropOver(draggedAlpha, RelativePoint(tabs, Point(1, 1)))
      assertTrue(tabs.isDropIndexAllowedForTest(1, draggedAlpha))
      assertTrue(tabs.isDropIndexAllowedForTest(2, draggedAlpha))
      assertTrue(tabs.isDropIndexAllowedForTest(3, draggedAlpha))
      assertFalse(tabs.isDropIndexAllowedForTest(4, draggedAlpha))
      tabs.resetDropOver(draggedAlpha)
    }
  }

  @Test
  fun `illegal same-bar reorder target does not consume drag-out routing`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("A", "/project/Alpha/A.kt")
      val alpha2 = tab("B", "/project/Alpha/B.kt")
      val alpha3 = tab("C", "/project/Alpha/C.kt")
      val delta = tab("D", "/project/Delta/D.kt")
      val delta2 = tab("D2", "/project/Delta/D2.kt")
      val epsilon = tab("E", "/project/Epsilon/E.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, alpha3, delta, delta2, epsilon))

      val sameBarReorderAllowed = tabs.canReallocateTo(alpha1, epsilon)

      assertFalse(sameBarReorderAllowed)
      assertFalse(DragHelper.shouldConsumeDragEventAfterReallocateAttempt(epsilon, sameBarReorderAllowed))
      assertTrue(DragHelper.shouldConsumeDragEventAfterReallocateAttempt(alpha2, tabs.canReallocateTo(alpha1, alpha2)))
      assertTrue(DragHelper.shouldConsumeDragEventAfterReallocateAttempt(null, false))
    }
  }

  @Test
  fun `same-bar reorder swaps adjacent grouped tabs`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("A", "/project/Alpha/A.kt")
      val alpha2 = tab("B", "/project/Alpha/B.kt")
      val alpha3 = tab("C", "/project/Alpha/C.kt")
      val delta = tab("D", "/project/Delta/D.kt")
      val delta2 = tab("D2", "/project/Delta/D2.kt")
      val epsilon = tab("E", "/project/Epsilon/E.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, alpha3, delta, delta2, epsilon))

      tabs.reallocate(alpha1, alpha2)

      assertEquals(listOf("B", "A", "C"), tabs.tabTexts().take(3))
    }
  }

  @Test
  fun `same-bar reorder reaches the final grouped sibling`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("A", "/project/Alpha/A.kt")
      val alpha2 = tab("B", "/project/Alpha/B.kt")
      val alpha3 = tab("C", "/project/Alpha/C.kt")
      val delta = tab("D", "/project/Delta/D.kt")
      val delta2 = tab("D2", "/project/Delta/D2.kt")
      val epsilon = tab("E", "/project/Epsilon/E.kt")

      tabs.setTabsFromWindow(listOf(alpha1, alpha2, alpha3, delta, delta2, epsilon))

      tabs.reallocate(alpha1, alpha3)

      assertEquals(listOf("B", "C", "A"), tabs.tabTexts().take(3))
    }
  }

  @Test
  fun `resetDropOver removes temporary drop target without regrouping regular tabs`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
      val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
      val beta2 = tab("beta2", "/project/Beta/Beta2.kt")
      val dragged = tab("dragged", "/project/Alpha/Dragged.kt")

      tabs.addTabSilently(alpha1, tabs.tabCount)
      tabs.addTabSilently(alpha2, tabs.tabCount)
      tabs.addTabSilently(beta1, tabs.tabCount)
      tabs.addTabSilently(beta2, tabs.tabCount)

      assertDoesNotThrow {
        tabs.startDropOver(dragged, RelativePoint(tabs, Point(1, 1)))
        tabs.resetDropOver(dragged)
      }

      assertEquals(listOf("alpha1", "alpha2", "beta1", "beta2"), tabs.tabTexts())
    }
  }

  @Test
  fun `temporary drop target cleanup keeps later close operations valid`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val alpha1 = tab("alpha1", "/project/Alpha/Alpha1.kt")
      val alpha2 = tab("alpha2", "/project/Alpha/Alpha2.kt")
      val beta1 = tab("beta1", "/project/Beta/Beta1.kt")
      val beta2 = tab("beta2", "/project/Beta/Beta2.kt")
      val gamma1 = tab("gamma1", "/project/Gamma/Gamma1.kt")
      val gamma2 = tab("gamma2", "/project/Gamma/Gamma2.kt")
      val dragged = tab("dragged", "/project/Beta/Dragged.kt")

      tabs.addTabSilently(alpha1, tabs.tabCount)
      tabs.addTabSilently(alpha2, tabs.tabCount)
      tabs.addTabSilently(beta1, tabs.tabCount)
      tabs.addTabSilently(beta2, tabs.tabCount)
      tabs.addTabSilently(gamma1, tabs.tabCount)
      tabs.addTabSilently(gamma2, tabs.tabCount)

      assertDoesNotThrow {
        tabs.startDropOver(dragged, RelativePoint(tabs, Point(1, 1)))
        tabs.resetDropOver(dragged)
        tabs.removeTab(beta2)
      }

      assertEquals(listOf("alpha1", "alpha2", "gamma1", "gamma2", "beta1"), tabs.tabTexts())
      assertNotNull(tabs.groupFor(beta1))
    }
  }

  @Test
  fun `restoration clusters interleaved groups`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      tabs.setTabsFromWindow(listOf(
        tab("A1", "/project/Alpha/A1.kt"), tab("B1", "/project/Beta/B1.kt"),
        tab("A2", "/project/Alpha/A2.kt"), tab("B2", "/project/Beta/B2.kt"),
      ))
      assertEquals(listOf("A1", "A2", "B1", "B2"), tabs.visibleTabTexts())
    }
  }

  @Test
  fun `cross-window preview cancellation retains source membership`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val source = makeTabsWithAlphaGroup()
      val target = makeTabsWithAlphaGroup()
      val dragged = source.getTabAt(0)
      val preview = TabInfo(JLabel()).setObject(dragged.`object`).setText("preview")
      source.onDragStateChanged(true)
      dragged.isHidden = true
      target.startDropOver(preview, RelativePoint(target, Point(1, 1)))
      assertEquals(5, source.logicalGroupSizes().values.sum())
      assertEquals(5, target.logicalGroupSizes().values.sum())
      assertTrue(target.isDropIndexAllowedForTest(2, preview))
      assertFalse(target.isDropIndexAllowedForTest(4, preview))
      target.resetDropOver(preview)
      dragged.isHidden = false
      source.onDragStateChanged(false)
      assertEquals(listOf("alpha1", "alpha2", "alpha3", "beta1", "beta2"), source.visibleTabTexts())
      assertEquals(5, target.retainedFileCount)
    }
  }

  @Test
  fun `drop preview after the last tab follows the group boundaries`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val source = makeTabsWithAlphaGroup()
      val target = makeTabsWithAlphaGroup()
      val afterLastTab = -1

      val alphaPreview = TabInfo(JLabel()).setObject(source.getTabAt(0).`object`).setText("alpha preview")
      target.startDropOver(alphaPreview, RelativePoint(target, Point(1, 1)))
      assertFalse(target.isDropIndexAllowedForTest(afterLastTab, alphaPreview))
      target.resetDropOver(alphaPreview)

      val betaPreview = TabInfo(JLabel()).setObject(source.getTabAt(3).`object`).setText("beta preview")
      target.startDropOver(betaPreview, RelativePoint(target, Point(1, 1)))
      assertTrue(target.isDropIndexAllowedForTest(afterLastTab, betaPreview))
      target.resetDropOver(betaPreview)
    }
  }

  @Test
  fun `drop preview takes the file and the pinned state of the dragged tab`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabsWithAlphaGroup()
      val file = makeFile("/project/Alpha/Dragged.kt")
      val preview = TabInfo(JLabel()).setText("preview")

      tabs.prepareDropPreview(preview, file, isPinned = true)

      assertEquals(file, preview.`object`)
      assertTrue(preview.isPinned)
    }
  }

  @Test
  fun `disabled grouping allows every drop position and keeps the default insertion index`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      EditorTabGroupingSettings.getInstance().groupByDirectory = false
      val tabs = makeTabsWithAlphaGroup()
      val first = tabs.getTabAt(0)
      val last = tabs.getTabAt(tabs.tabCount - 1)
      val file = makeFile("/project/Alpha/Dragged.kt")
      val preview = TabInfo(JLabel()).setText("preview")
      tabs.prepareDropPreview(preview, file, isPinned = false)

      tabs.startDropOver(preview, RelativePoint(tabs, Point(1, 1)))
      for (index in -1..tabs.tabCount) {
        assertTrue(tabs.isDropIndexAllowedForTest(index, preview), "index $index")
      }
      tabs.resetDropOver(preview)
      assertNull(tabs.getPreferredInsertionIndex(file, currentTabIndex = 0, isPinned = false))

      tabs.reallocate(first, last)
      assertEquals(listOf("alpha2", "alpha3", "beta1", "beta2", "alpha1"), tabs.visibleTabTexts())
    }
  }

  @Test
  fun `cross-window commit adds membership only after preview cleanup`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val source = makeTabsWithAlphaGroup()
      val target = makeTabsWithAlphaGroup()
      val dragged = source.getTabAt(0)
      val preview = TabInfo(JLabel()).setObject(dragged.`object`).setText("preview")
      source.onDragStateChanged(true)
      dragged.isHidden = true
      target.startDropOver(preview, RelativePoint(target, Point(1, 1)))
      target.resetDropOver(preview)
      source.removeTab(dragged)
      source.onDragStateChanged(false)
      val inserted = TabInfo(JLabel()).setObject(dragged.`object`).setText("inserted")
      target.addTabSilently(inserted, 2)
      assertEquals(listOf("alpha1", "alpha2", "inserted", "alpha3", "beta1", "beta2"), target.visibleTabTexts())
      assertEquals(6, target.logicalGroupSizes().values.sum())
      assertEquals(4, source.retainedFileCount)
    }
  }

  @Test
  fun `pinned and unpinned groups have separate drop boundaries`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val pinned = (1..2).map { index ->
        tab("p$index", "/project/Alpha/p$index.kt").also {
          com.intellij.ui.ClientProperty.put(it.component, com.intellij.ui.tabs.impl.JBTabsImpl.PINNED, true)
        }
      }
      val unpinned = (1..2).map { index -> tab("u$index", "/project/Alpha/u$index.kt") }
      tabs.setTabsFromWindow(pinned + unpinned)
      assertTrue(tabs.canReallocateTo(pinned[0], pinned[1]))
      assertFalse(tabs.canReallocateTo(pinned[0], unpinned[0]))
      assertTrue(tabs.canReallocateTo(unpinned[1], unpinned[0]))
      assertFalse(tabs.canReallocateTo(unpinned[1], pinned[1]))
      tabs.select(unpinned[1], false)
      tabs.rearrangeTabsByGroup()
      assertEquals(unpinned[1], tabs.selectedInfo)
    }
  }

  @Test
  fun `delayed resolution preserves an explicit position inside a group`(): Unit = timeoutRunBlocking {
    val resolved = kotlinx.coroutines.CompletableDeferred<Unit>()
    var requests = 0
    val resolver = object : DirectoryGroupResolver(project) {
      override suspend fun resolve(files: List<VirtualFile>): Map<VirtualFile, TabGroup?> {
        if (++requests > 1) resolved.await()
        return files.associateWith { EditorTabGroupingProvider.resolveDirectoryGroup(it, project) }
      }
    }
    val scope = this
    val tabs = withContext(Dispatchers.EDT) {
      GroupingJBEditorTabs(project, tabsDisposable, scope, TabListOptions(), createTestEditorWindow(project, tabsDisposable), resolver = resolver).also {
        it.setTabsFromWindow(listOf(
          tab("A1", "/project/Alpha/A1.kt"), tab("A2", "/project/Alpha/A2.kt"),
          tab("A3", "/project/Alpha/A3.kt"), tab("B1", "/project/Beta/B1.kt"),
        ))
        it.addTabSilently(tab("inserted", "/project/Alpha/Inserted.kt"), 1)
        assertEquals("inserted", it.getTabAt(1).text)
      }
    }
    val start = System.nanoTime()
    withContext(Dispatchers.EDT) { assertEquals("inserted", tabs.getTabAt(1).text) }
    println("Grouping EDT turn during pending resolution: durationNs=${System.nanoTime() - start}")
    resolved.complete(Unit)
    tabs.awaitGroupResolution()
    val order = withContext(Dispatchers.EDT) { tabs.tabTexts() }
    assertEquals(listOf("A1", "inserted", "A2", "A3", "B1"), order)
  }

  private suspend fun makeTabs(isIslandsTheme: Boolean = true): TestGroupingJBEditorTabs {
    val scope = CoroutineScope(currentCoroutineContext())
    return TestGroupingJBEditorTabs(project, tabsDisposable, scope, TabListOptions(), isIslandsTheme).apply {
      size = Dimension(800, 200)
    }
  }

  private suspend fun makeTabsWithAlphaGroup(): TestGroupingJBEditorTabs {
    return makeTabs().apply {
      addTabSilently(tab("alpha1", "/project/Alpha/Alpha1.kt"), tabCount)
      addTabSilently(tab("alpha2", "/project/Alpha/Alpha2.kt"), tabCount)
      addTabSilently(tab("alpha3", "/project/Alpha/Alpha3.kt"), tabCount)
      addTabSilently(tab("beta1", "/project/Beta/Beta1.kt"), tabCount)
      addTabSilently(tab("beta2", "/project/Beta/Beta2.kt"), tabCount)
    }
  }

  private fun tab(name: String, path: String): TabInfo =
    TabInfo(JLabel(name)).setObject(makeFile(path)).apply { setText(name) }

  private fun GroupingJBEditorTabs.tabTexts(): List<String> =
    (0 until tabCount).map { getTabAt(it).text }

  private fun makeDir(path: String): VirtualFile {
    val name = path.substringAfterLast('/', path)
    val parentPath = path.substringBeforeLast('/')
    val parent: VirtualFile? = if (parentPath.isNotEmpty() && parentPath != path) makeDir(parentPath) else null
    return object : LightVirtualFile(name) {
      override fun getPath(): String = path
      override fun isDirectory(): Boolean = true
      override fun getParent(): VirtualFile? = parent
    }
  }

  private fun makeFile(path: String): VirtualFile {
    val parentPath = path.substringBeforeLast('/')
    val name = path.substringAfterLast('/')
    val parent = makeDir(parentPath)
    return object : LightVirtualFile(name) {
      override fun getPath(): String = path
      override fun isDirectory(): Boolean = false
      override fun getParent(): VirtualFile = parent
    }
  }
}

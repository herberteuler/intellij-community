// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

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
import com.intellij.ui.tabs.JBTabsPosition
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.TabListOptions
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Counts the work on the hot paths of tab grouping.
 *
 * Each test maps to one goal of the performance work. The tests count operations instead of
 * time, because a wall-clock assertion is not stable on a shared agent.
 */
@TestApplication
@Timeout(30)
class GroupingTabsPerformanceTest {

  @TestDisposable
  private lateinit var disposable: Disposable
  private lateinit var tabsDisposable: Disposable
  private lateinit var project: MockProjectEx
  private lateinit var fileIndex: StubProjectFileIndex
  private var savedGroupByDirectory: Boolean = false
  private var savedShowGroupNames: Boolean = false

  @BeforeEach
  fun setUp(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      tabsDisposable = Disposer.newDisposable()
      Disposer.register(disposable, tabsDisposable)
      project = MockProjectEx(disposable)
      fileIndex = StubProjectFileIndex(makeDir("/project"))
      project.registerService(ProjectFileIndex::class.java, fileIndex)
      savedGroupByDirectory = EditorTabGroupingSettings.getInstance().groupByDirectory
      savedShowGroupNames = EditorTabGroupingSettings.getInstance().showGroupNames
      EditorTabGroupingSettings.getInstance().groupByDirectory = true
      EditorTabGroupingSettings.getInstance().showGroupNames = true
    }
  }

  @AfterEach
  fun tearDown(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      EditorTabGroupingSettings.getInstance().groupByDirectory = savedGroupByDirectory
      EditorTabGroupingSettings.getInstance().showGroupNames = savedShowGroupNames
      Disposer.dispose(tabsDisposable)
    }
  }

  @Test
  fun `disabled grouping performs no index lookups`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      EditorTabGroupingSettings.getInstance().groupByDirectory = false
      makeTabs(tabCount = 50)
      assertEquals(0, fileIndex.contentRootCallCount)
    }
  }

  @Test
  fun `repeated drop checks reuse the range at every tab count`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      for (count in listOf(50, 200, 1000)) {
        val tabs = makeTabs(tabCount = count - 2)
        val singleton = tab("singleton", "/project/Single/a.kt")
        val other = tab("other", "/project/Other/b.kt")
        tabs.addTabSilently(singleton, tabs.tabCount)
        tabs.addTabSilently(other, tabs.tabCount)
        val sources = listOf(tabs.getTabAt(0) to tabs.getTabAt(1), singleton to other)
        for ((source, target) in sources) {
          repeat(100) { assertTrue(tabs.canReallocateTo(source, target)) }
          val builds = tabs.dropRangeBuildCount
          val bean = java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
          val threadId = Thread.currentThread().threadId()
          val bytes = bean?.getThreadAllocatedBytes(threadId) ?: 0
          val start = System.nanoTime()
          repeat(10000) { tabs.canReallocateTo(source, target) }
          val elapsed = System.nanoTime() - start
          val allocated = (bean?.getThreadAllocatedBytes(threadId) ?: 0) - bytes
          println("Grouping drag: tabs=$count source=${source.text} checks=10000 durationNs=$elapsed allocatedBytes=$allocated")
          assertEquals(builds, tabs.dropRangeBuildCount)
        }
        assertEquals(2, tabs.dropRangeBuildCount)
      }
    }
  }

  @Test
  fun `bulk regrouping requests one reorder`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      for (count in listOf(50, 200, 1000)) {
        val tabs = makeTabs(tabCount = count)
        tabs.sortTabs(compareByDescending { it.text })
        tabs.resetCounters()
        tabs.rearrangeTabsByGroup()
        assertEquals(1, tabs.tabMoveCount)
      }
    }
  }

  @Test
  fun `hidden group names perform no disambiguation`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs(tabCount = 12)
      EditorTabGroupingSettings.getInstance().showGroupNames = false
      tabs.doLayout()
      paintOnce(tabs)
      assertEquals(0, tabs.groupNamesBuildCount)
    }
  }

  @Test
  fun `production resolver queries the index away from EDT`(): Unit = timeoutRunBlocking {
    val files = withContext(Dispatchers.EDT) { listOf(makeFile("/project/Alpha/a.kt")) }
    val resolved = DirectoryGroupResolver(project).resolve(files)
    assertEquals("Alpha", resolved.getValue(files.single())?.name)
    assertEquals(0, fileIndex.edtLookupCount)
  }

  @Test
  fun `wrapped rows paint at multiple scales`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val oldScale = com.intellij.ui.scale.JBUIScale.scale(1f)
      try {
        for (scale in listOf(1f, 1.5f, 2f)) {
          com.intellij.ui.scale.JBUIScale.setUserScaleFactorForTest(scale)
          for (names in listOf(false, true)) {
            EditorTabGroupingSettings.getInstance().showGroupNames = names
            val tabs = makeTabs(12, singleRow = false)
            tabs.size = Dimension(400, 400)
            tabs.doLayout()
            paintOnce(tabs)
            assertEquals(1, tabs.outlineBuildCount)
            if (names) assertFalse(tabs.labelPaints.isEmpty())
          }
        }
      }
      finally {
        com.intellij.ui.scale.JBUIScale.setUserScaleFactorForTest(oldScale)
      }
    }
  }

  // Goal 1: opening one tab resolves at most one file through the file index.
  @Test
  fun `opening a tab resolves only the new file`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs(tabCount = 50)
      tabs.rearrangeTabsByGroup()
      fileIndex.resetCounters()

      tabs.addTabSilently(tab("extra", "/project/Alpha/Extra.kt"), tabs.tabCount)
      tabs.rearrangeTabsByGroup()

      assertEquals(1, fileIndex.contentRootCallCount)
    }
  }

  // Goal 2: a rearrange performs no new index lookup.
  @Test
  fun `rearranging performs no index lookups`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs(tabCount = 50)
      tabs.rearrangeTabsByGroup()
      fileIndex.resetCounters()

      tabs.rearrangeTabsByGroup()

      assertEquals(0, fileIndex.contentRootCallCount)
    }
  }

  // Goal 2: only a content root change may drop the memo.
  @Test
  fun `a content root change clears the memo`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs(tabCount = 10)
      tabs.rearrangeTabsByGroup()
      fileIndex.resetCounters()

      tabs.onContentRootsChanged()

      assertEquals(10, fileIndex.contentRootCallCount)
    }
  }

  // Goal 3: at most one outline build per layout, not one per paint.
  @Test
  fun `repeated paints build the outline once`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs(tabCount = 3)
      tabs.doLayout()
      tabs.resetCounters()

      paintOnce(tabs)
      paintOnce(tabs)
      paintOnce(tabs)
      assertEquals(1, tabs.outlineBuildCount)
      assertFalse(tabs.labelPaints.isEmpty())

      tabs.doLayout()
      paintOnce(tabs)
      assertEquals(2, tabs.outlineBuildCount)
    }
  }

  // Goal 4: the reserved label height per tab is constant time.
  @Test
  fun `label height is constant time per tab`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      assertEquals(visibleInfosCallsForLabelHeights(50), visibleInfosCallsForLabelHeights(200))
    }
  }

  // Goal 5: a layout derives the label font at most one time.
  @Test
  fun `a layout derives the font at most once`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs(tabCount = 200)
      tabs.resetCounters()

      tabs.doLayout()
      for (i in 0 until tabs.tabCount) {
        tabs.additionalTabLabelHeight(tabs.getTabAt(i))
      }

      assertEquals(1, tabs.fontHeightComputeCount)
    }
  }

  // Goal 6: the drop-position helpers allocate no collection, so they return a range.
  @Test
  fun `the drop position helpers return a range`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val list = listOf(false to "a", false to "a", false to "b", false to "b")

      val positions: IntRange = computeConstrainedDropPositions(list, draggedIsPinned = false, draggedGroupKey = "a")

      assertEquals(0..2, positions)
      assertTrue(1 in positions)
    }
  }

  // Goal 7: a correctly ordered tab list triggers no reorder.
  @Test
  fun `an already-grouped list triggers no reorder`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs(tabCount = 50)
      tabs.rearrangeTabsByGroup()
      tabs.resetCounters()

      tabs.rearrangeTabsByGroup()

      assertEquals(0, tabs.tabMoveCount)
    }
  }

  // --- the group label must stay inside the group area ---

  @Test
  fun `a wide group keeps every part`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val plan = planGroupLabel(labelSpace = 200, circleWidth = 10, gap = 4, badgeWidth = 20)

      assertTrue(plan.drawCircle)
      assertTrue(plan.drawBadge)
      assertEquals(166, plan.textSpace)
    }
  }

  @Test
  fun `a narrow group drops the badge first`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val plan = planGroupLabel(labelSpace = 30, circleWidth = 10, gap = 4, badgeWidth = 20)

      assertTrue(plan.drawCircle)
      assertFalse(plan.drawBadge)
      // The badge gives its space back to the text.
      assertEquals(20, plan.textSpace)
    }
  }

  @Test
  fun `a narrower group drops the text`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val plan = planGroupLabel(labelSpace = 10, circleWidth = 10, gap = 4, badgeWidth = 20)

      assertTrue(plan.drawCircle)
      assertFalse(plan.drawBadge)
      assertEquals(0, plan.textSpace)
    }
  }

  @Test
  fun `a group with no room drops the circle`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val plan = planGroupLabel(labelSpace = 9, circleWidth = 10, gap = 4, badgeWidth = 20)

      assertFalse(plan.drawCircle)
      assertFalse(plan.drawBadge)
      assertEquals(0, plan.textSpace)
    }
  }

  @Test
  fun `the badge boundary is exact`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val fits = planGroupLabel(labelSpace = 34, circleWidth = 10, gap = 4, badgeWidth = 20)
      val oneShort = planGroupLabel(labelSpace = 33, circleWidth = 10, gap = 4, badgeWidth = 20)

      assertTrue(fits.drawBadge)
      assertEquals(0, fits.textSpace)
      assertFalse(oneShort.drawBadge)
    }
  }

  @Test
  fun `the group label stays inside the group area`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      for (position in JBTabsPosition.entries) {
        for (componentWidth in listOf(1200, 400, 160, 60)) {
          val tabs = makeTabs(tabCount = 12, position = position)
          tabs.size = Dimension(componentWidth, 400)
          tabs.doLayout()
          tabs.resetCounters()

          paintOnce(tabs)

          for ((areaX, areaWidth, drawnWidth) in tabs.labelPaints) {
            assertTrue(
              drawnWidth <= areaWidth,
              "position=$position width=$componentWidth areaX=$areaX drawn=$drawnWidth area=$areaWidth",
            )
          }
        }
      }
    }
  }

  // --- behavior guards for the rewritten helpers ---

  @Test
  fun `truncateToFit keeps its output after the binary search rewrite`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val label = JLabel()
      val fm = label.getFontMetrics(label.font)
      val text = "VeryLongDirectoryName"
      val full = fm.stringWidth(text)

      assertEquals("", truncateToFit(text, fm, fm.stringWidth("…") - 1))
      assertEquals(text, truncateToFit(text, fm, full))
      assertEquals(text, truncateToFit(text, fm, full + 100))
      val short = truncateToFit(text, fm, full - 1)
      assertTrue(short.endsWith("…"))
      assertTrue(fm.stringWidth(short) <= full - 1)
    }
  }

  @Test
  fun `disambiguateVisibleGroupName keeps its output for a shared name`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val one = TabGroup("src", "/rootA/src")
      val two = TabGroup("src", "/rootB/src")
      val visible = listOf(one, two)

      assertEquals("rootA/src", disambiguateVisibleGroupName(one, visible))
      assertEquals("rootB/src", disambiguateVisibleGroupName(two, visible))
      assertEquals("src", disambiguateVisibleGroupName(one, listOf(one)))
    }
  }

  // --- helpers ---

  private suspend fun visibleInfosCallsForLabelHeights(count: Int): Int {
    val tabs = makeTabs(tabCount = count, position = JBTabsPosition.left)
    tabs.doLayout()
    tabs.resetCounters()
    for (i in 0 until tabs.tabCount) {
      tabs.additionalTabLabelHeight(tabs.getTabAt(i))
    }
    return tabs.visibleInfosCallCount
  }

  private fun paintOnce(tabs: TestGroupingJBEditorTabs) {
    val image = BufferedImage(tabs.width, tabs.height, BufferedImage.TYPE_INT_ARGB)
    val g = image.createGraphics()
    try {
      tabs.paint(g)
    }
    finally {
      g.dispose()
    }
  }

  private suspend fun makeTabs(tabCount: Int, position: JBTabsPosition = JBTabsPosition.top, singleRow: Boolean = true): TestGroupingJBEditorTabs {
    val scope = CoroutineScope(currentCoroutineContext())
    val tabs = TestGroupingJBEditorTabs(project, tabsDisposable, scope, TabListOptions(tabPosition = position, singleRow = singleRow), isIslandsTheme = true)
    tabs.size = Dimension(800, 400)
    // A detached component has no font, and the group label derives its font from this one.
    tabs.font = JLabel().font
    tabs.setTabsFromWindow((0 until tabCount).map { i ->
      val dir = if (i % 2 == 0) "Alpha" else "Beta"
      tab("tab$i", "/project/$dir/File$i.kt")
    })
    return tabs
  }

  private fun tab(name: String, path: String): TabInfo =
    TabInfo(JLabel(name)).setObject(makeFile(path)).apply { setText(name) }

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
    val parent = makeDir(path.substringBeforeLast('/'))
    return object : LightVirtualFile(path.substringAfterLast('/')) {
      override fun getPath(): String = path
      override fun isDirectory(): Boolean = false
      override fun getParent(): VirtualFile = parent
    }
  }
}

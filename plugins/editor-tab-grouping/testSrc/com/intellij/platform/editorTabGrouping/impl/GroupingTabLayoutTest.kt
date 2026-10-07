// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.mock.MockProjectEx
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.impl.EditorTabLabel
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.TabListOptions
import java.awt.Dimension
import javax.swing.JLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Tests layout invariants for editor tab grouping: [GroupingJBEditorTabs.getGroupHeaderHeight],
 * and [EditorTabLabel] insets/height behaviour when grouping is active.
 */
@TestApplication
@Timeout(30)
class GroupingTabLayoutTest {

  @TestDisposable
  private lateinit var disposable: Disposable
  private lateinit var tabsDisposable: Disposable
  private var savedGroupByDirectory: Boolean = false

  @BeforeEach
  fun setUp(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      tabsDisposable = Disposer.newDisposable()
      Disposer.register(disposable, tabsDisposable)
      savedGroupByDirectory = EditorTabGroupingSettings.getInstance().groupByDirectory
      EditorTabGroupingSettings.getInstance().groupByDirectory = false
    }
  }

  @AfterEach
  fun tearDown(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      EditorTabGroupingSettings.getInstance().groupByDirectory = savedGroupByDirectory
      Disposer.dispose(tabsDisposable)
    }
  }

  private suspend fun makeTabs(): GroupingJBEditorTabs {
    val scope = CoroutineScope(currentCoroutineContext())
    val project = MockProjectEx(tabsDisposable)
    val tabs = GroupingJBEditorTabs(project, tabsDisposable, scope, TabListOptions(), createTestEditorWindow(project, tabsDisposable))
    tabs.size = Dimension(800, 200)
    return tabs
  }

  private fun makeLabel(tabs: GroupingJBEditorTabs): EditorTabLabel =
    EditorTabLabel(info = TabInfo(JLabel("test")), tabs = tabs)

  @Test
  fun `group names are hidden by default`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      assertFalse(EditorTabGroupingSettings.State().showGroupNames)
    }
  }

  @Test
  fun `directory grouping is enabled by default`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      assertTrue(EditorTabGroupingSettings.State().groupByDirectory)
    }
  }

  @Test
  fun `getGroupHeaderHeight is 0 when directory grouping is disabled`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      assertEquals(0, makeTabs().getGroupHeaderHeight())
    }
  }

  @Test
  fun `getGroupHeaderHeight is 0 when grouping is enabled without visible groups`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      EditorTabGroupingSettings.getInstance().groupByDirectory = true
      assertEquals(0, makeTabs().getGroupHeaderHeight())
    }
  }

  @Test
  fun `effective grouping activation follows visible group count and source exclusion`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      data class Case(
        val name: String,
        val configured: Boolean,
        val groupSizes: List<Int>,
        val excludedGroupSize: Int? = null,
        val expected: Boolean,
      )

      val cases = listOf(
        Case("not configured", false, listOf(2, 2), expected = false),
        Case("no groups", true, emptyList(), expected = false),
        Case("singleton groups", true, listOf(1, 1), expected = false),
        Case("one multi-tab group", true, listOf(2), expected = false),
        Case("one group and another tab", true, listOf(2, 1), expected = true),
        Case("excluding the other tab hides the group", true, listOf(2, 1), excludedGroupSize = 1, expected = false),
        Case("excluding a member hides the only group", true, listOf(2, 1), excludedGroupSize = 2, expected = false),
        Case("two multi-tab groups", true, listOf(2, 2), expected = true),
        Case("pinned partitions count separately", true, listOf(2, 2), expected = true),
        Case("excluding singleton changes no visible group", true, listOf(2, 2, 1), excludedGroupSize = 1, expected = true),
        Case("excluding from two-tab group keeps the other group", true, listOf(2, 2), excludedGroupSize = 2, expected = true),
        Case("excluding from three-tab group keeps it visible", true, listOf(3, 2), excludedGroupSize = 3, expected = true),
        Case("three groups remain active after one is hidden", true, listOf(2, 2, 2), excludedGroupSize = 2, expected = true),
      )

      for ((name, configured, groupSizes, excludedGroupSize, expected) in cases) {
        assertEquals(
          expected,
          isTabGroupingActive(configured, groupSizes.count { it > 1 }, groupSizes.sum(), groupSizes.maxOrNull() ?: 0, excludedGroupSize),
          name,
        )
      }
    }
  }

  // --- EditorTabLabel layout without visible groups ---

  @Test
  fun `EditorTabLabel does not reserve height when no group header is visible`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tabs = makeTabs()
      val label = makeLabel(tabs)
      val baseHeight = label.preferredSize.height
      val baseInsets = label.insets
      EditorTabGroupingSettings.getInstance().groupByDirectory = true

      assertEquals(0, tabs.getGroupHeaderHeight())
      assertEquals(baseHeight, label.preferredSize.height)
      assertEquals(baseInsets, label.insets)
    }
  }
}

/** Unit tests for [clusterTabsByGroup] (Fix 2: group and within-group sorting). */
@TestApplication
@Timeout(30)
class ClusterTabsByGroupTest {

  private val GA = TabGroup("Alpha", "key-a")
  private val GB = TabGroup("Beta", "key-b")
  private val GZ = TabGroup("Zeta", "key-z")

  private fun tab(name: String): TabInfo = TabInfo(JLabel(name)).apply { setText(name) }

  @Test
  fun `named groups sorted by display name`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val z1 = tab("z1")
      val z2 = tab("z2")
      val a1 = tab("a1")
      val a2 = tab("a2")
      val b1 = tab("b1")
      val b2 = tab("b2")
      // tabs supplied in Z, A, B order; groups should come out A, B, Z
      val result = clusterTabsByGroup(listOf(z1, z2, a1, a2, b1, b2), { info ->
        when (info) {
          z1, z2 -> GZ; a1, a2 -> GA; b1, b2 -> GB; else -> null
        }
      }, sortAlphabetically = false)
      assertEquals(listOf(a1, a2, b1, b2, z1, z2), result,
                   "Groups must be sorted by name: Alpha, Beta, Zeta")
    }
  }

  @Test
  fun `ungrouped tabs always appear last`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val grouped = tab("g")
      val ungrouped = tab("u")
      val grouped2 = tab("g2")
      val result = clusterTabsByGroup(listOf(ungrouped, grouped, grouped2), { info ->
        if (info == grouped || info == grouped2) GA else null
      }, sortAlphabetically = false)
      assertEquals(listOf(grouped, grouped2, ungrouped), result,
                   "Ungrouped tab must appear after all group clusters")
    }
  }

  @Test
  fun `single-tab groups are treated as ungrouped and placed last`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val grouped1 = tab("grouped1")
      val grouped2 = tab("grouped2")
      val singleton = tab("singleton")
      val ungrouped = tab("ungrouped")
      val result = clusterTabsByGroup(listOf(singleton, ungrouped, grouped1, grouped2), { info ->
        when (info) {
          grouped1, grouped2 -> GA; singleton -> GB; else -> null
        }
      }, sortAlphabetically = false)
      assertEquals(listOf(grouped1, grouped2, singleton, ungrouped), result,
                   "Only shown groups should be clustered before ungrouped/singleton tabs")
    }
  }

  @Test
  fun `alpha sort on - tabs sorted within each group`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tC = tab("C")
      val tA = tab("A")
      val tB = tab("B")
      val result = clusterTabsByGroup(listOf(tC, tA, tB), { GA }, sortAlphabetically = true)
      assertEquals(listOf(tA, tB, tC), result)
    }
  }

  @Test
  fun `alpha sort off - preserves original order within group`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tC = tab("C")
      val tA = tab("A")
      val tB = tab("B")
      val result = clusterTabsByGroup(listOf(tC, tA, tB), { GA }, sortAlphabetically = false)
      assertEquals(listOf(tC, tA, tB), result)
    }
  }

  @Test
  fun `alpha sort on - ungrouped tabs sorted too`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tB = tab("B")
      val tA = tab("A")
      val result = clusterTabsByGroup(listOf(tB, tA), { null }, sortAlphabetically = true)
      assertEquals(listOf(tA, tB), result)
    }
  }

  @Test
  fun `groups with same name - secondary sort by key`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val g1 = TabGroup("Same", "a-key")
      val g2 = TabGroup("Same", "b-key")
      val a1 = tab("a1")
      val a2 = tab("a2")
      val b1 = tab("b1")
      val b2 = tab("b2")
      val result = clusterTabsByGroup(listOf(b1, b2, a1, a2), { info ->
        if (info == a1 || info == a2) g1 else g2
      }, sortAlphabetically = false)
      assertEquals(listOf(a1, a2, b1, b2), result, "Tiebreak by key: a-key before b-key")
    }
  }

  @Test
  fun `all ungrouped - ungrouped tabs in order`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val t1 = tab("t1")
      val t2 = tab("t2")
      val result = clusterTabsByGroup(listOf(t1, t2), { null }, sortAlphabetically = false)
      assertEquals(listOf(t1, t2), result)
    }
  }

  @Test
  fun `first tab of a new group stays with ungrouped tabs`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      // Simulates: bar has [B1, B2, C1, C2] and a new A1 tab (first of its group) is opened.
      // Singleton group A is not shown yet, so it stays with ungrouped tabs after shown groups.
      val b1 = tab("b1")
      val b2 = tab("b2")
      val c1 = tab("c1")
      val c2 = tab("c2")
      val a1 = tab("a1")
      val gAlpha = TabGroup("Alpha", "key-a")
      val gBeta = TabGroup("Beta", "key-b")
      val gGamma = TabGroup("Gamma", "key-c")
      val result = clusterTabsByGroup(listOf(b1, b2, c1, c2, a1), { info ->
        when (info) {
          b1 -> gBeta; b2 -> gBeta; c1 -> gGamma; c2 -> gGamma; a1 -> gAlpha; else -> null
        }
      }, sortAlphabetically = false)
      assertEquals(listOf(b1, b2, c1, c2, a1), result,
                   "Singleton group A is visually ungrouped until another A tab appears")
    }
  }

  @Test
  fun `second tab of a new group makes the group appear alphabetically among shown groups`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val b1 = tab("b1")
      val b2 = tab("b2")
      val c1 = tab("c1")
      val c2 = tab("c2")
      val a1 = tab("a1")
      val a2 = tab("a2")
      val gAlpha = TabGroup("Alpha", "key-a")
      val gBeta = TabGroup("Beta", "key-b")
      val gGamma = TabGroup("Gamma", "key-c")
      val result = clusterTabsByGroup(listOf(b1, b2, c1, c2, a1, a2), { info ->
        when (info) {
          b1, b2 -> gBeta; c1, c2 -> gGamma; a1, a2 -> gAlpha; else -> null
        }
      }, sortAlphabetically = false)
      assertEquals(listOf(a1, a2, b1, b2, c1, c2), result,
                   "Once group A has two tabs, it should be shown and sorted with other shown groups")
    }
  }

  @Test
  fun `empty input returns empty`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      assertTrue(clusterTabsByGroup(emptyList(), { null }, sortAlphabetically = false).isEmpty())
    }
  }

  @Test
  fun `Agents group sorts before directory groups including alphabetically earlier names`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val api1 = tab("api1")
      val api2 = tab("api2")
      val aaa1 = tab("aaa1")
      val aaa2 = tab("aaa2")
      val agents1 = tab("agents1")
      val agents2 = tab("agents2")
      val gApi = TabGroup("Api", "key-api")
      val gAaa = TabGroup("Aaa", "key-aaa")
      val gAgents = agentsTabGroup()
      // Tabs supplied with directories before Agents; Agents must still come first
      val result = clusterTabsByGroup(
        listOf(api1, api2, aaa1, aaa2, agents1, agents2),
        { info ->
          when (info) {
            api1, api2 -> gApi
            aaa1, aaa2 -> gAaa
            agents1, agents2 -> gAgents
            else -> null
          }
        },
        sortAlphabetically = false,
      )
      assertEquals(listOf(agents1, agents2, aaa1, aaa2, api1, api2), result,
                   "Agents group must sort before directory groups regardless of name")
    }
  }
}

/** Unit tests for grouped-vs-ungrouped DnD constraint selection. */
class ComputeConstrainedDropPositionsTest {

  private fun tabs(vararg keys: String?): List<Pair<Boolean, String?>> =
    keys.map { false to it }

  @Test
  fun `same-window preview excludes foreign-group boundary when dragging forward`() {
    val t = tabs("a", "a", "a", "b", "b")

    assertEquals(0..2, computeConstrainedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a", excludeIdx = 0))
  }

  @Test
  fun `same-window preview excludes foreign-group boundary when dragging backward`() {
    val t = tabs("b", "b", "a", "a", "a")

    assertEquals(2..4, computeConstrainedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a", excludeIdx = 4))
  }

  @Test
  fun `grouped tab stays inside its group even when dragged tab is absent from visible tabs`() {
    val t = tabs("a", "a", "a", "b", "b")

    assertEquals(0..3, computeConstrainedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a"))
  }

  @Test
  fun `grouped drag exposes every remaining in-group insertion boundary`() {
    val t = tabs("a", "a", "a", null, null)

    assertEquals(0..2, computeGroupedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a", excludeIdx = 0))
    assertEquals(0..2, computeConstrainedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a", excludeIdx = 0))
  }

  @Test
  fun `grouped drag rejects insertion boundary beyond remaining visible group`() {
    val t = tabs("a", "a", "a", "d", "e")

    val positions = computeGroupedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a", excludeIdx = 0)

    assertEquals(0..2, positions)
    assertFalse(3 in positions)
  }

  @Test
  fun `dragged grouped tab absent from visible tabs can be inserted between remaining siblings`() {
    val t = tabs("a", "a", "b", "b")

    assertEquals(0..2, computeGroupedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a"))
    assertEquals(0..2, computeConstrainedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a"))
  }

  @Test
  fun `singleton group does not use grouped drop positions`() {
    val t = tabs("a", null, "b", "b", null)

    assertTrue(computeGroupedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a").isEmpty())
  }

  @Test
  fun `ungrouped drop with missing pinned partition inserts before unpinned tabs`() {
    val t = listOf(false to "a", false to "a")

    assertEquals(0..0, computeUngroupedDropPositions(t, draggedIsPinned = true, excludeIdx = -1))
  }

  @Test
  fun `ungrouped drop with missing unpinned partition inserts after pinned tabs`() {
    val t = listOf(true to "a", true to "a")

    assertEquals(2..2, computeUngroupedDropPositions(t, draggedIsPinned = false, excludeIdx = -1))
  }

  @Test
  fun `ungrouped tab is constrained to ungrouped slots only`() {
    val t = tabs("a", "a", null, "b", "b", null)

    assertEquals(2..6, computeConstrainedDropPositions(t, draggedIsPinned = false, draggedGroupKey = null))
  }

  @Test
  fun `singleton potential group is treated as ungrouped during drag`() {
    val t = tabs("a", "a", null, "b", "b", null)

    assertEquals(2..6, computeConstrainedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "c"))
  }

  @Test
  fun `same group key in another pinned partition is not an allowed destination`() {
    val t = listOf(true to "a", true to "a", false to "a", false to "a", false to "b", false to "b")

    assertEquals(0..2, computeGroupedDropPositions(t, draggedIsPinned = true, draggedGroupKey = "a"))
    assertEquals(2..4, computeGroupedDropPositions(t, draggedIsPinned = false, draggedGroupKey = "a"))
  }
}

/** Unit tests for duplicate-name disambiguation using only visible groups. */
class DisambiguateGroupNameTest {

  @Test
  fun `same folder name stays collapsed when conflicting group is not visible`() {
    val visible = listOf(TabGroup("src", "/root-one/src"))

    assertEquals("src", disambiguateVisibleGroupName(TabGroup("src", "/root-one/src"), visible))
  }

  @Test
  fun `same folder name expands only when conflicting visible group exists`() {
    val visible = listOf(
      TabGroup("src", "/root-one/src"),
      TabGroup("src", "/root-two/src"),
    )

    assertEquals("root-one/src", disambiguateVisibleGroupName(TabGroup("src", "/root-one/src"), visible))
    assertEquals("root-two/src", disambiguateVisibleGroupName(TabGroup("src", "/root-two/src"), visible))
  }

  @Test
  fun `Agents synthetic group keeps display name when a directory is also named Agents`() {
    val agents = agentsTabGroup()
    val directoryAgents = TabGroup("Agents", "/project/root/Agents")
    val visible = listOf(agents, directoryAgents)

    assertEquals("Agents", disambiguateVisibleGroupName(agents, visible))
    assertEquals("root/Agents", disambiguateVisibleGroupName(directoryAgents, visible))
  }
}

/** Unit tests for ungrouped-tab end-placement rules. */
class ComputeUngroupedInsertIndexTest {

  private fun tabs(vararg keys: String?): List<Pair<Boolean, String?>> = keys.map { false to it }

  @Test
  fun `ungrouped tab at start of bar with grouped tabs moves to after last grouped`() {
    // Bar: [ungrouped(0), groupA(1), groupA(2)] - ungrouped must move to index 2
    val t = tabs(null, "a", "a")
    assertEquals(2, computeUngroupedInsertIndex(t, false, currentIdx = 0))
  }

  @Test
  fun `ungrouped tab after grouped tabs keeps its position`() {
    // Bar: [groupA(0), groupA(1), ungrouped(2)] - already in correct position
    val t = tabs("a", "a", null)
    assertNull(computeUngroupedInsertIndex(t, false, currentIdx = 2))
  }

  @Test
  fun `ungrouped tab between two groups moves to after last group`() {
    // Bar: [groupA(0), groupA(1), ungrouped(2), groupB(3), groupB(4)] - must move after groupB.
    val t = tabs("a", "a", null, "b", "b")
    assertEquals(4, computeUngroupedInsertIndex(t, false, currentIdx = 2))
  }

  @Test
  fun `all ungrouped - returns null meaning no reorder needed`() {
    assertNull(computeUngroupedInsertIndex(tabs(null, null, null), false, currentIdx = 1))
  }

  @Test
  fun `ungrouped tab appended after existing ungrouped keeps its position`() {
    // Bar: [groupA(0), groupA(1), ungrouped(2), ungrouped-new(3)]
    val t = tabs("a", "a", null, null)
    assertNull(computeUngroupedInsertIndex(t, false, currentIdx = 3))
  }

  @Test
  fun `pinned ungrouped tab moves after last grouped pinned tab`() {
    // Bar: [pinnedUngrouped(0), pinnedGroupA(1), pinnedGroupA(2), unpinnedGroupB(3), unpinnedGroupB(4)]
    // pinned ungrouped at 0; last shown grouped PINNED tab is 2; 0 <= 2 -> insertIdx = 2
    val t = listOf(true to null, true to "a", true to "a", false to "b", false to "b")
    assertEquals(2, computeUngroupedInsertIndex(t, isPinned = true, currentIdx = 0))
  }
}

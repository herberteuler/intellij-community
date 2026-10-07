// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.ui.tabs.TabInfo

internal fun isTabGroupingActive(
  groupingConfigured: Boolean,
  visibleGroupCount: Int,
  tabCount: Int,
  largestGroupSize: Int,
  excludedGroupSize: Int? = null,
): Boolean {
  if (!groupingConfigured) return false
  val remainingVisibleGroupCount = visibleGroupCount - if (excludedGroupSize == 2) 1 else 0
  if (remainingVisibleGroupCount > 1) return true
  if (remainingVisibleGroupCount == 0) return false
  val remainingTabCount = tabCount - if (excludedGroupSize != null) 1 else 0
  val remainingGroupSize = largestGroupSize - if (excludedGroupSize == largestGroupSize) 1 else 0
  return remainingTabCount > remainingGroupSize
}

/**
 * Clusters [tabs] by group: the Agents group first, then other shown groups by display name
 * (then key as tiebreaker), ungrouped tabs appended last. A group is shown only when at least
 * two tabs have its key. Within each cluster, tabs are sorted by tab text when
 * [sortAlphabetically] is true.
 */
internal fun clusterTabsByGroup(
  tabs: List<TabInfo>,
  groupResolver: (TabInfo) -> TabGroup?,
  sortAlphabetically: Boolean,
): List<TabInfo> {
  val resolvedGroups = tabs.associateWith(groupResolver)
  val visibleGroupKeys = resolvedGroups.values.asSequence()
    .filterNotNull()
    .groupingBy { it.key }
    .eachCount()
    .filterValues { it > 1 }
    .keys
  val ungrouped = mutableListOf<TabInfo>()
  val byGroup = LinkedHashMap<TabGroup, MutableList<TabInfo>>()
  for (info in tabs) {
    val grp = resolvedGroups[info]
    if (grp == null || grp.key !in visibleGroupKeys) ungrouped.add(info)
    else byGroup.getOrPut(grp) { mutableListOf() }.add(info)
  }
  val sortedGroups = byGroup.entries.sortedWith(
    compareBy({ if (it.key.key == AGENTS_GROUP_KEY) 0 else 1 }, { it.key.name }, { it.key.key })
  )
  return sortedGroups.flatMap { (_, list) ->
    if (sortAlphabetically) list.sortedBy { it.text } else list
  } + if (sortAlphabetically) ungrouped.sortedBy { it.text } else ungrouped
}

/**
 * Returns the tab indices where the dragged tab may land.
 *
 * The caller caches the result for the current tab order and drag source.
 */
internal fun computeConstrainedDropPositions(
  tabs: List<Pair<Boolean, String?>>,
  draggedIsPinned: Boolean,
  draggedGroupKey: String?,
  excludeIdx: Int = -1,
): IntRange {
  if (draggedGroupKey != null) {
    val groupedPositions = computeGroupedDropPositions(tabs, draggedIsPinned, draggedGroupKey, excludeIdx)
    if (!groupedPositions.isEmpty()) {
      return groupedPositions
    }
  }
  return computeUngroupedDropPositions(tabs, draggedIsPinned, excludeIdx)
}

/**
 * Returns the longest run of tabs in [tabs] that have [isPinned] and [groupKey].
 *
 * The tab at [excludeIdx] never matches. When [reindexed] is true, the result uses the indices of
 * the list without the tab at [excludeIdx], and a run continues across that position. When it is
 * false, the result uses the indices of [tabs].
 */
internal fun computeContiguousGroupRange(
  tabs: List<Pair<Boolean, String?>>,
  isPinned: Boolean,
  groupKey: String,
  excludeIdx: Int = -1,
  reindexed: Boolean = false,
): IntRange {
  var bestStart = -1
  var bestEnd = -1
  var currentStart = -1
  var outIdx = 0

  fun finishRun() {
    if (currentStart < 0) return
    val currentEnd = outIdx - 1
    if (bestStart < 0 || currentEnd - currentStart > bestEnd - bestStart) {
      bestStart = currentStart
      bestEnd = currentEnd
    }
    currentStart = -1
  }

  for (i in tabs.indices) {
    if (i == excludeIdx) {
      if (reindexed) continue
      finishRun()
      outIdx++
      continue
    }
    val (pinned, key) = tabs[i]
    if (pinned == isPinned && key == groupKey) {
      if (currentStart < 0) currentStart = outIdx
    }
    else {
      finishRun()
    }
    outIdx++
  }
  finishRun()

  return if (bestStart < 0) IntRange.EMPTY else bestStart..bestEnd
}

internal fun computeGroupedDropPositions(
  tabs: List<Pair<Boolean, String?>>,
  draggedIsPinned: Boolean,
  draggedGroupKey: String,
  excludeIdx: Int = -1,
): IntRange {
  val logicalGroupSize = tabs.count { (isPinned, groupKey) ->
    isPinned == draggedIsPinned && groupKey == draggedGroupKey
  }
  if (logicalGroupSize <= 1) return IntRange.EMPTY
  val range = computeContiguousGroupRange(tabs, draggedIsPinned, draggedGroupKey, excludeIdx, reindexed = true)
  if (range.isEmpty()) return IntRange.EMPTY
  return range.first..range.last + 1
}

internal fun computeUngroupedDropPositions(
  tabs: List<Pair<Boolean, String?>>,
  draggedIsPinned: Boolean,
  excludeIdx: Int,
): IntRange {
  val visibleGroups = visibleGroupKeys(tabs, excludeIdx)
  var remainingSize = 0
  var partitionCount = 0
  var lastInPartition = -1
  var firstUngrouped = -1
  var lastUngrouped = -1
  for (i in tabs.indices) {
    if (i == excludeIdx) continue
    val outIdx = remainingSize++
    val (isPinned, key) = tabs[i]
    if (isPinned != draggedIsPinned) continue
    partitionCount++
    lastInPartition = outIdx
    if (key == null || (isPinned to key) !in visibleGroups) {
      if (firstUngrouped < 0) firstUngrouped = outIdx
      lastUngrouped = outIdx
    }
  }

  if (partitionCount == 0) {
    val insertPos = if (draggedIsPinned) 0 else remainingSize
    return insertPos..insertPos
  }
  if (firstUngrouped >= 0) return firstUngrouped..lastUngrouped + 1
  val insertPos = lastInPartition + 1
  return insertPos..insertPos
}

/** Returns the (isPinned, groupKey) pairs that at least two tabs share, so the group is shown. */
private fun visibleGroupKeys(tabs: List<Pair<Boolean, String?>>, excludeIdx: Int = -1): Set<Pair<Boolean, String>> {
  val counts = HashMap<Pair<Boolean, String>, Int>()
  for (i in tabs.indices) {
    if (i == excludeIdx) continue
    val (isPinned, key) = tabs[i]
    if (key == null) continue
    val pair = isPinned to key
    counts[pair] = (counts[pair] ?: 0) + 1
  }
  return counts.entries.mapNotNullTo(HashSet()) { if (it.value > 1) it.key else null }
}

internal fun disambiguateVisibleGroupName(group: TabGroup, visibleGroups: List<TabGroup>): String {
  val conflicts = visibleGroups.filter { it.name == group.name && it.key != group.key }
  if (conflicts.isEmpty()) return group.name
  // Synthetic keys such as air:agents are not filesystem paths. Keep the display name and let
  // path-based peers expand instead of showing the raw key.
  if ('/' !in group.key) return group.name
  val hasSyntheticConflict = conflicts.any { '/' !in it.key }
  val parts = group.key.split('/')
  for (startIdx in parts.indices.reversed()) {
    val candidate = parts.drop(startIdx).joinToString("/")
    // A bare folder name still collides with a synthetic peer that kept the same display name.
    if (candidate == group.name && hasSyntheticConflict) continue
    if (conflicts.none { it.key.endsWith("/$candidate") || it.key == candidate }) return candidate
  }
  return group.key
}

/** Returns a valid insertion position, or null when the current position is valid. */
internal fun computeUngroupedInsertIndex(
  tabs: List<Pair<Boolean, String?>>,
  isPinned: Boolean,
  currentIdx: Int,
): Int? {
  val positions = computeUngroupedDropPositions(tabs, isPinned, currentIdx)
  return if (currentIdx in positions) null else currentIdx.coerceIn(positions.first, positions.last)
}

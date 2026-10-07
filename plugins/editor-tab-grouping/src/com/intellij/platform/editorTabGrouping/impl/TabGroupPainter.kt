// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.editorTabGrouping.impl

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.impl.InternalUICustomization
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.tabs.TabInfo
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.UIManager

private const val OUTLINE_H_INSET = 2
private const val OUTLINE_TEXT_PAD = 4
private const val OUTLINE_V_PAD = 5
private const val OUTLINE_BORDER_WIDTH = 1f
private const val RAIL_LINE_THICKNESS = 2
private const val RAIL_NAME_GAP = 1

// The rail sits this far inside the edge of the lane, so the container border does not clip it.
// Horizontal tabs pad from the top edge. Vertical tabs pad from the side edge.
private const val RAIL_EDGE_PAD = 1
private const val GROUP_CIRCLE_DIAMETER = 6
private const val BADGE_HORIZONTAL_PADDING = 2
private const val BADGE_VERTICAL_PADDING = 0
private const val BADGE_MIN_WIDTH = 12
private const val BADGE_ARC = 3
private const val GROUP_LABEL_EXTRA_PX = 1

internal class TabGroupPainter(private val tabs: GroupingJBEditorTabs) {
  private val width get() = tabs.width
  private val height get() = tabs.height
  private val font get() = tabs.font ?: UIManager.getFont("Label.font")
  private val isHorizontalTabs get() = tabs.isHorizontalTabs
  private val groupHeaderFontSize get() = JBUI.Fonts.miniFont().size.toFloat()
  private fun getFontMetrics(font: Font) = tabs.getFontMetrics(font)
  private fun getVisibleInfos() = tabs.visibleGroupTabs()
  private fun getTabLabel(info: TabInfo) = tabs.getTabLabel(info)
  private fun groupFor(info: TabInfo) = tabs.groupFor(info)
  private fun isGroupingActive() = tabs.isGroupingActive()
  private fun getGroupHeaderHeight() = tabs.getGroupHeaderHeight()
  private fun effectiveShowGroupNames() = tabs.effectiveShowGroupNames()
  private fun effectiveGroupLabelStyle() = tabs.effectiveGroupLabelStyle()
  private fun outlineTopPadding() = ((OUTLINE_V_PAD - 1).scale).coerceAtLeast(0)
  private var namedGroups: List<TabGroup> = emptyList()
  private var names: Map<String, String> = emptyMap()

  fun invalidate() {
    cachedOutlineData = null
    outlineDataValid = false
  }

  private fun displayNames(groups: List<TabGroup>): Map<String, String> {
    if (!effectiveShowGroupNames()) return emptyMap()
    if (namedGroups != groups) {
      namedGroups = groups
      val byName = groups.groupBy { it.name }
      names = groups.associate { it.key to disambiguateVisibleGroupName(it, byName.getValue(it.name)) }
      tabs.groupNamesBuilt()
    }
    return names
  }

  private data class OutlineKey(val groupKey: String, val pinned: Boolean, val laneCoord: Int, val segment: Int)

  // primaryMin/Max: span along scrolling axis (X for horizontal, Y for vertical).
  // crossPos/crossSize: position on perpendicular axis (Y/height for horizontal, X/width for vertical).
  private data class OutlineBounds(
    val group: TabGroup, val color: Color,
    var primaryMin: Int, var primaryMax: Int,
    val crossPos: Int, val crossSize: Int,
  )

  private data class OutlineData(
    val outlines: LinkedHashMap<OutlineKey, OutlineBounds>,
    val totalGroupCounts: Map<Pair<String, Boolean>, Int>,
    val topLane: Map<Pair<String, Boolean>, Int>,
    /** The label text per group key, disambiguated against the other visible groups. */
    val displayNames: Map<String, String>,
  )

  private fun buildOutlineData(): OutlineData? {
    if (!isGroupingActive()) return null

    val outlines = LinkedHashMap<OutlineKey, OutlineBounds>()
    // A group count must include a member outside the viewport, so it is taken before the clip test.
    val totalGroupCounts = tabs.logicalGroupSizes().mapKeys { (key, _) -> key.second to key.first }
    var segment = 0
    var previousGroup: Pair<String, Boolean>? = null
    for (info in getVisibleInfos()) {
      val label = getTabLabel(info) ?: continue
      val grp = groupFor(info)
      val groupKey = grp?.let { it.key to info.isPinned }
      if (groupKey != previousGroup) segment++
      previousGroup = groupKey
      if (grp == null) continue
      val b = label.bounds

      val primaryMin: Int
      val primaryMax: Int
      val crossPos: Int
      val crossSize: Int
      val laneCoord: Int
      if (isHorizontalTabs) {
        if (b.width == 0 || b.x + b.width <= 0 || b.x >= width) continue
        primaryMin = b.x
        primaryMax = b.x + b.width
        crossPos = b.y
        crossSize = b.height
        laneCoord = b.y
      }
      else {
        if (b.height == 0 || b.y + b.height <= 0 || b.y >= height) continue
        primaryMin = b.y
        primaryMax = b.y + b.height
        crossPos = b.x
        crossSize = b.width
        laneCoord = b.x
      }

      val key = OutlineKey(grp.key, info.isPinned, laneCoord, segment)
      val existing = outlines[key]
      if (existing == null) {
        outlines[key] = OutlineBounds(
          group = grp,
          color = EditorTabGroupingProvider.colorForGroup(grp),
          primaryMin = primaryMin, primaryMax = primaryMax,
          crossPos = crossPos, crossSize = crossSize,
        )
      }
      else {
        if (primaryMin < existing.primaryMin) existing.primaryMin = primaryMin
        if (primaryMax > existing.primaryMax) existing.primaryMax = primaryMax
      }
    }

    val topLane = mutableMapOf<Pair<String, Boolean>, Int>()
    for ((groupKey, pinned, laneCoord) in outlines.keys) {
      val gp = groupKey to pinned
      val cur = topLane[gp]
      if (cur == null || laneCoord < cur) topLane[gp] = laneCoord
    }

    val visibleGroupKeys = totalGroupCounts.filterValues { it > 1 }.keys

    val displayNames = if (effectiveShowGroupNames()) {
      val allGroups = outlines.entries.asSequence()
        .filter { (it.key.groupKey to it.key.pinned) in visibleGroupKeys }
        .map { it.value.group }
        .distinctBy { it.key }
        .toList()
      displayNames(allGroups)
    }
    else emptyMap()
    return OutlineData(outlines, totalGroupCounts, topLane, displayNames)
  }

  // Outline backgrounds paint in paintComponent (before children); labels paint in paint (after
  // children) so they stay visible on top of selected tabs' opaque chrome.
  // The data depends on the tab label bounds, so one layout pass reuses one value.

  private var cachedOutlineData: OutlineData? = null
  private var outlineDataValid = false

  private fun outlineData(): OutlineData? {
    if (!outlineDataValid) {
      cachedOutlineData = buildOutlineData()
      outlineDataValid = true
      tabs.outlineDataBuilt()
    }
    return cachedOutlineData
  }

  fun paintBackground(g: Graphics) {
    if (effectiveGroupLabelStyle() == GroupLabelStyle.RAIL) paintGroupDecoration(g as Graphics2D, outlineData())
  }

  fun paintForeground(g: Graphics) {
    val data = outlineData()
    if (effectiveGroupLabelStyle() == GroupLabelStyle.OUTLINE) paintGroupDecoration(g as Graphics2D, data)
    paintOutlineLabels(g as Graphics2D, data)
  }

  private fun paintGroupDecoration(source: Graphics2D, data: OutlineData?) {
    data ?: return
    val g = source.create() as Graphics2D
    try {
      val tabArc = JBUI.CurrentTheme.MainToolbar.Button.hoverArc().get()
      val arc = tabArc + OUTLINE_H_INSET.scale
      val vPad = OUTLINE_V_PAD.scale
      val style = effectiveGroupLabelStyle()
      val headerHeight = if (style == GroupLabelStyle.OUTLINE) getGroupHeaderHeight() else 0
      val railThickness = RAIL_LINE_THICKNESS.scale
      val tabHOffset = InternalUICustomization.getInstance()
        ?.getTabHOffsetUnscaled(UISettings.getInstance().compactMode, tabs.tabsPosition)?.scale ?: 0

      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

      for ((key, bounds) in data.outlines) {
        val gp = key.groupKey to key.pinned
        if ((data.totalGroupCounts[gp] ?: 0) <= 1) continue

        g.color = bounds.color

        if (isHorizontalTabs) {
          val rx = (bounds.primaryMin + OUTLINE_H_INSET).coerceAtLeast(0)
          val rRight = (bounds.primaryMax - OUTLINE_H_INSET).coerceAtMost(width)
          val rw = (rRight - rx).coerceAtLeast(0)
          val ry = bounds.crossPos + headerHeight + outlineTopPadding()
          val rh = (bounds.crossPos + bounds.crossSize - ry - vPad).coerceAtLeast(0)
          if (style == GroupLabelStyle.OUTLINE) {
            g.stroke = BasicStroke(OUTLINE_BORDER_WIDTH.scale)
            g.drawRoundRect(rx, ry, rw, rh, arc, arc)
          }
          else {
            // The rail starts and ends where the island tab background does.
            val railX = (bounds.primaryMin + tabHOffset).coerceAtLeast(0)
            val railRight = (bounds.primaryMax - tabHOffset).coerceAtMost(width)
            val railW = (railRight - railX).coerceAtLeast(0)
            val railY = bounds.crossPos + RAIL_EDGE_PAD.scale
            g.fillRoundRect(railX, railY, railW, railThickness, railThickness, railThickness)
          }
        }
        else {
          val ry = (bounds.primaryMin + headerHeight + OUTLINE_H_INSET).coerceAtLeast(0)
          val rBottom = (bounds.primaryMax - OUTLINE_H_INSET).coerceAtMost(height)
          val rh = (rBottom - ry).coerceAtLeast(0)
          val rx = bounds.crossPos + vPad
          val rw = (bounds.crossSize - 2 * vPad).coerceAtLeast(0)
          if (style == GroupLabelStyle.OUTLINE) {
            g.stroke = BasicStroke(OUTLINE_BORDER_WIDTH.scale)
            g.drawRoundRect(rx, ry, rw, rh, arc, arc)
          }
          else {
            val railX = bounds.crossPos + RAIL_EDGE_PAD.scale
            g.fillRoundRect(railX, ry, railThickness, rh, railThickness, railThickness)
          }
        }
      }

    }
    finally {
      g.dispose()
    }
  }

  private fun paintOutlineLabels(source: Graphics2D, data: OutlineData?) {
    data ?: return
    if (!effectiveShowGroupNames()) return
    val g = source.create() as Graphics2D
    try {
      val textPad = OUTLINE_TEXT_PAD.scale
      val vPad = OUTLINE_V_PAD.scale
      val labelFont = font.deriveFont(groupHeaderFontSize)
      val fm = getFontMetrics(labelFont)
      val circleDiameter = GROUP_CIRCLE_DIAMETER.scale
      val badgeHorizontalPadding = BADGE_HORIZONTAL_PADDING.scale
      val badgeVerticalPadding = BADGE_VERTICAL_PADDING.scale
      val badgeMinWidth = BADGE_MIN_WIDTH.scale
      val badgeArc = BADGE_ARC.scale
      val style = effectiveGroupLabelStyle()
      val headerHeight = getGroupHeaderHeight()
      val infoColor = UIUtil.getContextHelpForeground()

      @Suppress("UnregisteredNamedColor")
      val badgeBgColor = JBColor.namedColor("ColorPalette.Grey13", JBColor(0xD0D0D0, 0x4B4E54))

      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g.font = labelFont
      g.color = UIManager.getColor("Label.foreground")

      // Draws the circle, the optional path, the group name, and the count badge.
      // `originX` is the left edge of the label. `centerY` is the vertical center of the label.
      // A null `countText` drops the badge. Returns the width that the label drew.
      fun drawGroupLabel(originX: Int, centerY: Int, name: String, path: String, countText: String?, circleColor: Color): Int {
        val baselineY = centerY + fm.ascent / 2
        var cursorX = originX

        g.color = circleColor
        g.fillOval(cursorX, centerY - circleDiameter / 2, circleDiameter, circleDiameter)
        cursorX += circleDiameter + 2.scale

        if (path.isNotEmpty()) {
          g.color = infoColor
          g.drawString(path, cursorX, baselineY)
          cursorX += fm.stringWidth(path) + fm.stringWidth(" ")
        }

        if (name.isNotEmpty()) {
          g.color = UIManager.getColor("Label.foreground")
          g.drawString(name, cursorX, baselineY)
          cursorX += fm.stringWidth(name)
        }

        if (countText == null) return cursorX - originX

        cursorX += 4.scale
        val badgeWidth = maxOf(fm.stringWidth(countText) + 2 * badgeHorizontalPadding, badgeMinWidth)
        val badgeHeight = fm.height + 2 * badgeVerticalPadding
        g.color = badgeBgColor
        g.fillRoundRect(cursorX, centerY - badgeHeight / 2, badgeWidth, badgeHeight, badgeArc, badgeArc)
        g.color = UIManager.getColor("Label.foreground")
        g.drawString(countText, cursorX + (badgeWidth - fm.stringWidth(countText)) / 2, baselineY)
        return cursorX + badgeWidth - originX
      }

      // The clip of the copy holds the repaint region. Each group resets to it, then narrows it to
      // the group area, so no part of a label can paint over the next group.
      val baseClip = g.clip

      for ((key, bounds) in data.outlines) {
        val gp = key.groupKey to key.pinned
        val groupCount = data.totalGroupCounts[gp] ?: 0
        if (groupCount <= 1) continue
        if (data.topLane[gp] != key.laneCoord) continue

        val displayName = data.displayNames[bounds.group.key] ?: bounds.group.name
        val (namePart, pathPart) = splitDisplayName(displayName, bounds.group.name)

        val countText = groupCount.toString()
        val badgeWidth = maxOf(fm.stringWidth(countText) + 2 * badgeHorizontalPadding, badgeMinWidth)
        val circleWidth = circleDiameter + 2.scale
        val gapBetweenNameAndBadge = 4.scale

        // The label area is the part of the group that the label may use. The overflow is on the
        // X axis for both tab positions, because the vertical layout draws the label across the
        // top of the group column.
        val labelAreaX: Int
        val labelAreaWidth: Int
        val centerY: Int
        if (isHorizontalTabs) {
          val rx = (bounds.primaryMin + OUTLINE_H_INSET).coerceAtLeast(0)
          val rRight = (bounds.primaryMax - OUTLINE_H_INSET).coerceAtMost(width)
          labelAreaX = rx
          labelAreaWidth = (rRight - rx).coerceAtLeast(0)
          centerY = when (style) {
            GroupLabelStyle.OUTLINE -> bounds.crossPos + GROUP_LABEL_EXTRA_PX.scale + headerHeight / 2
            GroupLabelStyle.RAIL -> {
              bounds.crossPos + (RAIL_EDGE_PAD + RAIL_LINE_THICKNESS + RAIL_NAME_GAP).scale + fm.height / 2
            }
          }
        }
        else {
          val ry = (bounds.primaryMin + OUTLINE_H_INSET).coerceAtLeast(0)
          labelAreaX = bounds.crossPos + vPad
          labelAreaWidth = (bounds.crossSize - 2 * vPad).coerceAtLeast(0)
          centerY = ry + fm.ascent / 2 + 2.scale
        }
        val originX = labelAreaX + textPad
        val labelSpace = labelAreaWidth - 2 * textPad

        val plan = planGroupLabel(labelSpace, circleWidth, gapBetweenNameAndBadge, badgeWidth)
        if (!plan.drawCircle) continue
        val (truncatedName, truncatedPath) = truncateNameAndPath(namePart, pathPart, fm, plan.textSpace)

        // The assignment restores the clip that this loop narrowed for the group before. It is a
        // restore of the same value, so it never widens the region that the caller gave.
        @Suppress("SSBasedInspection")
        g.clip = baseClip
        g.clipRect(labelAreaX, 0, labelAreaWidth, height)
        val drawnWidth = drawGroupLabel(
          originX, centerY, truncatedName, truncatedPath, if (plan.drawBadge) countText else null, bounds.color
        )
        tabs.groupLabelPainted(labelAreaX, labelAreaWidth, drawnWidth + 2 * textPad)
      }


    }
    finally {
      g.dispose()
    }
  }

  // Split displayName into the group name part and an optional path prefix.
  // The disambiguated name may be "impl" (just the name) or "ui/tabs/impl" (path + name).
  // Returns Pair(name, path) where path is the parent directories including the trailing slash.
  private fun splitDisplayName(displayName: String, groupName: String): Pair<String, String> {
    if (displayName == groupName) return Pair(groupName, "")
    // The displayName from disambiguatedName is a path like "ui/tabs/impl" or "impl".
    // The group name is the last component. Find it at the end.
    val idx = displayName.lastIndexOf(groupName)
    if (idx < 0) return Pair(displayName, "")
    val before = displayName.substring(0, idx)
    return Pair(groupName, before)
  }

  // Truncate name and path to fit within available width.
  private fun truncateNameAndPath(
    name: String, path: String, fm: FontMetrics, availableWidth: Int,
  ): Pair<String, String> {
    if (availableWidth <= 0) return Pair("", "")
    val nameWidth = fm.stringWidth(name)
    val pathWidth = if (path.isNotEmpty()) fm.stringWidth(path) else 0
    val pathNameGap = if (path.isNotEmpty()) fm.stringWidth(" ") else 0
    val totalWidth = nameWidth + pathWidth + pathNameGap

    if (totalWidth <= availableWidth) return Pair(name, path)

    // Prioritize the group name; truncate the path first
    if (path.isNotEmpty()) {
      if (nameWidth > availableWidth) return Pair(truncateToFit(name, fm, availableWidth), "")
      val remainingForPath = (availableWidth - nameWidth - pathNameGap).coerceAtLeast(0)
      val truncatedPath = truncateToFit(path, fm, remainingForPath)
      return Pair(name, truncatedPath)
    }

    // Only name, truncate it
    return Pair(truncateToFit(name, fm, availableWidth), "")
  }

}

/** The parts of a group label that fit into the available space. */
internal data class GroupLabelPlan(val drawCircle: Boolean, val drawBadge: Boolean, val textSpace: Int)

/**
 * Returns the parts of a group label that fit into [labelSpace].
 *
 * A part that does not fit is dropped from the right, in the order the parts are placed: the
 * badge first, then the text, then the circle. [gap] is the space between the text and the badge.
 * The badge gives its space back to the text, so a name shows where the badge does not fit.
 */
internal fun planGroupLabel(labelSpace: Int, circleWidth: Int, gap: Int, badgeWidth: Int): GroupLabelPlan {
  if (labelSpace < circleWidth) return GroupLabelPlan(drawCircle = false, drawBadge = false, textSpace = 0)
  if (circleWidth + gap + badgeWidth <= labelSpace) {
    return GroupLabelPlan(drawCircle = true, drawBadge = true, textSpace = labelSpace - circleWidth - gap - badgeWidth)
  }
  return GroupLabelPlan(drawCircle = true, drawBadge = false, textSpace = labelSpace - circleWidth)
}

/** Returns [text], shortened with an ellipsis so that it fits into [maxWidth]. */
internal fun truncateToFit(text: String, fm: FontMetrics, maxWidth: Int): String {
  if (maxWidth <= 0) return ""
  if (fm.stringWidth(text) <= maxWidth) return text
  val ellipsis = "…"
  // A prefix never gets narrower as it gets longer, so a binary search finds the longest
  // prefix that fits. It needs O(log n) measurements instead of O(n).
  var low = 0
  var high = text.length
  while (low < high) {
    val mid = (low + high + 1) / 2
    if (fm.stringWidth(text.substring(0, mid) + ellipsis) <= maxWidth) low = mid else high = mid - 1
  }
  return if (low == 0) "" else text.substring(0, low) + ellipsis
}

private val Int.scale: Int get() = JBUIScale.scale(this)

private val Float.scale: Float get() = JBUIScale.scale(this)

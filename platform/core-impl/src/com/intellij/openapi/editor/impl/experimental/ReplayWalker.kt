// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import java.util.TreeMap

/**
 * The engine behind [EgWalkerReplay]: the reference implementation's `EditContext`, plus the
 * walk that drives it. See [EgWalkerReplay] for the algorithm and the port conventions.
 *
 * The walker is temporary and single use: the caller runs one replay and discards it. The
 * maps hold only the units the walk touches, so the state costs O(region), not O(graph).
 */
internal class ReplayWalker(private val graph: EventGraphImpl, placeholderCount: Int) {

  /** Every item in document order. The list only grows: a split inserts, nothing removes. */
  private val items = ArrayList<Item>()

  /** For a delete unit, the unit id it deleted. */
  private val delTargets = HashMap<LV, LV>()

  /**
   * Every item by its first unit id. A span covers a range, so a lookup takes the floor
   * entry and then checks that the item really covers the unit. Ranges never overlap, so
   * the floor entry is the only candidate.
   */
  private val itemsByUnit = TreeMap<LV, Item>()

  /** The prepare version. The reference calls this `curVersion`. */
  private var curVersion: Frontier = IntArray(0)

  /**
   * The last boundary a lookup produced, or that an apply advanced past. A sequential run
   * lands at or after it, so the walk resumes there instead of rescanning from the head. A
   * retreat or an advance changes prepare widths anywhere, which resets the cache.
   */
  private var cachedItemIndex = 0
  private var cachedPreparePos = 0
  private var cachedEffectPos = 0

  /**
   * Where the current phase reports its effects. Null means the phase only rebuilds the
   * concurrency context, and then the ops skip the work of producing the output at all.
   */
  private var sink: EgWalkerReplay.Sink? = null

  init {
    if (placeholderCount > 0) {
      // One item for the whole ancestor document; the ops split it lazily.
      addItem(
        0,
        Item(
          lv = -1 - placeholderCount,
          length = placeholderCount,
          originLeft = NO_UNIT,
          rightParent = NO_UNIT,
        ),
      )
    }
  }

  // ------------------------------------------------------------------------------- the two walks

  /**
   * Starts the prepare version at [ancestor], which the walk never moves below.
   *
   * This takes a [VersionImpl] and not a [Frontier], although the walk keeps a frontier.
   * The caller has an [EventGraphImpl.Conflict] in hand, whose other two fields are lists
   * of units, and a real type is what stops one of those reaching here by mistake.
   */
  fun startAt(ancestor: VersionImpl) {
    // A copy, because the walk writes the single-head array in place.
    curVersion = ancestor.toLvs()
  }

  /** Walks the whole graph, or the part of it that a past [version] selects. */
  fun replayAt(version: VersionImpl, sink: EgWalkerReplay.Sink) {
    val subset = if (version == graph.versionImpl()) {
      null
    } else {
      graph.eventsOf(version)
    }
    this.sink = sink
    val size = graph.size()
    var lv = 0
    while (lv < size) {
      if (subset != null && !subset.get(lv)) {
        lv++
        continue
      }
      val count = runBatch(lv) { next: LV ->
        subset == null || subset.get(next)
      }
      step(lv, count)
      lv += count
    }
  }

  /** Walks an ascending lv list, one whole run per step where the list allows it. */
  fun walk(lvs: LvList, sink: EgWalkerReplay.Sink?) {
    this.sink = sink
    var i = 0
    while (i < lvs.size) {
      val lv = lvs[i]
      val count = runBatch(lv) { next: LV ->
        i + (next - lv) < lvs.size && lvs[i + (next - lv)] == next
      }
      step(lv, count)
      i += count
    }
  }

  /**
   * How many units from [lv] the walk can take in one step: the units of one run that
   * [holds] also has. The scan stops at the run end, because a later run needs its own
   * version move.
   */
  private inline fun runBatch(lv: LV, holds: (LV) -> Boolean): Int {
    val limit = graph.runEndOf(lv) - lv
    var count = 1
    while (count < limit && holds(lv + count)) {
      count++
    }
    return count
  }

  /**
   * Consumes [count] units of one run from [lv]: moves the prepare version to the first
   * unit's parents, then applies the whole span. Every unit of a run after the first has
   * the one parent `lv - 1`, so the later units need no version move.
   *
   * The version move is also what lets an apply take an OFFSET. An offset indexes the
   * document at the event's parents, and the prepare version is that document once the move
   * is done. So the two spaces coincide exactly here, and nowhere else.
   */
  private fun step(lv: LV, count: Int) {
    val parents = graph.parentsOf(lv)
    // The sequential case: the prepare version already is the parents, so the diff is
    // empty. This skips a queue-and-map diff walk per run.
    if (!curVersion.contentEquals(parents)) {
      val diff = graph.diff(curVersion, parents)
      if (!diff.isEmpty()) {
        // The prepare widths change at arbitrary places: the cached cursor is stale.
        cacheCursor(0, 0, 0)
      }
      moveRange(diff.aOnly, retreating = true)
      moveRange(diff.bOnly, retreating = false)
    }
    if (graph.isDeleteAt(lv)) {
      applyDelete(lv, count, graph.offsetAt(lv))
    } else {
      applyInsert(lv, count, graph.offsetAt(lv))
    }
    setCurVersion(lv + count - 1)
  }

  // ------------------------------------------------------------------------- retreat and advance

  /**
   * Moves the prepare version over [lvs], in batches that share one item.
   *
   * A retreat walks backwards, so an item is undeleted before it is uninserted. A batch
   * covers the units that land on one contiguous range of one item, so a whole run usually
   * moves in one step and the item keeps its span. Only a batch that covers part of an item
   * splits it.
   */
  private fun moveRange(lvs: LvList, retreating: Boolean) {
    if (retreating) {
      var end = lvs.size
      while (end > 0) {
        val start = batchStart(lvs, end)
        move(lvs[start], end - start, true)
        end = start
      }
    } else {
      var start = 0
      while (start < lvs.size) {
        val count = batchLength(lvs, start, lvs.size - start)
        move(lvs[start], count, false)
        start += count
      }
    }
  }

  /** The number of entries from [from], at most [limit], that form one batch. */
  private fun batchLength(lvs: LvList, from: Int, limit: Int): Int {
    val anchor = Batch(lvs, from)
    var count = 1
    while (count < limit && anchor.covers(from + count)) {
      count++
    }
    return count
  }

  /** The first index of the batch that ends just before [end]. */
  private fun batchStart(lvs: LvList, end: Int): Int {
    val last = end - 1
    val anchor = Batch(lvs, last)
    var start = last
    while (start > 0 && anchor.covers(start - 1)) {
      start--
    }
    return start
  }

  /**
   * One batch, anchored on the entry at [anchorIndex]. [covers] answers whether another
   * entry belongs to the same batch. Both scan directions ask the same question, so the
   * rule lives in one place.
   */
  private inner class Batch(
    private val lvs: LvList,
    private val anchorIndex: Int,
  ) {
    private val isDelete: Boolean = graph.isDeleteAt(lvs[anchorIndex])
    private val target: LV = targetUnitOf(isDelete, lvs[anchorIndex])
    private val item: Item = itemBy(target)

    /**
     * Whether the entry at [entryIndex] is the same kind, the next lv, and the next unit of
     * the item. [entryIndex] indexes [lvs] and never the item list.
     */
    fun covers(entryIndex: Int): Boolean {
      val offset = entryIndex - anchorIndex
      val lv = lvs[anchorIndex] + offset
      if (lvs[entryIndex] != lv || graph.isDeleteAt(lv) != isDelete) {
        return false
      }
      val unit = target + offset
      return targetUnitOf(isDelete, lv) == unit && item.contains(unit)
    }

    override fun toString(): String {
      val kind = if (isDelete) "delete" else "insert"
      return "Batch($kind, anchorLv=${lvs[anchorIndex]}, target=$target, $item)"
    }
  }

  /** Retreats or advances the [count] units that [lv] starts, all inside one item. */
  private fun move(lv: LV, count: Int, retreating: Boolean) {
    val isDelete = graph.isDeleteAt(lv)
    val item = isolate(targetUnitOf(isDelete, lv), count)
    if (retreating) {
      item.retreat(isDelete)
    } else {
      item.advance(isDelete)
    }
  }

  /** The unit that [lv] changes: the item it deleted, or itself when it is an insert. */
  private fun targetUnitOf(isDelete: Boolean, lv: LV): LV {
    return if (isDelete) delTargets[lv]!! else lv
  }

  /**
   * Returns the item that covers exactly the [count] units from [target], and splits the
   * containing item when it is wider. The whole-item case needs no index, so it never scans
   * the item list.
   */
  private fun isolate(target: LV, count: Int): Item {
    var item = itemBy(target)
    // The batch rule keeps a range inside one item. Without this check a wider range would
    // change only the part that fits, and leave the rest silently unmoved.
    require(item.contains(target + count - 1)) {
      "The range of $count units from $target does not fit the item $item"
    }
    if (item.coversExactly(target, count)) {
      return item
    }
    var index = findItemIdx(target)
    if (item.startsBefore(target)) {
      splitItem(index, target - item.lv)
      index++
      item = items[index]
    }
    if (item.length > count) {
      splitItem(index, count)
      item = items[index]
    }
    return item
  }

  // ------------------------------------------------------------------------------------- the ops

  /**
   * Deletes [count] units at [offset], the offset the run itself recorded. Every unit of a
   * delete run removes at the same offset, so the run eats the items there one after
   * another. An item that reaches past the run splits, so the deleted part stays exact.
   *
   * One lookup carries the whole run, because a consumed item keeps no prepare width. A
   * fresh lookup per item would first back up over everything the run already consumed, and
   * then walk forward over it again.
   */
  private fun applyDelete(lv: LV, count: Int, offset: Int) {
    val cursor = findByCurPos(offset)
    var done = 0
    while (done < count) {
      // Skip the items that the prepare version does not have.
      while (!hasItemToDelete(cursor, lv)) {
        cursor.advanceOver(items[cursor.itemIndex])
      }
      val taken = minOf(count - done, items[cursor.itemIndex].length)
      if (items[cursor.itemIndex].length > taken) {
        splitItem(cursor.itemIndex, taken)
      }
      val item = items[cursor.itemIndex]
      // A concurrent delete may have removed the characters from the effect version.
      if (item.inEffect) {
        // Every unit of the run removes at the same position, so one call carries them all.
        sink?.delete(cursor.effectPos, taken)
      }
      item.deleteHere()
      for (k in 0 until taken) {
        delTargets[lv + done + k] = item.lv + k
      }
      // The item now has no width in either version, so only the index moves.
      cursor.advanceOver(item)
      done += taken
    }
    cacheCursor(cursor)
  }

  /**
   * Whether the prepare version has the item at the cursor, so the delete takes that one.
   *
   * This also bounds the skip loop of [applyDelete]. A run that deletes more units than the
   * document at its own parents holds would otherwise walk off the end of the item list.
   */
  private fun hasItemToDelete(cursor: Cursor, lv: LV): Boolean {
    require(cursor.itemIndex < items.size) {
      "The delete run at the lv $lv reached the end of the item list"
    }
    return items[cursor.itemIndex].inPrepare
  }

  /**
   * Inserts [count] units from [lv] as one span at [offset], the offset the run recorded.
   *
   * Only the first unit of a run needs the Fugue integration. A later unit lands right
   * after the one before it, because its left origin is that unit and no other item can
   * name it yet: the walk visits the units in one go, with no retreat or advance between
   * them. So the span carries the first unit's origins.
   */
  private fun applyInsert(lv: LV, count: Int, offset: Int) {
    val cursor = findByCurPos(offset)
    require(cursor.itemIndex == 0 || items[cursor.itemIndex - 1].inPrepare) {
      "The item before the insert point is not inserted in the prepare version"
    }
    // The left origin is the last unit the left neighbour covers.
    val originLeft = if (cursor.itemIndex == 0) NO_UNIT else items[cursor.itemIndex - 1].lastUnit
    val rightParent = rightParentAt(cursor.itemIndex, originLeft)
    val newItem = Item(
      lv = lv,
      length = count,
      originLeft = originLeft,
      rightParent = rightParent,
    )
    integrate(newItem, cursor)
    addItem(cursor.itemIndex, newItem)
    // The span sits inside one run, so one slice of its fragment covers it. A silent phase
    // skips the slice, which is the only work the report costs.
    sink?.insert(cursor.effectPos, graph.fragmentAt(lv, count))
    cursor.advanceOver(newItem)
    cacheCursor(cursor)
  }

  /**
   * The right parent of an insert at [index]: the next item that the prepare version has
   * already applied, when that item shares the left origin.
   *
   * This is the reference implementation's Fugue variant. FugueMax would take that item
   * unconditionally.
   */
  private fun rightParentAt(index: Int, originLeft: LV): LV {
    for (i in index until items.size) {
      val next = items[i]
      if (next.appliedInPrepare) {
        // A right parent always names the FIRST unit of a span, and a left origin always
        // names the LAST unit of one. A split keeps both, so the two stay comparable.
        return if (next.originLeft == originLeft) next.firstUnit else NO_UNIT
      }
    }
    return NO_UNIT
  }

  // ------------------------------------------------------------------------------------ the scan

  /**
   * Finds the place for a concurrent insertion among the not-yet-inserted items at the
   * cursor. A direct port of `integrate` from the reference implementation, which ports
   * YjsMod / Fugue. The cursor moves to the found place.
   */
  private fun integrate(newItem: Item, cursor: Cursor) {
    // Without concurrency there is nothing to scan.
    if (cursor.itemIndex >= items.size || items[cursor.itemIndex].appliedInPrepare) {
      return
    }
    var scanning = false
    var scanIdx = cursor.itemIndex
    var scanEffectPos = cursor.effectPos
    val leftIdx = cursor.itemIndex - 1
    val rightIdx = indexOfBound(newItem.rightParent)
    while (scanIdx < items.size) {
      val other = items[scanIdx]
      if (other.appliedInPrepare) {
        break
      }
      require(!other.contains(newItem.rightParent)) {
        "The scan reached the right parent of the new item"
      }
      val otherLeftIdx = if (other.originLeft == NO_UNIT) -1 else findItemIdx(other.originLeft)
      if (otherLeftIdx < leftIdx) {
        break
      }
      if (otherLeftIdx == leftIdx) {
        val otherRightIdx = indexOfBound(other.rightParent)
        if (otherRightIdx == rightIdx && graph.compareEvents(newItem.lv, other.lv) < 0) {
          break
        }
        scanning = otherRightIdx < rightIdx
      }
      scanEffectPos += other.effectWidth
      scanIdx++
      if (!scanning) {
        cursor.moveTo(scanIdx, scanEffectPos)
      }
    }
  }

  /** The scan bound that a right parent names. [NO_UNIT] means the end of the list. */
  private fun indexOfBound(rightParent: LV): Int {
    return if (rightParent == NO_UNIT) items.size else findItemIdx(rightParent)
  }

  /**
   * Finds the insert point for [targetPreparePos], walking from the cached cursor. The
   * reference calls this `findByCurPos`, and its `curPos` is this port's prepare position.
   */
  private fun findByCurPos(targetPreparePos: Int): Cursor {
    val cursor = if (cachedPreparePos <= targetPreparePos) {
      Cursor(cachedItemIndex, cachedPreparePos, cachedEffectPos)
    } else {
      Cursor(0, 0, 0)
    }
    while (cursor.preparePos < targetPreparePos) {
      require(cursor.itemIndex < items.size) {
        "The document is not long enough for the requested position"
      }
      val item = items[cursor.itemIndex]
      if (cursor.preparePos + item.prepareWidth > targetPreparePos) {
        // The boundary falls inside this span: split it, then retry the left piece.
        splitItem(cursor.itemIndex, targetPreparePos - cursor.preparePos)
        continue
      }
      cursor.advanceOver(item)
    }
    // A cached start can sit after zero-width items at this position. Back up to the
    // earliest boundary: that is where a head-to-target walk stops, and the insert
    // anchoring depends on it.
    while (cursor.itemIndex > 0 && items[cursor.itemIndex - 1].prepareWidth == 0) {
      cursor.retreatOver(items[cursor.itemIndex - 1])
    }
    return cursor
  }

  /** Finds the item that covers [needleLv]: an exact item, or the containing span. */
  private fun findItemIdx(needleLv: LV): Int {
    for (i in items.indices) {
      if (items[i].contains(needleLv)) {
        return i
      }
    }
    throw IllegalStateException("The item $needleLv is not in the item list")
  }

  // ------------------------------------------------------------------------- the item bookkeeping

  /** Splits the span at [itemIndex] after [offset] units and files the new right piece. */
  private fun splitItem(itemIndex: Int, offset: Int) {
    addItem(itemIndex + 1, items[itemIndex].splitAfter(offset))
  }

  /**
   * Adds [item] to the list at [itemIndex] and to the unit index. The two must stay in step.
   */
  private fun addItem(itemIndex: Int, item: Item) {
    items.add(itemIndex, item)
    itemsByUnit[item.lv] = item
  }

  private fun itemBy(unit: LV): Item {
    val item = itemsByUnit.floorEntry(unit)?.value
    require(item != null && item.contains(unit)) {
      "No item covers the unit $unit"
    }
    return item
  }

  private fun cacheCursor(cursor: Cursor) {
    cacheCursor(cursor.itemIndex, cursor.preparePos, cursor.effectPos)
  }

  private fun cacheCursor(itemIndex: Int, preparePos: Int, effectPos: Int) {
    cachedItemIndex = itemIndex
    cachedPreparePos = preparePos
    cachedEffectPos = effectPos
  }

  private fun setCurVersion(lv: LV) {
    // Reuse the single-head array: nothing retains the old version.
    if (curVersion.size == 1) {
      curVersion[0] = lv
    } else {
      curVersion = intArrayOf(lv)
    }
  }

  /**
   * The walk state, by size and not by content. The item list holds one entry per span of
   * the walked region, so printing it would print the region.
   */
  override fun toString(): String {
    val cached = Cursor(cachedItemIndex, cachedPreparePos, cachedEffectPos)
    return "ReplayWalker(items=${items.size}, delTargets=${delTargets.size}, " +
           "prepare=v${curVersion.listedForMessage()}, cached=$cached, reporting=${sink != null})"
  }
}

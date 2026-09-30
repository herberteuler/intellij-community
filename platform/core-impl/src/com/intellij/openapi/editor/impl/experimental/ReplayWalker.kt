// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import java.util.BitSet
import java.util.TreeMap

/**
 * The engine behind [EgWalkerReplay]: the reference implementation's `EditContext`, plus the
 * walk that drives it. See [EgWalkerReplay] for the algorithm and the port conventions.
 *
 * The walker is temporary and single use: the caller runs one replay and discards it. The
 * item list, the item map and the delete targets hold only the units the walk touches, so the
 * state costs O(region), not O(graph).
 */
internal class ReplayWalker(private val graph: EventGraphImpl, placeholderCount: Int) {

  /** Every item in document order. The list only grows: a split inserts, nothing removes. */
  private val items = ArrayList<Item>()

  /** For each delete unit, the lv of the unit it deleted. */
  private val delTargets = DeleteTargets()

  /**
   * Every item by the lv of its first unit. A span covers a range, so a lookup takes the floor
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
          firstUnit = -1 - placeholderCount,
          length = placeholderCount,
          originLeft = NO_UNIT,
          rightParent = NO_UNIT,
        ),
      )
    }
  }

  // ------------------------------------------------------------------------------- the two walks

  /**
   * Starts the prepare version at [ancestor], which the walk never moves below. It takes a
   * [VersionImpl] and not a raw [Frontier], so only a checked version can reach it.
   */
  fun startAt(ancestor: VersionImpl) {
    curVersion = ancestor.lvs
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
    var lv = nextToWalk(subset, 0)
    while (lv in 0 until size) {
      val run = graph.runAt(lv)
      // A unit of a run descends from every unit before it, so a past version holds a prefix of
      // each run it touches. The step ends at the run end, or where that prefix ends.
      val end = if (subset == null) run.lvEnd() else minOf(run.lvEnd(), subset.nextClearBit(lv))
      step(run, lv, end - lv)
      lv = nextToWalk(subset, end)
    }
  }

  /**
   * The first lv at or after [from] that the walk takes. A value outside `[0, size)` means none is
   * left. A past version leaves out whole regions, so the walk jumps over them and does not step
   * through them.
   */
  private fun nextToWalk(subset: BitSet?, from: LV): LV {
    return subset?.nextSetBit(from) ?: from
  }

  /** Walks [ranges], one whole run per step where a range allows it. */
  fun walk(ranges: LvRanges, sink: EgWalkerReplay.Sink?) {
    this.sink = sink
    for (index in 0 until ranges.size()) {
      var lv = ranges.start(index)
      val end = ranges.end(index)
      while (lv < end) {
        val run = graph.runAt(lv)
        val count = minOf(end, run.lvEnd()) - lv
        step(run, lv, count)
        lv += count
      }
    }
  }

  /**
   * Consumes [count] units of [run] from [lv]: moves the prepare version to the first unit's
   * parents, then applies the whole span. Every unit of a run after the first has the one
   * parent `lv - 1`, so the later units need no version move.
   *
   * The version move is also what lets an apply take an OFFSET. An offset indexes the
   * document at the event's parents, and the prepare version is that document once the move
   * is done. So the two spaces coincide exactly here, and nowhere else.
   *
   * The caller looked [run] up once, and every question of the step goes to it. A graph lookup
   * per question would search the runs again for the same lv.
   */
  private fun step(run: StoredRun, lv: LV, count: Int) {
    val parents = run.parentsOf(lv)
    // The sequential case: the prepare version already is the parents, so the diff is
    // empty. This skips a queue-and-map diff walk per run.
    if (!curVersion.contentEquals(parents)) {
      val diff = graph.diff(curVersion, parents)
      if (!diff.isEmpty()) {
        // The prepare widths change at arbitrary places: the cached cursor is stale.
        resetCursorCache()
      }
      retreatRanges(diff.aOnly)
      advanceRanges(diff.bOnly)
    }
    if (run.isDelete()) {
      applyDelete(lv, count, run.offsetAt(lv))
    } else {
      applyInsert(run, lv, count, run.offsetAt(lv))
    }
    setCurVersion(lv + count - 1)
  }

  // ------------------------------------------------------------------------- retreat and advance

  /**
   * Takes [ranges] back out of the prepare version, in batches that share one item.
   *
   * A retreat walks backwards, so an item is undeleted before it is uninserted. A batch covers the
   * units of a range that change one contiguous part of one item. So a whole run usually moves in
   * one step, and the item keeps its span. Only a batch that covers part of an item splits it.
   */
  private fun retreatRanges(ranges: LvRanges) {
    for (index in ranges.size() - 1 downTo 0) {
      val start = ranges.start(index)
      var end = ranges.end(index)
      while (end > start) {
        end = retreatBatchBefore(start, end)
      }
    }
  }

  /** Puts [ranges] back into the prepare version, forwards, in the batches of [retreatRanges]. */
  private fun advanceRanges(ranges: LvRanges) {
    for (index in 0 until ranges.size()) {
      var start = ranges.start(index)
      val end = ranges.end(index)
      while (start < end) {
        start = advanceBatchFrom(start, end)
      }
    }
  }

  /**
   * Advances the batch that starts at [lv] and ends at or before [end], and returns the lv after it.
   *
   * An insert unit changes itself, and an item holds the units of one insert run, so the batch ends
   * where the item ends. A delete unit changes the unit it deleted, so the batch also ends where
   * its piece of [DeleteTargets] ends. Past that end, the targets stop being consecutive. The item
   * end implies that bound today. [applyDelete] splits the item at the ends of each part that one
   * item gives a piece, and an item never grows. The bound keeps the batch right without that fact.
   */
  private fun advanceBatchFrom(lv: LV, end: LV): LV {
    val isDelete = graph.isDeleteAt(lv)
    val target = targetUnitOf(isDelete, lv)
    var batchEnd = minOf(end, lv + (itemBy(target).lastUnit + 1 - target))
    if (isDelete) {
      batchEnd = minOf(batchEnd, delTargets.pieceEndOf(lv))
    }
    isolate(target, batchEnd - lv).advance(isDelete)
    return batchEnd
  }

  /**
   * Retreats the batch that ends just before [end] and starts at or after [start], and returns its
   * first lv. The mirror of [advanceBatchFrom].
   */
  private fun retreatBatchBefore(start: LV, end: LV): LV {
    val last = end - 1
    val isDelete = graph.isDeleteAt(last)
    val lastTarget = targetUnitOf(isDelete, last)
    var batchStart = maxOf(start, last - (lastTarget - itemBy(lastTarget).firstUnit))
    if (isDelete) {
      batchStart = maxOf(batchStart, delTargets.pieceStartOf(last))
    }
    isolate(lastTarget - (last - batchStart), end - batchStart).retreat(isDelete)
    return batchStart
  }

  /** The unit that [lv] changes: the item it deleted, or itself when it is an insert. */
  private fun targetUnitOf(isDelete: Boolean, lv: LV): LV {
    return if (isDelete) delTargets.targetOf(lv) else lv
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
      splitItem(index, target - item.firstUnit)
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
      delTargets.add(lv + done, item.firstUnit, taken)
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
   * Inserts [count] units of [run] from [lv] as one span at [offset], the offset the run recorded.
   *
   * Only the first unit of the span needs the Fugue integration. A later unit lands right after the
   * one before it, because its left origin is that unit. No other item can name it yet, because the
   * walk visits the units in one go, with no retreat or advance between them. So the span carries
   * the origins of its first unit.
   */
  private fun applyInsert(
    run: StoredRun,
    lv: LV,
    count: Int,
    offset: Int,
  ) {
    val cursor = findByCurPos(offset)
    require(cursor.itemIndex == 0 || items[cursor.itemIndex - 1].inPrepare) {
      "The item before the insert point is not inserted in the prepare version"
    }
    // The left origin is the last unit the left neighbour covers.
    val originLeft = if (cursor.itemIndex == 0) NO_UNIT else items[cursor.itemIndex - 1].lastUnit
    val rightParent = rightParentAt(cursor.itemIndex, originLeft)
    val newItem = Item(
      firstUnit = lv,
      length = count,
      originLeft = originLeft,
      rightParent = rightParent,
    )
    integrate(newItem, cursor)
    addItem(cursor.itemIndex, newItem)
    // The span sits inside one run, so one slice of its fragment covers it. A silent phase
    // skips the slice, which is the only work the report costs.
    sink?.insert(cursor.effectPos, run.fragmentFrom(lv, count))
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
        if (otherRightIdx == rightIdx && graph.lvCmp(newItem.firstUnit, other.firstUnit) < 0) {
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
        "The document is not long enough for the prepare position $targetPreparePos"
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
    val index = items.indexOfFirst { it.contains(needleLv) }
    require(index >= 0) {
      "No item covers the lv $needleLv"
    }
    return index
  }

  // ------------------------------------------------------------------------- the item bookkeeping

  /** Splits the span at [itemIndex] after [units] units and files the new right piece. */
  private fun splitItem(itemIndex: Int, units: Int) {
    addItem(itemIndex + 1, items[itemIndex].splitAfter(units))
  }

  /**
   * Adds [item] to the list at [itemIndex] and to the unit index. The two must stay in step.
   */
  private fun addItem(itemIndex: Int, item: Item) {
    items.add(itemIndex, item)
    itemsByUnit[item.firstUnit] = item
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

  /** Sends the next lookup to the start of the item list. The start is valid for any widths. */
  private fun resetCursorCache() {
    cacheCursor(0, 0, 0)
  }

  /**
   * Moves the prepare version to the one head [lv]. This always allocates. An in-place write would
   * save one small array per step. But the walk compares [curVersion] with the parents arrays of
   * the graph. One wrong assignment would then corrupt a run that other graph values share.
   */
  private fun setCurVersion(lv: LV) {
    curVersion = intArrayOf(lv)
  }

  /**
   * The walk state, by size and not by content. The item list holds one entry per span of
   * the walked region, so printing it would print the region.
   */
  override fun toString(): String {
    val cached = Cursor(cachedItemIndex, cachedPreparePos, cachedEffectPos)
    return "ReplayWalker(items=${items.size}, delTargets=${delTargets.size()}, " +
           "prepare=v${curVersion.listedForMessage()}, cached=$cached, reporting=${sink != null})"
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import org.jetbrains.annotations.TestOnly
import java.util.BitSet

/**
 * No move: the step is not a whole move, or its moved text is not intact.
 */
private const val NO_MOVE = -1

/**
 * The engine behind [EgWalkerReplay]: the reference implementation's `EditContext`, plus the
 * walk that drives it. See [EgWalkerReplay] for the algorithm and the port conventions.
 *
 * The walker is temporary and single use: the caller runs one replay and discards it. The item
 * tree and the delete targets hold only the units the walk touches, so the state costs O(region),
 * not O(graph).
 */
internal class ReplayWalker(private val graph: EventGraphImpl, placeholderCount: Int) {

  /**
   * Every item in document order, with the lookups by index, by prepare position, by unit, and of
   * the next applied item.
   */
  private val items = ItemTree()

  /**
   * For each delete unit, the lv of the unit it deleted.
   */
  private val delTargets = DeleteTargets()

  /**
   * The prepare version. The reference calls this `curVersion`.
   */
  private var curVersion: Frontier = IntArray(0)

  /**
   * The last boundary that an apply advanced past. A sequential edit lands there, so it needs no
   * lookup in the tree. A retreat or an advance changes prepare widths anywhere, which resets the
   * cache.
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
      items.add(
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
   * an [LvVersion] and not a raw [Frontier], so only a checked version can reach it.
   */
  fun startAt(ancestor: LvVersion) {
    curVersion = ancestor.lvs
  }

  /**
   * Walks the whole graph, or the part of it that a past [version] selects.
   */
  fun replayAt(version: LvVersion, sink: EgWalkerReplay.Sink) {
    val subset = if (version == graph.lvVersion()) {
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

  /**
   * Walks [ranges], one whole run per step where a range allows it.
   */
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
    val moveOffset = moveOffsetOf(run, lv, count)
    if (run.isDelete()) {
      applyDelete(lv, count, run.offsetAt(lv), moveOffset)
    } else {
      applyInsert(run, lv, count, run.offsetAt(lv), moveOffset)
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

  /**
   * Puts [ranges] back into the prepare version, forwards, in the batches of [retreatRanges].
   */
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
    val itemEnd = lv + (items.itemCovering(target).lastUnit + 1 - target)
    var batchEnd = minOf(end, itemEnd)
    if (isDelete) {
      batchEnd = minOf(batchEnd, delTargets.pieceEndOf(lv))
    }
    items.advance(isolate(target, batchEnd - lv), isDelete)
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
    val itemStart = last - (lastTarget - items.itemCovering(lastTarget).firstUnit)
    var batchStart = maxOf(start, itemStart)
    if (isDelete) {
      batchStart = maxOf(batchStart, delTargets.pieceStartOf(last))
    }
    val firstTarget = lastTarget - (last - batchStart)
    items.retreat(isolate(firstTarget, end - batchStart), isDelete)
    return batchStart
  }

  /**
   * The unit that [lv] changes: the item it deleted, or itself when it is an insert.
   */
  private fun targetUnitOf(isDelete: Boolean, lv: LV): LV {
    return if (isDelete) delTargets.targetOf(lv) else lv
  }

  /**
   * Returns the item that covers exactly the [count] units from [target], and splits the
   * containing item when it is wider. The whole-item case needs no [findItemIdx].
   */
  private fun isolate(target: LV, count: Int): Item {
    var item = items.itemCovering(target)
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
      items.splitAt(index, target - item.firstUnit)
      index++
      item = items.get(index)
    }
    if (item.length > count) {
      items.splitAt(index, count)
      item = items.get(index)
    }
    return item
  }

  // ------------------------------------------------------------------------------------- the ops

  /**
   * Deletes [count] units at [offset], the offset the run itself recorded. Every unit of a
   * delete run removes at the same offset, so the run eats the items there one after
   * another. An item that reaches past the run splits, so the deleted part stays exact.
   *
   * A consumed item keeps no prepare width, so the next unit of the run deletes at the same
   * prepare position. When the item at the cursor has no prepare width, the tree finds the next one
   * that has it. The items of no prepare width stay as they are, as in the reference, which steps
   * over them one by one.
   *
   * [moveOffset] is the copy of a move delete, or [NO_MOVE].
   */
  private fun applyDelete(
    lv: LV,
    count: Int,
    offset: Int,
    moveOffset: Int,
  ) {
    val copyEffectPos = copyEffectPosOf(offset, count, moveOffset)
    var cursor = findByCurPos(offset)
    var done = 0
    while (done < count) {
      if (!hasItemToDelete(cursor)) {
        cursor = nextItemToDelete(lv, offset)
      }
      val taken = minOf(count - done, items.get(cursor.itemIndex).length)
      if (items.get(cursor.itemIndex).length > taken) {
        items.splitAt(cursor.itemIndex, taken)
      }
      val item = items.get(cursor.itemIndex)
      // A concurrent delete may have removed the characters from the effect version.
      if (item.inEffect) {
        // Every unit of the run removes at the same position, so one call carries them all.
        reportDelete(cursor.effectPos, taken, copyEffectPos)
      }
      items.deleteHere(item)
      delTargets.add(lv + done, item.firstUnit, taken)
      // The item now has no width in either version, so only the index moves.
      cursor.advanceOver(item)
      done += taken
    }
    cacheCursor(cursor)
  }

  /**
   * Whether the prepare version has the item at the cursor, so the delete takes that one.
   */
  private fun hasItemToDelete(cursor: Cursor): Boolean {
    return cursor.itemIndex < items.size() && items.get(cursor.itemIndex).inPrepare
  }

  /**
   * The cursor before the first item at the prepare position [offset] that the prepare version
   * has. It fails for a run that deletes more units than the document at its own parents holds.
   */
  private fun nextItemToDelete(lv: LV, offset: Int): Cursor {
    checkDeleteInDocument(lv, offset)
    return items.cursorBefore(offset)
  }

  private fun checkDeleteInDocument(lv: LV, offset: Int) {
    require(offset < items.prepareWidth()) {
      "The delete run at the lv $lv reaches past the end of the document"
    }
  }

  /**
   * Inserts [count] units of [run] from [lv] as one span at [offset], the offset the run recorded.
   *
   * Only the first unit of the span needs the Fugue integration. A later unit lands right after the
   * one before it, because its left origin is that unit. No other item can name it yet, because the
   * walk visits the units in one go, with no retreat or advance between them. So the span carries
   * the origins of its first unit.
   *
   * [moveOffset] is the source of a move insert, or [NO_MOVE].
   */
  private fun applyInsert(
    run: StoredRun,
    lv: LV,
    count: Int,
    offset: Int,
    moveOffset: Int,
  ) {
    val cursor = findByCurPos(offset)
    val left = leftNeighbourOf(cursor)
    checkInsertedBefore(left)
    // The left origin is the last unit the left neighbour covers.
    val originLeft = left?.lastUnit ?: NO_UNIT
    val rightParent = rightParentAt(cursor.itemIndex, originLeft)
    val newItem = Item(
      firstUnit = lv,
      length = count,
      originLeft = originLeft,
      rightParent = rightParent,
    )
    integrate(newItem, cursor, run)
    items.add(cursor.itemIndex, newItem)
    // The span sits inside one run, so one slice of its fragment covers it. A silent phase
    // skips the slice, which is the only work the report costs.
    val reportTo = sink
    if (reportTo != null) {
      val fragment = run.fragmentFrom(lv, count)
      reportInsert(reportTo, cursor.effectPos, fragment, moveOffset)
    }
    cursor.advanceOver(newItem)
    cacheCursor(cursor)
  }

  /**
   * The item right before [cursor], or `null` at the document start.
   */
  private fun leftNeighbourOf(cursor: Cursor): Item? {
    if (cursor.itemIndex == 0) {
      return null
    }
    return items.get(cursor.itemIndex - 1)
  }

  private fun checkInsertedBefore(left: Item?) {
    require(left == null || left.inPrepare) {
      "The item before the insert point is not inserted in the prepare version"
    }
  }

  /**
   * The right parent of an insert at [index]: the next item that the prepare version has
   * already applied, when that item shares the left origin.
   *
   * This is the reference implementation's Fugue variant. FugueMax would take that item
   * unconditionally.
   */
  private fun rightParentAt(index: Int, originLeft: LV): LV {
    val next = items.firstAppliedFrom(index)
    // A right parent always names the FIRST unit of a span, and a left origin always names the
    // LAST unit of one. A split keeps both, so the two stay comparable.
    return if (next != null && next.originLeft == originLeft) {
      next.firstUnit
    } else {
      NO_UNIT
    }
  }

  // ----------------------------------------------------------------------------------- the moves

  /**
   * The move offset of the op that a step of [count] units of [run] from [lv] applies, or [NO_MOVE].
   * Only a reporting phase needs it, and only a whole run is a move.
   */
  private fun moveOffsetOf(run: StoredRun, lv: LV, count: Int): Int {
    if (sink == null || !run.isMove() || !run.isWholeRun(lv, count)) {
      return NO_MOVE
    }
    return run.moveOffset()
  }

  /**
   * Reports an insert at [effectPos], as the first half of a move when the source at [moveOffset]
   * is intact. The insert is applied, so [moveOffset] indexes the prepare version as it is.
   */
  private fun reportInsert(
    reportTo: EgWalkerReplay.Sink,
    effectPos: Int,
    fragment: CharSequence,
    moveOffset: Int,
  ) {
    val sourceEffectPos = intactEffectStart(moveOffset, fragment.length)
    if (sourceEffectPos == NO_MOVE) {
      reportTo.insert(effectPos, fragment)
    } else {
      reportTo.moveInsert(effectPos, fragment, sourceEffectPos)
    }
  }

  /**
   * Reports a delete at [effectPos], as a piece of a move when [copyEffectPos] names its copy.
   */
  private fun reportDelete(effectPos: Int, count: Int, copyEffectPos: Int) {
    val reportTo = sink ?: return
    if (copyEffectPos == NO_MOVE) {
      reportTo.delete(effectPos, count)
    } else {
      reportTo.moveDelete(effectPos, count, copyEffectPos)
    }
  }

  /**
   * The effect position of the copy of a move delete, or [NO_MOVE] unless both the source at
   * [offset] and the copy at [moveOffset] are intact. The delete is not applied yet.
   */
  private fun copyEffectPosOf(offset: Int, count: Int, moveOffset: Int): Int {
    if (moveOffset == NO_MOVE || intactEffectStart(offset, count) == NO_MOVE) {
      return NO_MOVE
    }
    return intactEffectStart(moveOffset, count)
  }

  /**
   * The effect position of the [length] characters at [preparePos], or [NO_MOVE] when they are not
   * intact. They are intact when the effect version holds exactly them, with nothing between them.
   */
  private fun intactEffectStart(preparePos: Int, length: Int): Int {
    if (preparePos < 0 || length > items.prepareWidth() - preparePos) {
      return NO_MOVE
    }
    val cursor = items.cursorBefore(preparePos)
    val first = items.get(cursor.itemIndex)
    if (!first.inEffect) {
      return NO_MOVE
    }
    val unitsBefore = preparePos - cursor.preparePos
    var remaining = length - (first.length - unitsBefore)
    var index = cursor.itemIndex
    while (remaining > 0) {
      index++
      val item = items.get(index)
      // An item in one version only is a concurrent insert or a concurrent delete.
      if (item.inPrepare != item.inEffect) {
        return NO_MOVE
      }
      remaining -= item.prepareWidth
    }
    return cursor.effectPos + unitsBefore
  }

  // ------------------------------------------------------------------------------------ the scan

  /**
   * Finds the place for a concurrent insertion among the not-yet-inserted items at the
   * cursor. A direct port of `integrate` from the reference implementation, which ports
   * YjsMod / Fugue. The cursor moves to the found place. [newRun] is the run that holds the first
   * unit of [newItem], for the tie-break.
   */
  private fun integrate(newItem: Item, cursor: Cursor, newRun: StoredRun) {
    // Without concurrency there is nothing to scan.
    if (!hasUnappliedItemAt(cursor.itemIndex)) {
      return
    }
    var scanning = false
    var scanIdx = cursor.itemIndex
    var scanEffectPos = cursor.effectPos
    val leftIdx = cursor.itemIndex - 1
    val leftItem = leftNeighbourOf(cursor)
    val rightIdx = indexOfBound(newItem.rightParent)
    while (scanIdx < items.size()) {
      val other = items.get(scanIdx)
      if (other.appliedInPrepare) {
        break
      }
      require(!other.contains(newItem.rightParent)) {
        "The scan reached the right parent of the new item"
      }
      val leftOrder = leftOriginOrder(other.originLeft, leftIdx, leftItem)
      if (leftOrder < 0) {
        break
      }
      if (leftOrder == 0) {
        val otherRightIdx = rightBoundOf(other, newItem, rightIdx)
        val sameRight = otherRightIdx == rightIdx
        if (sameRight && graph.lvCmp(newRun, newItem.firstUnit, other.firstUnit) < 0) {
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

  /**
   * Compares the index of the item that covers [originLeft] with [leftIdx], as the reference
   * compares `oleftIdx` with `leftIdx`. The result is negative, zero, or positive. [leftItem] is
   * the item at [leftIdx]. Concurrent items at one place mostly have it as their left origin, and
   * then the answer needs no index.
   */
  private fun leftOriginOrder(originLeft: LV, leftIdx: Int, leftItem: Item?): Int {
    if (originLeft == NO_UNIT) {
      // The document start has the index -1.
      return (-1).compareTo(leftIdx)
    }
    if (leftItem != null && leftItem.contains(originLeft)) {
      return 0
    }
    return findItemIdx(originLeft).compareTo(leftIdx)
  }

  /**
   * The scan bound of the right parent of [other], a scanned item. When it names the right parent
   * of [newItem], it is [rightIdx], and no lookup is needed.
   */
  private fun rightBoundOf(other: Item, newItem: Item, rightIdx: Int): Int {
    if (other.rightParent == newItem.rightParent) {
      return rightIdx
    }
    return indexOfBound(other.rightParent)
  }

  /**
   * Whether an item at [index] exists that the prepare version has not applied: a concurrent one.
   */
  private fun hasUnappliedItemAt(index: Int): Boolean {
    return index < items.size() && !items.get(index).appliedInPrepare
  }

  /**
   * The scan bound that a right parent names. [NO_UNIT] means the end of the items.
   */
  private fun indexOfBound(rightParent: LV): Int {
    return if (rightParent == NO_UNIT) {
      items.size()
    } else {
      findItemIdx(rightParent)
    }
  }

  /**
   * The index of the item that covers [needleLv]. The reference calls this `findItemIdx`.
   */
  private fun findItemIdx(needleLv: LV): Int {
    return items.indexCovering(needleLv)
  }

  /**
   * Finds the insert point for [targetPreparePos]: the earliest boundary at that position. The
   * reference calls this `findByCurPos`, and its `curPos` is this port's prepare position.
   *
   * A sequential edit lands at the cached cursor. The cache answers it when the item before the
   * cursor has prepare width, because the boundary is then the earliest one. Any other lookup
   * goes to [boundaryAt]. The insert anchoring depends on the earliest boundary.
   */
  private fun findByCurPos(targetPreparePos: Int): Cursor {
    if (targetPreparePos == cachedPreparePos && isEarliestBoundary(cachedItemIndex)) {
      return Cursor(cachedItemIndex, cachedPreparePos, cachedEffectPos)
    }
    return boundaryAt(targetPreparePos)
  }

  /**
   * Whether the boundary before [itemIndex] is the earliest one at its prepare position.
   */
  private fun isEarliestBoundary(itemIndex: Int): Boolean {
    return itemIndex == 0 || items.get(itemIndex - 1).prepareWidth > 0
  }

  /**
   * The earliest boundary at [preparePos]: right after the item that covers the position before
   * it. When [preparePos] falls inside that item, the item splits there.
   */
  private fun boundaryAt(preparePos: Int): Cursor {
    checkInDocument(preparePos)
    if (preparePos == 0) {
      return Cursor(0, 0, 0)
    }
    val cursor = items.cursorBefore(preparePos - 1)
    val units = preparePos - cursor.preparePos
    if (items.get(cursor.itemIndex).length > units) {
      items.splitAt(cursor.itemIndex, units)
    }
    cursor.advanceOver(items.get(cursor.itemIndex))
    return cursor
  }

  private fun checkInDocument(preparePos: Int) {
    require(preparePos >= 0) {
      "The prepare position $preparePos is negative"
    }
    require(preparePos <= items.prepareWidth()) {
      "The document is not long enough for the prepare position $preparePos"
    }
  }

  // ------------------------------------------------------------------------ the cursor cache

  private fun cacheCursor(cursor: Cursor) {
    cacheCursor(cursor.itemIndex, cursor.preparePos, cursor.effectPos)
  }

  private fun cacheCursor(itemIndex: Int, preparePos: Int, effectPos: Int) {
    cachedItemIndex = itemIndex
    cachedPreparePos = preparePos
    cachedEffectPos = effectPos
  }

  /**
   * Sets the cached boundary to the start of the document, which is valid for any widths.
   */
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
   * Fails when an invariant of the item tree does not hold. See [ItemTree.checkInvariants].
   */
  @TestOnly
  fun checkItems() {
    items.checkInvariants()
  }

  /**
   * The inner levels of the item tree. See [ItemTree.depth].
   */
  @TestOnly
  fun itemTreeDepth(): Int {
    return items.depth()
  }

  /**
   * Whether the walk looked an item up by unit. See [ItemTree.hasUnitIndex].
   */
  @TestOnly
  fun hasUnitIndex(): Boolean {
    return items.hasUnitIndex()
  }

  /**
   * The walk state, by size and not by content. The item tree holds one entry per span of
   * the walked region, so printing it would print the region.
   */
  override fun toString(): String {
    val cached = Cursor(cachedItemIndex, cachedPreparePos, cachedEffectPos)
    return "ReplayWalker(items=${items.size()}, delTargets=${delTargets.size()}, " +
           "prepare=v${curVersion.listedForMessage()}, cached=$cached, reporting=${sink != null})"
  }
}

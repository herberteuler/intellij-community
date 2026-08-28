// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import java.util.TreeMap

/** The prepare version has not reached the op that creates the item. */
private const val NOT_YET_INSERTED = -1

/** The version has the characters. */
private const val INSERTED = 0

/** The version removed the characters. A prepare state counts stacked concurrent deletes. */
private const val DELETED = 1

/** No unit: the document start for a left origin, the document end for a right parent. */
private const val NO_UNIT: LV = -1

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
  private var cachedIndex = 0
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
          prepareState = INSERTED,
          effectState = INSERTED,
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
      val count = runBatch(lv) { next -> subset == null || subset.get(next) }
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
      val count = runBatch(lv) { next -> i + (next - lv) < lvs.size && lvs[i + (next - lv)] == next }
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
      applyDelete(lv, count, graph.posAt(lv))
    } else {
      applyInsert(lv, count, graph.posAt(lv))
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
  private inner class Batch(private val lvs: LvList, private val anchorIndex: Int) {
    private val isDelete = graph.isDeleteAt(lvs[anchorIndex])
    private val target: LV = targetUnitOf(isDelete, lvs[anchorIndex])
    private val item: Item = itemBy(target)

    /** Whether the entry at [index] is the same kind, the next lv, and the next unit of the item. */
    fun covers(index: Int): Boolean {
      val offset = index - anchorIndex
      val lv = lvs[anchorIndex] + offset
      if (lvs[index] != lv || graph.isDeleteAt(lv) != isDelete) {
        return false
      }
      val unit = target + offset
      return targetUnitOf(isDelete, lv) == unit && item.contains(unit)
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
   * Deletes [count] units at [pos]. Every unit of a delete run removes at the same
   * position, so the run eats the items there one after another. An item that reaches past
   * the run splits, so the deleted part stays exact.
   *
   * One lookup carries the whole run, because a consumed item keeps no prepare width. A
   * fresh lookup per item would first back up over everything the run already consumed, and
   * then walk forward over it again.
   */
  private fun applyDelete(lv: LV, count: Int, pos: Int) {
    val cursor = findByCurPos(pos)
    var done = 0
    while (done < count) {
      // Skip the items that the prepare version does not have.
      while (!items[cursor.index].inPrepare) {
        cursor.advanceOver(items[cursor.index])
      }
      val taken = minOf(count - done, items[cursor.index].length)
      if (items[cursor.index].length > taken) {
        splitItem(cursor.index, taken)
      }
      val item = items[cursor.index]
      // A concurrent delete may have removed the characters from the effect version.
      if (item.inEffect) {
        // Each removal shifts the next character to the same position.
        repeat(taken) {
          sink?.delete(cursor.effectPos)
        }
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
   * Inserts [count] units from [lv] as one span at [pos].
   *
   * Only the first unit of a run needs the Fugue integration. A later unit lands right
   * after the one before it, because its left origin is that unit and no other item can
   * name it yet: the walk visits the units in one go, with no retreat or advance between
   * them. So the span carries the first unit's origins.
   */
  private fun applyInsert(lv: LV, count: Int, pos: Int) {
    val cursor = findByCurPos(pos)
    require(cursor.index == 0 || items[cursor.index - 1].inPrepare) {
      "The item before the insert point is not inserted in the prepare version"
    }
    // The left origin is the last unit the left neighbour covers.
    val originLeft = if (cursor.index == 0) NO_UNIT else items[cursor.index - 1].lastUnit
    val newItem = Item(
      lv = lv,
      length = count,
      prepareState = INSERTED,
      effectState = INSERTED,
      originLeft = originLeft,
      rightParent = rightParentAt(cursor.index, originLeft),
    )
    integrate(newItem, cursor)
    addItem(cursor.index, newItem)
    // A silent phase skips this: every character would cost a run lookup for nothing.
    val out = sink
    if (out != null) {
      for (k in 0 until count) {
        out.insert(cursor.effectPos + k, graph.charAt(lv + k))
      }
    }
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
    if (cursor.index >= items.size || items[cursor.index].appliedInPrepare) {
      return
    }
    var scanning = false
    var scanIdx = cursor.index
    var scanEndPos = cursor.effectPos
    val leftIdx = cursor.index - 1
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
      scanEndPos += other.effectWidth
      scanIdx++
      if (!scanning) {
        cursor.moveTo(scanIdx, scanEndPos)
      }
    }
  }

  /** The scan bound that a right parent names. [NO_UNIT] means the end of the list. */
  private fun indexOfBound(rightParent: LV): Int {
    return if (rightParent == NO_UNIT) items.size else findItemIdx(rightParent)
  }

  /** Finds the insert point for a prepare-version position, walking from the cached cursor. */
  private fun findByCurPos(targetPos: Int): Cursor {
    val cursor = if (cachedPreparePos <= targetPos) {
      Cursor(cachedIndex, cachedPreparePos, cachedEffectPos)
    } else {
      Cursor(0, 0, 0)
    }
    while (cursor.preparePos < targetPos) {
      require(cursor.index < items.size) {
        "The document is not long enough for the requested position"
      }
      val item = items[cursor.index]
      if (cursor.preparePos + item.prepareWidth > targetPos) {
        // The boundary falls inside this span: split it, then retry the left piece.
        splitItem(cursor.index, targetPos - cursor.preparePos)
        continue
      }
      cursor.advanceOver(item)
    }
    // A cached start can sit after zero-width items at this position. Back up to the
    // earliest boundary: that is where a head-to-target walk stops, and the insert
    // anchoring depends on it.
    while (cursor.index > 0 && items[cursor.index - 1].prepareWidth == 0) {
      cursor.retreatOver(items[cursor.index - 1])
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

  /** Splits the span at [index] after [offset] units and files the new right piece. */
  private fun splitItem(index: Int, offset: Int) {
    addItem(index + 1, items[index].splitAfter(offset))
  }

  /** Adds [item] to the list at [index] and to the unit index. The two must stay in step. */
  private fun addItem(index: Int, item: Item) {
    items.add(index, item)
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
    cacheCursor(cursor.index, cursor.preparePos, cursor.effectPos)
  }

  private fun cacheCursor(index: Int, preparePos: Int, effectPos: Int) {
    cachedIndex = index
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

  // ------------------------------------------------------------------------------- the item state

  /**
   * A span of document characters that share one state: the replay's unit of work.
   *
   * The span covers the unit ids `[lv, lv + length)`, which always ascend. A real id is a
   * graph lv, so it is at or above 0. A placeholder span ends at -2, so every placeholder id
   * stays below 0, and [NO_UNIT] stays free for the document edges.
   *
   * The two states are the paper's `sp` and `se`. They are private: every transition is a
   * method here, so the rules that guard them cannot be bypassed from the walk.
   *
   * [originLeft] and [rightParent] order concurrent insertions, and they belong to the FIRST
   * unit of the span. Inside an insert run every later unit has the unit before it as the
   * left origin and no right parent, so [splitAfter] rebuilds them without storing them.
   */
  private class Item(
    val lv: LV,
    length: Int,
    private var prepareState: Int,
    private var effectState: Int,
    val originLeft: LV,
    val rightParent: LV,
  ) {
    var length: Int = length
      private set

    /** The unit id of the first character. A right parent always names this one. */
    val firstUnit: LV get() = lv

    /** The unit id of the last character. A left origin always names this one. */
    val lastUnit: LV get() = lv + length - 1

    /** Whether the prepare version has these characters. */
    val inPrepare: Boolean get() = prepareState == INSERTED

    /** Whether the effect version has these characters. */
    val inEffect: Boolean get() = effectState == INSERTED

    /** Whether the prepare version already reached the op that creates the item. */
    val appliedInPrepare: Boolean get() = prepareState != NOT_YET_INSERTED

    val prepareWidth: Int get() = if (inPrepare) length else 0

    val effectWidth: Int get() = if (inEffect) length else 0

    fun contains(unit: LV): Boolean = unit >= lv && unit < lv + length

    fun coversExactly(unit: LV, units: Int): Boolean = lv == unit && length == units

    fun startsBefore(unit: LV): Boolean = lv < unit

    /** Takes the span back out of the prepare version: one delete, or the insert itself. */
    fun retreat(isDelete: Boolean) {
      if (isDelete) {
        require(prepareState >= DELETED) {
          "Retreat of a delete, but the item is not deleted in the prepare version"
        }
        require(effectState == DELETED) {
          "Retreat of a delete, but the item is not deleted in the effect version"
        }
      } else {
        require(inPrepare) {
          "Retreat of an insert, but the item is not inserted in the prepare version"
        }
      }
      prepareState--
    }

    /** Puts the span back into the prepare version: one delete, or the insert itself. */
    fun advance(isDelete: Boolean) {
      if (isDelete) {
        require(prepareState >= INSERTED) {
          "Advance of a delete, but the item is not yet inserted in the prepare version"
        }
        require(effectState == DELETED) {
          "Advance of a delete, but the item is not deleted in the effect version"
        }
        prepareState++
      } else {
        require(!appliedInPrepare) {
          "Advance of an insert, but the item is already inserted in the prepare version"
        }
        prepareState = INSERTED
      }
    }

    /** Removes the span from both versions. */
    fun deleteHere() {
      require(inPrepare) {
        "Delete of an item that is not inserted in the prepare version"
      }
      prepareState = DELETED
      effectState = DELETED
    }

    /**
     * Splits after [offset] units. This item keeps the left part; the right part is
     * returned, and the caller files it in the list and the unit index.
     *
     * A placeholder piece keeps `originLeft = NO_UNIT`, because the reference gives that
     * origin to every placeholder unit. A real piece anchors on the unit before it and has
     * no right parent: inside an insert run, every unit but the first has those two origins.
     */
    fun splitAfter(offset: Int): Item {
      require(offset in 1 until length) {
        "The split offset $offset is out of the span of length $length"
      }
      val isPlaceholder = lv < 0
      val right = Item(
        lv = lv + offset,
        length = length - offset,
        prepareState = prepareState,
        effectState = effectState,
        originLeft = if (isPlaceholder) NO_UNIT else lv + offset - 1,
        rightParent = NO_UNIT,
      )
      length = offset
      return right
    }

    override fun toString(): String = "[$lv..$lastUnit] sp=$prepareState se=$effectState"
  }

  /**
   * Where the walk is in the item list: the index, plus the matching position in each of the
   * two versions. The positions are the summed widths of the items before the index, so all
   * three only ever move together.
   */
  private class Cursor(index: Int, preparePos: Int, effectPos: Int) {
    var index: Int = index
      private set
    var preparePos: Int = preparePos
      private set
    var effectPos: Int = effectPos
      private set

    fun advanceOver(item: Item) {
      index++
      preparePos += item.prepareWidth
      effectPos += item.effectWidth
    }

    fun retreatOver(item: Item) {
      index--
      preparePos -= item.prepareWidth
      effectPos -= item.effectWidth
    }

    /** Jumps to the place that the Fugue scan chose. The scan crosses no prepare width. */
    fun moveTo(index: Int, effectPos: Int) {
      this.index = index
      this.effectPos = effectPos
    }
  }
}

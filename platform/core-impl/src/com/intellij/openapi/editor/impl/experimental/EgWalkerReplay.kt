// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import java.util.TreeMap

/**
 * The Eg-walker replay: rebuilds the document at a version from the event graph.
 *
 * This is a direct port of the reference implementation
 * (`Resources/eg-walker/eg-walker-reference/src/index.ts`). It walks the events in
 * a topological order and keeps the document at two versions at once: the *prepare*
 * version, where the next event was authored, and the *effect* version, with every
 * walked event applied. [moveRange] moves the prepare version; [apply] consumes a run
 * and reports its effect to the [Sink].
 *
 * Concurrent insertions are ordered by [integrate], the reference implementation's
 * YjsMod/Fugue scan. The [ReplayState] is temporary: the caller discards it when the
 * replay ends. Nothing here is persisted.
 *
 * The walk is run-length encoded on both sides. One [Item] covers a whole run, and the
 * walk consumes as much of a run as the version list holds. An item splits only where an
 * op needs a boundary inside it: a concurrent insert, a partial delete, or a partial
 * retreat or advance. Costs: the item list is scanned linearly, but from a cached cursor,
 * so a sequential run advances in place; the worst case stays quadratic in the number of
 * items of the walked region, which run-length encoding is what shrinks.
 */
internal object EgWalkerReplay {

  internal interface Sink {
    fun insert(pos: Int, character: Char)
    fun delete(pos: Int)
  }

  /**
   * Rebuilds the text at [version] from scratch and reports every effect to [sink], in order.
   */
  fun replay(graph: EventGraphImpl, version: VersionImpl, sink: Sink) {
    val state = ReplayState(0)
    val subset = if (version == graph.versionImpl()) {
      null
    } else {
      graph.eventsOf(version)
    }
    val size = graph.size()
    var lv = 0
    while (lv < size) {
      if (subset != null && !subset.get(lv)) {
        lv++
        continue
      }
      // Take as much of the run as the walk holds. The scan stops at the run end, so it
      // never looks at a unit that this step cannot consume.
      val limit = graph.runEndOf(lv) - lv
      var count = 1
      while (count < limit && (subset == null || subset.get(lv + count))) {
        count++
      }
      lv += step(state, graph, sink, lv, count)
    }
  }

  /**
   * Merges everything the graph holds beyond [branchVersion] into a document that is
   * already at [branchVersion], and reports only the new effects to [sink]. This is
   * the paper's partial replay: only the region above the common ancestor is walked.
   *
   * One placeholder item stands in for the whole document at the common ancestor, so
   * the units at or below it are never replayed, and it splits lazily where the
   * region's ops land. The units of the branch's own history above the ancestor are
   * replayed silently, to rebuild the concurrency context; the units only in the
   * merged history apply with output. A port of `mergeChangesIntoBranch` from the
   * reference implementation, with the paper's single-placeholder representation.
   */
  fun mergeInto(graph: EventGraphImpl, branchVersion: VersionImpl, sink: Sink) {
    val conflict = graph.findConflicting(branchVersion.lvs, graph.versionImpl().lvs)
    // One span of placeholder units, at least as long as the document at the common
    // ancestor. The trailing extras sit after every reachable position, inert.
    val placeholderCount = if (branchVersion.isRoot()) {
      0
    } else {
      branchVersion.lvs[branchVersion.lvs.size - 1] + 1
    }
    val state = ReplayState(placeholderCount)
    state.curVersion = conflict.commonAncestor
    walk(state, graph, null, conflict.conflictLvs)
    walk(state, graph, sink, conflict.newLvs)
  }

  /** Walks an ascending lv list, one whole run per step where the list allows it. */
  private fun walk(state: ReplayState, graph: EventGraphImpl, sink: Sink?, lvs: IntArray) {
    var i = 0
    while (i < lvs.size) {
      val lv = lvs[i]
      val limit = graph.runEndOf(lv) - lv
      var count = 1
      while (count < limit && i + count < lvs.size && lvs[i + count] == lv + count) {
        count++
      }
      i += step(state, graph, sink, lv, count)
    }
  }

  /**
   * Consumes [count] units of one run from [lv]: moves the prepare version to the first
   * unit's parents, then applies the whole span. Every unit of a run after the first has
   * the one parent `lv - 1`, so the later units need no version move.
   */
  private fun step(state: ReplayState, graph: EventGraphImpl, sink: Sink?, lv: LV, count: Int): Int {
    val parents = graph.parentsOf(lv)
    // The sequential case: the prepare version already is the parents, so the diff is
    // empty. This skips a queue-and-map diff walk per run.
    if (!state.curVersion.contentEquals(parents)) {
      val diff = graph.diff(state.curVersion, parents)
      if (diff.aOnly.isNotEmpty() || diff.bOnly.isNotEmpty()) {
        // The prepare widths change at arbitrary places: the cached cursor is stale.
        state.resetCursor()
      }
      // Retreat in reverse order, so an item is undeleted before it is uninserted.
      moveRange(state, graph, diff.aOnly, retreating = true)
      moveRange(state, graph, diff.bOnly, retreating = false)
    }
    apply(state, graph, sink, lv, count)
    state.setCurVersion(lv + count - 1)
    return count
  }

  // ------------------------------------------------------------------------ the internal state

  private const val NOT_YET_INSERTED = -1
  private const val INSERTED = 0
  private const val DELETED = 1 // the prepare state counts stacked concurrent deletes: 1, 2, ...

  /**
   * A run of document characters, inserted or not yet inserted or deleted.
   * [prepareState] is the paper's `sp`; [effectState] is the paper's `se`.
   * [originLeft] and [rightParent] order concurrent insertions; -1 means the
   * document start and the document end.
   *
   * A real item covers one character: [length] is 1. A placeholder item covers
   * [length] units starting at the unit id [lv]; it stays Inserted in both states
   * while its length is above 1, because an op splits it first.
   */
  private class Item(
    val lv: LV,
    var length: Int,
    var prepareState: Int,
    var effectState: Int,
    val originLeft: LV,
    val rightParent: LV,
  )

  private fun prepareWidth(item: Item): Int {
    return if (item.prepareState == INSERTED) item.length else 0
  }

  private fun effectWidth(item: Item): Int {
    return if (item.effectState == INSERTED) item.length else 0
  }

  /**
   * The unit ids of a span ascend: an item covers `[lv, lv + length)`. A real id is a
   * graph lv, so it is at or above 0. A placeholder span ends at -2, so every placeholder
   * id stays below 0, and -1 stays free for the start and end sentinel.
   */
  private fun isPlaceholder(item: Item): Boolean {
    return item.lv < 0
  }

  /** The unit id of the first character the item covers. */
  private fun firstUnitLv(item: Item): LV {
    return item.lv
  }

  /** The unit id of the last character the item covers. */
  private fun lastUnitLv(item: Item): LV {
    return item.lv + item.length - 1
  }

  /** Whether the item covers the unit [lv]. */
  private fun containsUnit(item: Item, lv: LV): Boolean {
    return lv >= item.lv && lv < item.lv + item.length
  }

  /** The first unit id of a placeholder span of [count] units. The span ends at -2. */
  private fun placeholderFirstLv(count: Int): LV {
    return -1 - count
  }

  /**
   * The reference implementation's `EditContext`. The maps hold only the units the walk
   * touches, so the state costs O(region), not O(graph).
   */
  private class ReplayState(placeholderCount: Int) {
    val items = ArrayList<Item>()

    /** For a delete unit, the unit id it deleted. */
    private val delTargets = HashMap<LV, LV>()

    /**
     * Every item by its first unit id. A span covers a range, so the lookup takes the
     * floor entry and then checks that the item really covers the unit. Ranges never
     * overlap, so the floor entry is the only candidate.
     */
    private val itemsByLv = TreeMap<LV, Item>()

    var curVersion = IntArray(0)

    /**
     * The last boundary [findByCurPos] produced or an apply advanced past: the item
     * index with its prepare and effect positions. Sequential units land at or after
     * it, so the walk resumes there instead of rescanning from the head. A retreat or
     * an advance changes prepare widths anywhere in the list, which resets the cache.
     */
    var cachedIdx = 0
    var cachedCurPos = 0
    var cachedEndPos = 0

    init {
      if (placeholderCount > 0) {
        // One item for the whole ancestor document; the ops split it lazily.
        val item = Item(
          lv = placeholderFirstLv(placeholderCount),
          length = placeholderCount,
          prepareState = INSERTED,
          effectState = INSERTED,
          originLeft = -1,
          rightParent = -1,
        )
        items.add(item)
        register(item)
      }
    }

    fun register(item: Item) {
      itemsByLv[item.lv] = item
    }

    fun itemBy(lv: LV): Item {
      val item = itemsByLv.floorEntry(lv)?.value
      require(item != null && containsUnit(item, lv)) {
        "No item covers the unit $lv"
      }
      return item
    }

    fun setDelTarget(lv: LV, target: LV) {
      delTargets[lv] = target
    }

    fun delTargetOf(lv: LV): LV {
      return delTargets[lv]!!
    }

    fun cacheCursor(idx: Int, curPos: Int, endPos: Int) {
      cachedIdx = idx
      cachedCurPos = curPos
      cachedEndPos = endPos
    }

    fun resetCursor() {
      cacheCursor(0, 0, 0)
    }

    fun setCurVersion(lv: LV) {
      // Reuse the single-head array: nothing retains the old version.
      if (curVersion.size == 1) {
        curVersion[0] = lv
      } else {
        curVersion = intArrayOf(lv)
      }
    }
  }

  /** A position in [ReplayState.items] plus the matching position in the effect version. */
  private class Cursor(var idx: Int, var endPos: Int)

  /**
   * Splits the span at [index] after [offset] units. The left piece keeps the item and
   * its origins; the right piece follows it, with the matching first unit id. Both pieces
   * keep the state of the whole span.
   *
   * A placeholder piece keeps `originLeft = -1`, because the reference implementation
   * gives that origin to every placeholder unit. A real piece anchors on the unit before
   * it and has no right parent: inside an insert run, every unit but the first has
   * exactly those two origins.
   */
  private fun splitItem(state: ReplayState, index: Int, offset: Int) {
    val item = state.items[index]
    checkSplit(item, offset)
    val right = Item(
      lv = item.lv + offset,
      length = item.length - offset,
      prepareState = item.prepareState,
      effectState = item.effectState,
      originLeft = if (isPlaceholder(item)) -1 else item.lv + offset - 1,
      rightParent = -1,
    )
    item.length = offset
    state.items.add(index + 1, right)
    // The left piece keeps its first unit, so its entry stays; the right piece is new.
    state.register(right)
  }

  // ----------------------------------------------------------------- retreat / advance / apply

  /**
   * Moves the prepare version over [lvs], in batches that share one item.
   *
   * A retreat walks backwards, so an item is undeleted before it is uninserted. A batch
   * covers the units that land on one contiguous range of one item, so a whole run
   * usually moves in one step and the item keeps its span. Only a batch that covers part
   * of an item splits it.
   */
  private fun moveRange(state: ReplayState, graph: EventGraphImpl, lvs: IntArray, retreating: Boolean) {
    if (retreating) {
      var end = lvs.size
      while (end > 0) {
        val start = batchStart(state, graph, lvs, end)
        move(state, graph, lvs[start], end - start, true)
        end = start
      }
    }
    else {
      var start = 0
      while (start < lvs.size) {
        val count = batchLength(state, graph, lvs, start, lvs.size - start)
        move(state, graph, lvs[start], count, false)
        start += count
      }
    }
  }

  /** The first index of the batch that ends just before [end]. Walks back once, not once per step. */
  private fun batchStart(state: ReplayState, graph: EventGraphImpl, lvs: IntArray, end: Int): Int {
    val last = end - 1
    val isDelete = graph.isDeleteAt(lvs[last])
    val lastTarget = targetLvOf(state, isDelete, lvs[last])
    val item = state.itemBy(lastTarget)
    var start = last
    while (start > 0) {
      val previous = start - 1
      if (lvs[previous] != lvs[start] - 1 || graph.isDeleteAt(lvs[previous]) != isDelete) {
        break
      }
      val target = lastTarget - (last - previous)
      if (targetLvOf(state, isDelete, lvs[previous]) != target || !containsUnit(item, target)) {
        break
      }
      start--
    }
    return start
  }

  /**
   * The number of entries from [from], at most [limit], whose targets form one contiguous
   * range inside one item.
   */
  private fun batchLength(state: ReplayState, graph: EventGraphImpl, lvs: IntArray, from: Int, limit: Int): Int {
    val first = lvs[from]
    val isDelete = graph.isDeleteAt(first)
    val firstTarget = targetLvOf(state, isDelete, first)
    val item = state.itemBy(firstTarget)
    var count = 1
    while (count < limit) {
      val next = lvs[from + count]
      if (next != first + count || graph.isDeleteAt(next) != isDelete) {
        break
      }
      val target = firstTarget + count
      if (targetLvOf(state, isDelete, next) != target || !containsUnit(item, target)) {
        break
      }
      count++
    }
    return count
  }

  /** Retreats or advances the [count] units that [lv] starts, all inside one item. */
  private fun move(state: ReplayState, graph: EventGraphImpl, lv: LV, count: Int, retreating: Boolean) {
    val isDelete = graph.isDeleteAt(lv)
    val item = isolate(state, targetLvOf(state, isDelete, lv), count)
    if (retreating) {
      checkRetreat(isDelete, item)
      item.prepareState--
    }
    else {
      checkAdvance(isDelete, item)
      if (isDelete) {
        item.prepareState++
      }
      else {
        item.prepareState = INSERTED
      }
    }
  }

  private fun targetLvOf(state: ReplayState, isDelete: Boolean, lv: LV): LV {
    return if (isDelete) state.delTargetOf(lv) else lv
  }

  /**
   * Returns the item that covers exactly the [count] units from [target], and splits the
   * containing item when it is wider. The whole-item case needs no index, so it never
   * scans the item list.
   */
  private fun isolate(state: ReplayState, target: LV, count: Int): Item {
    var item = state.itemBy(target)
    if (item.lv == target && item.length == count) {
      return item
    }
    var index = findItemIdx(state, target)
    if (item.lv < target) {
      splitItem(state, index, target - item.lv)
      index++
      item = state.items[index]
    }
    if (item.length > count) {
      splitItem(state, index, count)
      item = state.items[index]
    }
    return item
  }

  private fun apply(state: ReplayState, graph: EventGraphImpl, sink: Sink?, lv: LV, count: Int) {
    if (graph.isDeleteAt(lv)) {
      applyDelete(state, sink, lv, count, graph.posAt(lv))
    } else {
      applyInsert(state, graph, sink, lv, count, graph.posAt(lv))
    }
  }

  /**
   * Deletes [count] units at [pos]. Every unit of a delete run removes at the same
   * position, so the run eats the items there one after another. An item that reaches
   * past the run splits, so the deleted part stays exact.
   */
  private fun applyDelete(state: ReplayState, sink: Sink?, lv: LV, count: Int, pos: Int) {
    // One lookup carries the whole run. Every unit removes at the same position, and a
    // consumed item keeps no prepare width, so the next target is simply the next item
    // that the prepare version still has. A fresh lookup per item would first back up
    // over everything this run already consumed, and then walk forward over it again.
    val cursor = findByCurPos(state, pos)
    var done = 0
    while (done < count) {
      // Skip the items that do not exist in the prepare version.
      while (state.items[cursor.idx].prepareState != INSERTED) {
        cursor.endPos += effectWidth(state.items[cursor.idx])
        cursor.idx++
      }
      val taken = minOf(count - done, state.items[cursor.idx].length)
      if (state.items[cursor.idx].length > taken) {
        splitItem(state, cursor.idx, taken)
      }
      val item = state.items[cursor.idx]
      checkDeleteTarget(item)
      // A concurrent delete may have removed the characters from the effect version.
      if (item.effectState == INSERTED) {
        // Each removal shifts the next character to the same position.
        repeat(taken) {
          sink?.delete(cursor.endPos)
        }
      }
      item.prepareState = DELETED
      item.effectState = DELETED
      for (k in 0 until taken) {
        state.setDelTarget(lv + done + k, item.lv + k)
      }
      // The item now has no width in either version, so neither position moves.
      cursor.idx++
      done += taken
    }
    state.cacheCursor(cursor.idx, pos, cursor.endPos)
  }

  /**
   * Inserts [count] units from [lv] as one span at [pos].
   *
   * Only the first unit of a run needs the Fugue integration. A later unit lands right
   * after the one before it, because its left origin is that unit and no other item can
   * name it yet: the walk visits the units in one go, with no retreat or advance between
   * them. So the span carries the first unit's origins, and a split gives the right piece
   * the origins that a later unit would have had.
   */
  private fun applyInsert(state: ReplayState, graph: EventGraphImpl, sink: Sink?, lv: LV, count: Int, pos: Int) {
    val cursor = findByCurPos(state, pos)
    checkInsertPoint(state, cursor)
    // The left origin is the last unit the left neighbor covers.
    val originLeft = if (cursor.idx == 0) -1 else lastUnitLv(state.items[cursor.idx - 1])
    // The right parent is the next item that exists in the prepare version.
    // This is the reference implementation's Fugue variant; FugueMax would
    // take that item unconditionally.
    var rightParent = -1
    for (i in cursor.idx until state.items.size) {
      val next = state.items[i]
      if (next.prepareState != NOT_YET_INSERTED) {
        // A right parent always names the FIRST unit of a span, and a left origin always
        // names the LAST unit of one. A split keeps both, so the two stay comparable.
        rightParent = if (next.originLeft == originLeft) firstUnitLv(next) else -1
        break
      }
    }
    val newItem = Item(
      lv = lv,
      length = count,
      prepareState = INSERTED,
      effectState = INSERTED,
      originLeft = originLeft,
      rightParent = rightParent,
    )
    state.register(newItem)
    integrate(state, graph, newItem, cursor)
    state.items.add(cursor.idx, newItem)
    if (sink != null) {
      for (k in 0 until count) {
        sink.insert(cursor.endPos + k, graph.charAt(lv + k))
      }
    }
    // The next run lands right after this span.
    state.cacheCursor(cursor.idx + 1, pos + count, cursor.endPos + count)
  }

  // --------------------------------------------------------------------------------- the scan

  /**
   * Finds the place for a concurrent insertion among the not-yet-inserted items at the
   * cursor. A direct port of `integrate` from the reference implementation, which ports
   * YjsMod / Fugue. The cursor is moved to the found place.
   */
  private fun integrate(state: ReplayState, graph: EventGraphImpl, newItem: Item, cursor: Cursor) {
    // Without concurrency there is nothing to scan.
    if (cursor.idx >= state.items.size || state.items[cursor.idx].prepareState != NOT_YET_INSERTED) {
      return
    }
    var scanning = false
    var scanIdx = cursor.idx
    var scanEndPos = cursor.endPos
    val leftIdx = cursor.idx - 1
    val rightIdx = if (newItem.rightParent == -1) {
      state.items.size
    } else {
      findItemIdx(state, newItem.rightParent)
    }
    while (scanIdx < state.items.size) {
      val other = state.items[scanIdx]
      if (other.prepareState != NOT_YET_INSERTED) {
        break
      }
      checkNotRightParent(other, newItem)
      val otherLeftIdx = if (other.originLeft == -1) {
        -1
      } else {
        findItemIdx(state, other.originLeft)
      }
      if (otherLeftIdx < leftIdx) {
        break
      }
      if (otherLeftIdx == leftIdx) {
        val otherRightIdx = if (other.rightParent == -1) {
          state.items.size
        } else {
          findItemIdx(state, other.rightParent)
        }
        if (otherRightIdx == rightIdx && graph.compareEvents(newItem.lv, other.lv) < 0) {
          break
        }
        scanning = otherRightIdx < rightIdx
      }
      scanEndPos += effectWidth(other)
      scanIdx++
      if (!scanning) {
        cursor.idx = scanIdx
        cursor.endPos = scanEndPos
      }
    }
  }

  /** Finds the insert point for a prepare-version position, walking from the cached cursor. */
  private fun findByCurPos(state: ReplayState, targetPos: Int): Cursor {
    var i: Int
    var curPos: Int
    var endPos: Int
    if (state.cachedCurPos <= targetPos) {
      i = state.cachedIdx
      curPos = state.cachedCurPos
      endPos = state.cachedEndPos
    } else {
      i = 0
      curPos = 0
      endPos = 0
    }
    while (curPos < targetPos) {
      checkInRange(i, state)
      val item = state.items[i]
      val width = prepareWidth(item)
      if (curPos + width > targetPos) {
        // The boundary falls inside this span: split it, then retry the left piece.
        splitItem(state, i, targetPos - curPos)
        continue
      }
      curPos += width
      endPos += effectWidth(item)
      i++
    }
    // A cached start can sit after zero-width items at this position. Back up to the
    // earliest boundary: that is where a head-to-target walk stops, and the insert
    // anchoring depends on it.
    while (i > 0 && prepareWidth(state.items[i - 1]) == 0) {
      i--
      endPos -= effectWidth(state.items[i])
    }
    return Cursor(i, endPos)
  }

  /** Finds the item that covers [needleLv]: an exact item, or the containing span. */
  private fun findItemIdx(state: ReplayState, needleLv: LV): Int {
    for (i in state.items.indices) {
      if (containsUnit(state.items[i], needleLv)) {
        return i
      }
    }
    throw IllegalStateException("The item $needleLv is not in the item list")
  }

  // ------------------------------------------------------------------------------------ checks

  private fun checkRetreat(isDelete: Boolean, item: Item) {
    if (isDelete) {
      require(item.prepareState >= DELETED) {
        "Retreat of a delete, but the item is not deleted in the prepare version"
      }
      require(item.effectState == DELETED) {
        "Retreat of a delete, but the item is not deleted in the effect version"
      }
    } else {
      require(item.prepareState == INSERTED) {
        "Retreat of an insert, but the item is not inserted in the prepare version"
      }
    }
  }

  private fun checkAdvance(isDelete: Boolean, item: Item) {
    if (isDelete) {
      require(item.prepareState >= INSERTED) {
        "Advance of a delete, but the item is not yet inserted in the prepare version"
      }
      require(item.effectState == DELETED) {
        "Advance of a delete, but the item is not deleted in the effect version"
      }
    } else {
      require(item.prepareState == NOT_YET_INSERTED) {
        "Advance of an insert, but the item is already inserted in the prepare version"
      }
    }
  }

  private fun checkDeleteTarget(item: Item) {
    require(item.prepareState == INSERTED) {
      "Delete of an item that is not inserted in the prepare version"
    }
  }

  private fun checkInsertPoint(state: ReplayState, cursor: Cursor) {
    require(cursor.idx == 0 || state.items[cursor.idx - 1].prepareState == INSERTED) {
      "The item before the insert point is not inserted in the prepare version"
    }
  }

  private fun checkNotRightParent(other: Item, newItem: Item) {
    require(!containsUnit(other, newItem.rightParent)) {
      "The scan reached the right parent of the new item"
    }
  }

  private fun checkSplit(item: Item, offset: Int) {
    require(offset in 1 until item.length) {
      "The split offset $offset is out of the span of length ${item.length}"
    }
  }

  private fun checkInRange(i: Int, state: ReplayState) {
    require(i < state.items.size) {
      "The document is not long enough for the requested position"
    }
  }
}

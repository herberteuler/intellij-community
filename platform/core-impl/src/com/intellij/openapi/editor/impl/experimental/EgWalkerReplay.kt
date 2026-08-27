// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/**
 * The Eg-walker replay: rebuilds the document at a version from the event graph.
 *
 * This is a direct port of the reference implementation
 * (`Resources/eg-walker/eg-walker-reference/src/index.ts`). It walks the events in
 * a topological order and keeps the document at two versions at once: the *prepare*
 * version, where the next event was authored, and the *effect* version, with every
 * walked event applied. [retreat] and [advance] move the prepare version; [apply]
 * consumes one unit and reports its effect to the [Sink].
 *
 * Concurrent insertions are ordered by [integrate], the reference implementation's
 * YjsMod/Fugue scan. The [ReplayState] is temporary: the caller discards it when the
 * replay ends. Nothing here is persisted.
 *
 * The event storage is run-length encoded, but this walk is still per unit: one
 * [Item] per character, one lv at a time. Inside a run, the parent of a unit is the
 * unit before it, so the per-unit diff there is trivially empty. Run-length items
 * are a follow-up optimization. Costs: the item list is scanned linearly per unit,
 * but from a cached cursor, so a sequential run advances in place; the worst case
 * stays quadratic in the walked region.
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
    for (lv in 0 until graph.size()) {
      if (subset != null && !subset.get(lv)) {
        continue
      }
      step(state, graph, sink, lv)
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
    for (lv in conflict.conflictLvs) {
      step(state, graph, null, lv)
    }
    for (lv in conflict.newLvs) {
      step(state, graph, sink, lv)
    }
  }

  /** Consumes one unit: moves the prepare version to its parents, then applies it. */
  private fun step(state: ReplayState, graph: EventGraphImpl, sink: Sink?, lv: LV) {
    val parents = graph.parentsOf(lv)
    // The sequential case: the prepare version already is the parents, so the diff is
    // empty. This skips a queue-and-map diff walk per unit inside every run.
    if (!state.curVersion.contentEquals(parents)) {
      val diff = graph.diff(state.curVersion, parents)
      if (diff.aOnly.isNotEmpty() || diff.bOnly.isNotEmpty()) {
        // The prepare widths change at arbitrary places: the cached cursor is stale.
        state.resetCursor()
      }
      // Retreat in reverse order, so an item is undeleted before it is uninserted.
      for (i in diff.aOnly.indices.reversed()) {
        retreat(state, graph, diff.aOnly[i])
      }
      for (advanced in diff.bOnly) {
        advance(state, graph, advanced)
      }
    }
    apply(state, graph, sink, lv)
    state.setCurVersion(lv)
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

  private fun isPlaceholder(item: Item): Boolean {
    return item.lv <= PLACEHOLDER_BASE
  }

  /** The unit id of the last character the item covers. */
  private fun lastUnitLv(item: Item): LV {
    return if (isPlaceholder(item)) item.lv - (item.length - 1) else item.lv
  }

  /**
   * A placeholder unit id is `PLACEHOLDER_BASE - i` for the document position `i` at
   * the common ancestor. -1 stays free for the start and end sentinel.
   */
  private const val PLACEHOLDER_BASE = -2

  /**
   * The reference implementation's `EditContext`. The maps hold only the units the walk
   * touches, so the state costs O(region), not O(graph).
   */
  private class ReplayState(placeholderCount: Int) {
    val items = ArrayList<Item>()

    /** For a delete unit, the lv of the item it deleted. */
    private val delTargets = HashMap<LV, LV>()

    /** The items by the unit id they cover: real inserts, and consumed placeholder pieces. */
    private val itemsByLv = HashMap<LV, Item>()

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
          lv = PLACEHOLDER_BASE,
          length = placeholderCount,
          prepareState = INSERTED,
          effectState = INSERTED,
          originLeft = -1,
          rightParent = -1,
        )
        items.add(item)
      }
    }

    fun register(lv: LV, item: Item) {
      itemsByLv[lv] = item
    }

    fun itemBy(lv: LV): Item {
      return itemsByLv[lv]!!
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
   * Splits the placeholder span at [index] after [offset] units. The left piece keeps
   * the item; the right piece is inserted after it, with the matching first unit id.
   */
  private fun splitPlaceholder(state: ReplayState, index: Int, offset: Int) {
    val item = state.items[index]
    checkSplit(item, offset)
    val right = Item(
      lv = item.lv - offset,
      length = item.length - offset,
      prepareState = INSERTED,
      effectState = INSERTED,
      originLeft = -1,
      rightParent = -1
    )
    item.length = offset
    state.items.add(index + 1, right)
  }

  // ----------------------------------------------------------------- retreat / advance / apply

  private fun retreat(state: ReplayState, graph: EventGraphImpl, lv: LV) {
    val isDelete = graph.isDeleteAt(lv)
    val item = targetItem(state, isDelete, lv)
    checkRetreat(isDelete, item)
    item.prepareState--
  }

  private fun advance(state: ReplayState, graph: EventGraphImpl, lv: LV) {
    val isDelete = graph.isDeleteAt(lv)
    val item = targetItem(state, isDelete, lv)
    checkAdvance(isDelete, item)
    if (isDelete) {
      item.prepareState++
    } else {
      item.prepareState = INSERTED
    }
  }

  private fun targetItem(state: ReplayState, isDelete: Boolean, lv: LV): Item {
    val targetLv = if (isDelete) state.delTargetOf(lv) else lv
    return state.itemBy(targetLv)
  }

  private fun apply(state: ReplayState, graph: EventGraphImpl, sink: Sink?, lv: LV) {
    if (graph.isDeleteAt(lv)) {
      applyDelete(state, sink, lv, graph.posAt(lv))
    } else {
      applyInsert(state, graph, sink, lv, graph.posAt(lv), graph.charAt(lv))
    }
  }

  private fun applyDelete(state: ReplayState, sink: Sink?, lv: LV, pos: Int) {
    val cursor = findByCurPos(state, pos)
    // Skip the items that do not exist in the prepare version.
    while (state.items[cursor.idx].prepareState != INSERTED) {
      val item = state.items[cursor.idx]
      cursor.endPos += effectWidth(item)
      cursor.idx++
    }
    if (state.items[cursor.idx].length > 1) {
      // A delete consumes one unit: split it off the placeholder span.
      splitPlaceholder(state, cursor.idx, 1)
    }
    val item = state.items[cursor.idx]
    checkDeleteTarget(item)
    // A concurrent delete may have removed the character from the effect version already.
    if (item.effectState == INSERTED) {
      sink?.delete(cursor.endPos)
    }
    item.prepareState = DELETED
    item.effectState = DELETED
    state.setDelTarget(lv, item.lv)
    state.register(item.lv, item)
    // The next unit of a delete run targets the same position, right after this item.
    state.cacheCursor(cursor.idx + 1, pos, cursor.endPos)
  }

  private fun applyInsert(state: ReplayState, graph: EventGraphImpl, sink: Sink?, lv: LV, pos: Int, character: Char) {
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
        rightParent = if (next.originLeft == originLeft) next.lv else -1
        break
      }
    }
    val newItem = Item(
      lv = lv,
      length = 1,
      prepareState = INSERTED,
      effectState = INSERTED,
      originLeft = originLeft,
      rightParent = rightParent,
    )
    state.register(lv, newItem)
    integrate(state, graph, newItem, cursor)
    state.items.add(cursor.idx, newItem)
    sink?.insert(cursor.endPos, character)
    // The next unit of an insert run lands right after this item.
    state.cacheCursor(cursor.idx + 1, pos + 1, cursor.endPos + 1)
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
        // The boundary falls inside this placeholder span: split it, retry the piece.
        splitPlaceholder(state, i, targetPos - curPos)
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
      val item = state.items[i]
      // Unit ids descend within a span, so the covered range is (lv - length, lv].
      if (needleLv <= item.lv && needleLv > item.lv - item.length) {
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
    require(other.lv != newItem.rightParent) {
      "The scan reached the right parent of the new item"
    }
  }

  private fun checkSplit(item: Item, offset: Int) {
    require(item.lv <= PLACEHOLDER_BASE) {
      "Split of a real item ${item.lv}"
    }
    require(item.prepareState == INSERTED && item.effectState == INSERTED) {
      "Split of a consumed placeholder ${item.lv}"
    }
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

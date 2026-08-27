// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Event

/**
 * The Eg-walker replay: rebuilds the document at a version from the event graph.
 *
 * This is a direct port of the reference implementation
 * (`Resources/eg-walker/eg-walker-reference/src/index.ts`). It walks the events in
 * a topological order and keeps the document at two versions at once: the *prepare*
 * version, where the next event was authored, and the *effect* version, with every
 * walked event applied. [retreat] and [advance] move the prepare version; [apply]
 * consumes one event and reports its effect to the [Sink].
 *
 * Concurrent insertions are ordered by [integrate], the reference implementation's
 * YjsMod/Fugue scan. The [ReplayState] is temporary: the caller discards it when the
 * replay ends. Nothing here is persisted.
 *
 * Costs, as in the reference: the item list is scanned linearly per event, so a replay
 * of n events is O(n^2) in the worst case. Good enough for a prototype.
 */
internal object EgWalkerReplay {

  internal interface Sink {
    fun insert(pos: Int, character: Char)
    fun delete(pos: Int)
  }

  /**
   * Rebuilds the text at [version] and reports every effect to [sink], in order.
   */
  fun replay(graph: EventGraphImpl, version: VersionImpl, sink: Sink) {
    val state = ReplayState(graph.size())
    val subset = if (version == graph.versionImpl()) null else graph.eventsOf(version)
    for (lv in 0 until graph.size()) {
      if (subset != null && !subset.get(lv)) {
        continue
      }
      // Move the prepare version from the last consumed event to this event's parents.
      val parents = graph.parentsOf(lv)
      val diff = graph.diff(state.curVersion, parents)
      // Retreat in reverse order, so an item is undeleted before it is uninserted.
      for (i in diff.aOnly.indices.reversed()) {
        retreat(state, graph, diff.aOnly[i])
      }
      for (advanced in diff.bOnly) {
        advance(state, graph, advanced)
      }
      apply(state, graph, sink, lv)
      state.curVersion = intArrayOf(lv)
    }
  }

  // ------------------------------------------------------------------------ the internal state

  private const val NOT_YET_INSERTED = -1
  private const val INSERTED = 0
  private const val DELETED = 1 // the prepare state counts stacked concurrent deletes: 1, 2, ...

  /**
   * One character of the document, inserted or not yet inserted or deleted.
   * [prepareState] is the paper's `sp`; [effectState] is the paper's `se`.
   * [originLeft] and [rightParent] order concurrent insertions; -1 means the
   * document start and the document end.
   */
  private class Item(
    val lv: Int,
    var prepareState: Int,
    var effectState: Int,
    val originLeft: Int,
    val rightParent: Int,
  )

  /** The reference implementation's `EditContext`. */
  private class ReplayState(graphSize: Int) {
    val items = ArrayList<Item>()

    /** For a delete event, the lv of the item it deleted. */
    val delTargets = IntArray(graphSize) { -1 }

    val itemsByLv = arrayOfNulls<Item>(graphSize)

    var curVersion = IntArray(0)
  }

  private fun itemWidth(state: Int): Int {
    return if (state == INSERTED) 1 else 0
  }

  /** A position in [ReplayState.items] plus the matching position in the effect version. */
  private class Cursor(var idx: Int, var endPos: Int)

  // ----------------------------------------------------------------- retreat / advance / apply

  private fun retreat(state: ReplayState, graph: EventGraphImpl, lv: Int) {
    val event = graph.eventOf(lv)
    val item = targetItem(state, event, lv)
    checkRetreat(event, item)
    item.prepareState--
  }

  private fun advance(state: ReplayState, graph: EventGraphImpl, lv: Int) {
    val event = graph.eventOf(lv)
    val item = targetItem(state, event, lv)
    checkAdvance(event, item)
    if (event is Event.Delete) {
      item.prepareState++
    }
    else {
      item.prepareState = INSERTED
    }
  }

  private fun targetItem(state: ReplayState, event: Event, lv: Int): Item {
    val targetLv = if (event is Event.Delete) state.delTargets[lv] else lv
    return state.itemsByLv[targetLv]!!
  }

  private fun apply(state: ReplayState, graph: EventGraphImpl, sink: Sink, lv: Int) {
    when (val event = graph.eventOf(lv)) {
      is Event.Delete -> applyDelete(state, sink, lv, event)
      is Event.Insert -> applyInsert(state, graph, sink, lv, event)
    }
  }

  private fun applyDelete(state: ReplayState, sink: Sink, lv: Int, event: Event.Delete) {
    val cursor = findByCurPos(state, event.pos())
    // Skip the items that do not exist in the prepare version.
    while (state.items[cursor.idx].prepareState != INSERTED) {
      val item = state.items[cursor.idx]
      cursor.endPos += itemWidth(item.effectState)
      cursor.idx++
    }
    val item = state.items[cursor.idx]
    checkDeleteTarget(item)
    // A concurrent delete may have removed the character from the effect version already.
    if (item.effectState == INSERTED) {
      sink.delete(cursor.endPos)
    }
    item.prepareState = DELETED
    item.effectState = DELETED
    state.delTargets[lv] = item.lv
  }

  private fun applyInsert(state: ReplayState, graph: EventGraphImpl, sink: Sink, lv: Int, event: Event.Insert) {
    val cursor = findByCurPos(state, event.pos())
    checkInsertPoint(state, cursor)
    val originLeft = if (cursor.idx == 0) -1 else state.items[cursor.idx - 1].lv
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
    val newItem = Item(lv, INSERTED, INSERTED, originLeft, rightParent)
    state.itemsByLv[lv] = newItem
    integrate(state, graph, newItem, cursor)
    state.items.add(cursor.idx, newItem)
    sink.insert(cursor.endPos, event.character())
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
    val rightIdx = if (newItem.rightParent == -1) state.items.size else findItemIdx(state, newItem.rightParent)
    while (scanIdx < state.items.size) {
      val other = state.items[scanIdx]
      if (other.prepareState != NOT_YET_INSERTED) {
        break
      }
      checkNotRightParent(other, newItem)
      val otherLeftIdx = if (other.originLeft == -1) -1 else findItemIdx(state, other.originLeft)
      if (otherLeftIdx < leftIdx) {
        break
      }
      if (otherLeftIdx == leftIdx) {
        val otherRightIdx = if (other.rightParent == -1) state.items.size else findItemIdx(state, other.rightParent)
        if (otherRightIdx == rightIdx && graph.compareEvents(newItem.lv, other.lv) < 0) {
          break
        }
        scanning = otherRightIdx < rightIdx
      }
      scanEndPos += itemWidth(other.effectState)
      scanIdx++
      if (!scanning) {
        cursor.idx = scanIdx
        cursor.endPos = scanEndPos
      }
    }
  }

  /** Finds the insert point for a prepare-version position, front to back. */
  private fun findByCurPos(state: ReplayState, targetPos: Int): Cursor {
    var curPos = 0
    var endPos = 0
    var i = 0
    while (curPos < targetPos) {
      checkInRange(i, state)
      val item = state.items[i]
      curPos += itemWidth(item.prepareState)
      endPos += itemWidth(item.effectState)
      i++
    }
    return Cursor(i, endPos)
  }

  private fun findItemIdx(state: ReplayState, needleLv: Int): Int {
    for (i in state.items.indices) {
      if (state.items[i].lv == needleLv) {
        return i
      }
    }
    throw IllegalStateException("The item $needleLv is not in the item list")
  }

  // ------------------------------------------------------------------------------------ checks

  private fun checkRetreat(event: Event, item: Item) {
    if (event is Event.Delete) {
      require(item.prepareState >= DELETED) {
        "Retreat of a delete, but the item is not deleted in the prepare version"
      }
      require(item.effectState == DELETED) {
        "Retreat of a delete, but the item is not deleted in the effect version"
      }
    }
    else {
      require(item.prepareState == INSERTED) {
        "Retreat of an insert, but the item is not inserted in the prepare version"
      }
    }
  }

  private fun checkAdvance(event: Event, item: Item) {
    if (event is Event.Delete) {
      require(item.prepareState >= INSERTED) {
        "Advance of a delete, but the item is not yet inserted in the prepare version"
      }
      require(item.effectState == DELETED) {
        "Advance of a delete, but the item is not deleted in the effect version"
      }
    }
    else {
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

  private fun checkInRange(i: Int, state: ReplayState) {
    require(i < state.items.size) {
      "The document is not long enough for the requested position"
    }
  }
}

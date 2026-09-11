// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/**
 * A span of document characters that share one state: the replay's unit of work.
 * [ReplayWalker] holds them in document order.
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
internal class Item(
  val lv: LV,
  length: Int,
  val originLeft: LV,
  val rightParent: LV,
  private var prepareState: Int = INSERTED,
  private var effectState: Int = INSERTED,
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

  /** Whether the span stands in for the document at the common ancestor. */
  private val isPlaceholder: Boolean get() = lv < 0

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
      prepareState--
    } else {
      require(inPrepare) {
        "Retreat of an insert, but the item is not inserted in the prepare version"
      }
      prepareState = NOT_YET_INSERTED
    }
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
    val right = Item(
      lv = lv + offset,
      length = length - offset,
      originLeft = if (isPlaceholder) NO_UNIT else lv + offset - 1,
      rightParent = NO_UNIT,
      prepareState = prepareState,
      effectState = effectState,
    )
    length = offset
    return right
  }

  override fun toString(): String {
    val kind = if (isPlaceholder) "placeholder" else "item"
    val span = if (lv == lastUnit) "$lv" else "$lv..$lastUnit"
    // The two states agree most of the time, and a disagreement is what a reader looks for.
    val states = if (prepareState == effectState) {
      stateName(prepareState)
    } else {
      "prepare=${stateName(prepareState)} effect=${stateName(effectState)}"
    }
    return "$kind[$span] $states"
  }

  private companion object {
    /** The prepare version has not reached the op that creates the item. */
    const val NOT_YET_INSERTED = -1

    /** The version has the characters. */
    const val INSERTED = 0

    /** The version removed the characters. A prepare state counts stacked concurrent deletes. */
    const val DELETED = 1

    /**
     * The state as a word. A delete states how many deletes stacked on it, because a prepare
     * state counts them and only the count tells one concurrent delete from several.
     */
    fun stateName(state: Int): String {
      return when (state) {
        NOT_YET_INSERTED -> "not-inserted"
        INSERTED -> "inserted"
        else -> "deleted($state)"
      }
    }
  }
}

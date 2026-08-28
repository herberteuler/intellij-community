// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Event

/**
 * One stored run: an [Event] plus its links into the graph. The run covers the lvs
 * `[lvStart, lvEnd())`, one per unit of the event.
 *
 * The run owns the arithmetic between an lv and the unit it names. A caller asks the run
 * about an lv and never subtracts [lvStart] itself.
 */
internal class StoredRun(
  val event: Event,
  val lvStart: LV,
  private val parents: IntArray,
) {
  /** The lv after the last unit. */
  fun lvEnd(): LV {
    return lvStart + event.length()
  }

  /** Whether this run covers [lv]. */
  fun contains(lv: LV): Boolean {
    return lv >= lvStart && lv < lvEnd()
  }

  /** Whether [lv] is at or after the first unit. The run search uses this. */
  fun startsAtOrBefore(lv: LV): Boolean {
    return lvStart <= lv
  }

  /** The number of units from [lv] to the end of the run. */
  fun unitsFrom(lv: LV): Int {
    return lvEnd() - lv
  }

  val isDelete: Boolean get() = event is Event.Delete

  /**
   * The parents of the unit [lv]. The first unit keeps the parents the append recorded;
   * every later unit has the one implicit parent `lv - 1`.
   */
  fun parentsOf(lv: LV): IntArray {
    return if (lv == lvStart) {
      parents
    } else {
      intArrayOf(lv - 1)
    }
  }

  /** The parents of the first unit, which are the parents of the whole run. */
  fun runParents(): IntArray {
    return parents
  }

  /** The offset that the unit [lv] edits, in its own parent version. */
  fun offsetAt(lv: LV): Int {
    return event.offsetOfUnit(lv - lvStart)
  }

  /** The seq that names the unit [lv]. */
  fun seqAt(lv: LV): Int {
    return event.seq() + (lv - lvStart)
  }

  /** The character that the unit [lv] inserts. The run must be an insert. */
  fun charAt(lv: LV): Char {
    val insert = event as? Event.Insert
    require(insert != null) {
      "The lv $lv is not an insert"
    }
    return insert.fragment()[lv - lvStart]
  }

  override fun toString(): String {
    return "run[$lvStart..${lvEnd() - 1}] $event"
  }
}

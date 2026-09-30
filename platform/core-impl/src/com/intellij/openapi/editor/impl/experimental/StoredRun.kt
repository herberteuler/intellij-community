// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph

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
  private val parents: Frontier,
) {
  /** The lv after the last unit. */
  fun lvEnd(): LV {
    return lvStart + event.length()
  }

  /** The first seq after the last unit. */
  fun endSeq(): Int {
    return event.seq() + event.length()
  }

  /** Whether this run holds the unit ([agent], [seq]). */
  fun holdsUnit(agent: Agent, seq: Int): Boolean {
    return agent == event.agent() && seq >= event.seq() && seq < endSeq()
  }

  /** The lv of the unit [seq], which this run must hold. */
  fun lvOfSeq(seq: Int): LV {
    return lvStart + (seq - event.seq())
  }

  /** Whether [lv] is at or after the first unit. The run search uses this. */
  fun startsAtOrBefore(lv: LV): Boolean {
    return lvStart <= lv
  }

  val isDelete: Boolean get() = event.op() is DocOp.Delete

  /**
   * The parents of the unit [lv]. The first unit keeps the parents the append recorded;
   * every later unit has the one implicit parent `lv - 1`.
   */
  fun parentsOf(lv: LV): Frontier {
    return if (lv == lvStart) {
      parents
    } else {
      intArrayOf(lv - 1)
    }
  }

  /** The parents of the first unit, which are the parents of the whole run. */
  fun runParents(): Frontier {
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
    return insertOp(lv).fragment()[lv - lvStart]
  }

  /**
   * The characters that the [count] units from [lv] insert. The run must be an insert, and
   * it must cover the whole span.
   */
  fun fragmentFrom(lv: LV, count: Int): CharSequence {
    val from = lv - lvStart
    return insertOp(lv).fragment().subSequence(from, from + count)
  }

  /**
   * This run extended by [next], or `null` when [next] does not continue it. The reference
   * implementation makes the same test for its graph entries in `tryAppendEntries`, and extends
   * the entry in place. A run is immutable, so this returns a new one.
   *
   * A later unit of a run has two properties that nothing stores. Its one parent is the unit
   * before it, and it edits at the offset that [Event.offsetOfUnit] gives it. So [next] must
   * have both. [parents] must name only the last unit of this run. The op of [next] must start
   * where the next unit of this run would edit. The id must continue too, because a run holds
   * one id range.
   *
   * An insert run grows only up to [EventGraph.MAX_COALESCED_INSERT] characters, because each
   * extension copies the fragment.
   */
  fun tryAppend(next: Event, parents: VersionImpl): StoredRun? {
    if (!isOnlyParent(parents) || next.agent() != event.agent() || next.seq() != endSeq()) {
      return null
    }
    val nextOp = next.op()
    val joined = when (val op = event.op()) {
      is DocOp.Insert -> {
        if (nextOp !is DocOp.Insert || !continuesInsert(op, nextOp)) {
          return null
        }
        DocOp.ins(op.offset(), op.fragment().toString() + nextOp.fragment())
      }
      is DocOp.Delete -> {
        if (nextOp !is DocOp.Delete || !continuesDelete(op, nextOp)) {
          return null
        }
        DocOp.del(op.offset(), op.length() + nextOp.length())
      }
    }
    return StoredRun(Event.create(event.agent(), event.seq(), joined), lvStart, this.parents)
  }

  /** Whether [parents] names the last unit of this run and nothing else. */
  private fun isOnlyParent(parents: VersionImpl): Boolean {
    val lvs = parents.lvs
    return lvs.size == 1 && lvs[0] == lvEnd() - 1
  }

  /** An insert walks forward, so [next] continues at the end of [op], within the length limit. */
  private fun continuesInsert(op: DocOp.Insert, next: DocOp.Insert): Boolean {
    return next.offset() == op.offset() + op.length() &&
           next.length() <= EventGraph.MAX_COALESCED_INSERT - op.length()
  }

  /**
   * A delete stays in place, so [next] continues at the offset of [op]. The joined delete must
   * still fit the offset space that an event checks, or the append would fail where a new run
   * succeeds.
   */
  private fun continuesDelete(op: DocOp.Delete, next: DocOp.Delete): Boolean {
    return next.offset() == op.offset() &&
           next.length() <= Int.MAX_VALUE - op.offset() - op.length()
  }

  /** The insert op of this run, for a caller that reads its content. */
  private fun insertOp(lv: LV): DocOp.Insert {
    val op = event.op()
    require(op is DocOp.Insert) {
      "The lv $lv is not an insert"
    }
    return op
  }

  override fun toString(): String {
    return "run[$lvStart..${lvEnd() - 1}] $event"
  }
}

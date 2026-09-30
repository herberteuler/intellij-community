// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.EventImpl

/**
 * One node of the Eg-walker event graph: a run of single-character operations with one id range.
 *
 * An event is an identity plus the change that the identity names. The identity is
 * ([agent], [seq]) to ([agent], `seq + length - 1`), and the change is [op].
 *
 * An event is NOT a [DocOp], so [DocText.applyOp] will not take one. The offset of [op]
 * indexes the document as it was in the PARENT VERSION, and applying an old event to a
 * document at another version is meaningless.
 *
 * The paper (arXiv 2409.14252) models one event per character. This implementation
 * run-length encodes them: one event covers [length] characters. The parents of the first
 * unit live in [EventGraph]; every later unit's parent is the unit before it.
 *
 * An event is immutable: a merge never rewrites it.
 */
interface Event {
  fun agent(): Agent

  /** The seq of the first unit. The run consumes the seqs `[seq, seq + length)`. */
  fun seq(): Int

  /** The change this event records, against the document of its parent version. */
  fun op(): DocOp

  /** The number of single-character operations in this run. At least 1. */
  fun length(): Int

  /**
   * The offset that the unit [index] of this run edits, in the document at the parents of that
   * unit. [index] must be in `[0, length)`.
   *
   * An insert walks forward, because every character it adds shifts the next one right.
   * A delete stays in place, because every removal shifts the next character into it.
   */
  fun offsetOfUnit(index: Int): Int

  /**
   * The part of this run from the unit [units] onward, as an event of its own. A merge
   * needs it when the other replica holds only the leading units of the run. [units] must be
   * in `[0, length)`, and a zero [units] returns this event.
   */
  fun suffixFrom(units: Int): Event

  companion object {
    /**
     * An event that records [op] under the id ([agent], [seq]).
     *
     * The event keeps [op] and never copies it, so [op] must come from [DocOp.ins] or
     * [DocOp.del]. Only those two detach the content from a sequence the caller can still
     * change, and an event lives in the graph forever.
     */
    fun create(agent: Agent, seq: Int, op: DocOp): Event {
      return EventImpl(agent, seq, op)
    }

    fun createInsert(agent: Agent, seq: Int, offset: Int, fragment: CharSequence): Event {
      return create(agent, seq, DocOp.ins(offset, fragment))
    }

    fun createDelete(agent: Agent, seq: Int, offset: Int, length: Int): Event {
      return create(agent, seq, DocOp.del(offset, length))
    }
  }
}

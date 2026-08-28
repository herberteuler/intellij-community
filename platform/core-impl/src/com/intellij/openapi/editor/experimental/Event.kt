// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.DeleteEventImpl
import com.intellij.openapi.editor.impl.experimental.InsertEventImpl

/**
 * One node of the Eg-walker event graph: a run of single-character operations with one id range.
 *
 * An event is a [DocOp] with an identity. [Insert] is a [DocOp.Insert] and [Delete] is a
 * [DocOp.Delete], so the change itself needs no second shape. What the event adds is the
 * id: ([agent], [seq]) to ([agent], `seq + length - 1`).
 *
 * The paper (arXiv 2409.14252) models one event per character. This implementation
 * run-length encodes them: one event covers [length] characters. The parents of the first
 * unit live in [EventGraph]; every later unit's parent is the unit before it.
 *
 * [offset] indexes the document as it was in the PARENT VERSION, not the merged document.
 * That is the one place where an event differs from a fresh op: applying an old event to a
 * document at another version is meaningless, even though the types allow it. The unit `i`
 * of an [Insert] puts `fragment()[i]` at `offset + i`. Every unit of a [Delete] removes one
 * character at [offset], because each removal shifts the next character there.
 *
 * An event is immutable: a merge never rewrites it.
 */
sealed interface Event {
  fun agent(): Agent

  /** The seq of the first unit. The run consumes the seqs `[seq, seq + length)`. */
  fun seq(): Int

  /** The position of the first unit in the parent-version document. */
  fun offset(): Int

  /** The number of single-character operations in this run. At least 1. */
  fun length(): Int

  /**
   * The offset that the unit [index] of this run edits, in the parent-version document.
   *
   * An insert walks forward, because every character it adds shifts the next one right.
   * A delete stays in place, because every removal shifts the next character into it.
   */
  fun offsetOfUnit(index: Int): Int

  /**
   * The part of this run from the unit [units] onward, as an event of its own. A merge
   * needs it when the other replica holds only the leading units of the run. A zero
   * [units] returns this event.
   */
  fun suffixFrom(units: Int): Event

  /** `fragment().length == length()`. */
  interface Insert : Event, DocOp.Insert

  interface Delete : Event, DocOp.Delete

  companion object {
    fun createInsert(agent: Agent, seq: Int, offset: Int, fragment: CharSequence): Insert {
      return InsertEventImpl(agent, seq, offset, fragment)
    }
    fun createDelete(agent: Agent, seq: Int, offset: Int, length: Int): Delete {
      return DeleteEventImpl(agent, seq, offset, length)
    }
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.DeleteEventImpl
import com.intellij.openapi.editor.impl.experimental.InsertEventImpl

/**
 * One node of the Eg-walker event graph: a run of single-character operations with one id range.
 *
 * The paper (arXiv 2409.14252) models one event per character. This implementation
 * run-length encodes them: one event covers [length] characters, and its ids are
 * ([agent], [seq]) to ([agent], `seq + length - 1`). The parents of the first unit
 * live in [EventGraph]; every later unit's parent is the unit before it.
 *
 * [pos] indexes the document as it was in the parent version, not the merged document.
 * The unit `i` of an [Insert] puts `content()[i]` at `pos + i`. Every unit of a [Delete]
 * removes one character at [pos], because each removal shifts the next character there.
 *
 * An event is immutable: a merge never rewrites it.
 */
sealed interface Event {
  fun agent(): Agent

  /** The seq of the first unit. The run consumes the seqs `[seq, seq + length)`. */
  fun seq(): Int

  /** The position of the first unit in the parent-version document. */
  fun pos(): Int

  /** The number of single-character operations in this run. At least 1. */
  fun length(): Int

  interface Insert : Event {
    /** The inserted characters; `content().length == length()`. */
    fun content(): CharSequence
  }

  interface Delete : Event

  companion object {
    fun createInsert(agent: Agent, seq: Int, pos: Int, content: CharSequence): Insert = InsertEventImpl(agent, seq, pos, content)
    fun createDelete(agent: Agent, seq: Int, pos: Int, length: Int): Delete = DeleteEventImpl(agent, seq, pos, length)
  }
}

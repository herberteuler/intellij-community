// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.DeleteEventImpl
import com.intellij.openapi.editor.impl.experimental.InsertEventImpl

/**
 * One node of the Eg-walker event graph: a single-character operation with its id.
 *
 * The paper (arXiv 2409.14252) defines an event as an operation, a unique id, and a set
 * of parent versions. The id is the ([agent], [seq]) pair here. The parents live in
 * [EventGraph], not on the event, because the graph stores them as internal indexes.
 *
 * [pos] indexes the document as it was in the parent version, not the merged document.
 * An event is immutable: a merge never rewrites it.
 */
sealed interface Event {
  fun agent(): Agent
  fun seq(): Int
  fun pos(): Int

  interface Insert : Event {
    fun character(): Char
  }

  interface Delete : Event

  companion object {
    fun createInsert(agent: Agent, seq: Int, pos: Int, character: Char): Insert = InsertEventImpl(agent, seq, pos, character)
    fun createDelete(agent: Agent, seq: Int, pos: Int): Delete = DeleteEventImpl(agent, seq, pos)
  }
}

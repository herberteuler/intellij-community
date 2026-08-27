// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.Event

internal class InsertEventImpl(
  private val agent: Agent,
  private val seq: Int,
  private val pos: Int,
  private val character: Char,
) : Event.Insert {
  init {
    checkEvent(seq, pos)
  }

  override fun agent(): Agent = agent
  override fun seq(): Int = seq
  override fun pos(): Int = pos
  override fun character(): Char = character

  override fun toString(): String {
    return "ins($agent, $seq, $pos, '$character')"
  }
}

internal class DeleteEventImpl(
  private val agent: Agent,
  private val seq: Int,
  private val pos: Int,
) : Event.Delete {
  init {
    checkEvent(seq, pos)
  }

  override fun agent(): Agent = agent
  override fun seq(): Int = seq
  override fun pos(): Int = pos

  override fun toString(): String {
    return "del($agent, $seq, $pos)"
  }
}

private fun checkEvent(seq: Int, pos: Int) {
  require(seq >= 0) {
    "Negative seq: $seq"
  }
  require(pos >= 0) {
    "Negative pos: $pos"
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.Event

internal class InsertEventImpl(
  private val agent: Agent,
  private val seq: Int,
  private val pos: Int,
  content: CharSequence,
) : Event.Insert {
  // A copy detaches the event from a mutable CharSequence the caller may hold.
  private val content: String = content.toString()

  init {
    checkEvent(seq, pos)
    checkContent(this.content)
    checkIdSpace(seq, this.content.length)
    checkPosSpace(pos, this.content.length)
  }

  override fun agent(): Agent = agent
  override fun seq(): Int = seq
  override fun pos(): Int = pos
  override fun length(): Int = content.length
  override fun content(): CharSequence = content

  override fun toString(): String {
    return "ins($agent, $seq, $pos, \"$content\")"
  }
}

internal class DeleteEventImpl(
  private val agent: Agent,
  private val seq: Int,
  private val pos: Int,
  private val length: Int,
) : Event.Delete {
  init {
    checkEvent(seq, pos)
    checkLength(length)
    checkIdSpace(seq, length)
  }

  override fun agent(): Agent = agent
  override fun seq(): Int = seq
  override fun pos(): Int = pos
  override fun length(): Int = length

  override fun toString(): String {
    return "del($agent, $seq, $pos, len=$length)"
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

private fun checkContent(content: String) {
  require(content.isNotEmpty()) {
    "The insert content is empty"
  }
}

private fun checkLength(length: Int) {
  require(length >= 1) {
    "The delete length is not positive: $length"
  }
}

private fun checkIdSpace(seq: Int, length: Int) {
  require(length <= Int.MAX_VALUE - seq) {
    "The seq space overflows: seq $seq + length $length"
  }
}

private fun checkPosSpace(pos: Int, length: Int) {
  require(length <= Int.MAX_VALUE - pos) {
    "The position space overflows: pos $pos + length $length"
  }
}

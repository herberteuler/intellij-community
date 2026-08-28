// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.Event
import com.intellij.util.text.ImmutableCharSequence

internal class InsertEventImpl(
  private val agent: Agent,
  private val seq: Int,
  private val offset: Int,
  fragment: CharSequence,
) : Event.Insert {
  // A copy detaches the event from a mutable CharSequence the caller may hold. An op is
  // transient and may alias, but an event lives in the graph forever.
  private val fragment: CharSequence = ImmutableCharSequence.asImmutable(fragment)

  init {
    checkEvent(seq, offset)
    checkFragment(this.fragment)
    checkIdSpace(seq, this.fragment.length)
    checkOffsetSpace(offset, this.fragment.length)
  }

  override fun agent(): Agent = agent
  override fun seq(): Int = seq
  override fun offset(): Int = offset
  override fun length(): Int = fragment.length
  override fun fragment(): CharSequence = fragment

  override fun offsetOfUnit(index: Int): Int = offset + index

  override fun suffixFrom(units: Int): Event {
    if (units == 0) {
      return this
    }
    return InsertEventImpl(
      agent,
      seq + units,
      offset + units,
      fragment.subSequence(units, fragment.length),
    )
  }

  override fun toString(): String {
    return "ins($agent, $seq, $offset, ${fragment.quotedForMessage()})"
  }
}

internal class DeleteEventImpl(
  private val agent: Agent,
  private val seq: Int,
  private val offset: Int,
  private val length: Int,
) : Event.Delete {
  init {
    checkEvent(seq, offset)
    checkLength(length)
    checkIdSpace(seq, length)
  }

  override fun agent(): Agent = agent
  override fun seq(): Int = seq
  override fun offset(): Int = offset
  override fun length(): Int = length

  override fun offsetOfUnit(index: Int): Int = offset

  override fun suffixFrom(units: Int): Event {
    if (units == 0) {
      return this
    }
    return DeleteEventImpl(agent, seq + units, offset, length - units)
  }

  override fun toString(): String {
    return "del($agent, $seq, $offset, len=$length)"
  }
}

private fun checkEvent(seq: Int, offset: Int) {
  require(seq >= 0) {
    "Negative seq: $seq"
  }
  require(offset >= 0) {
    "Negative offset: $offset"
  }
}

private fun checkFragment(fragment: CharSequence) {
  require(fragment.isNotEmpty()) {
    "The insert fragment is empty"
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

private fun checkOffsetSpace(offset: Int, length: Int) {
  require(length <= Int.MAX_VALUE - offset) {
    "The offset space overflows: offset $offset + length $length"
  }
}

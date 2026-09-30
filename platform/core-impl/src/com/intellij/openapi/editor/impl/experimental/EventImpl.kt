// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.Event

internal class EventImpl(
  private val agent: Agent,
  private val seq: Int,
  private val op: DocOp,
) : Event {

  init {
    // The id order of the graph compares agents, and only AgentImpl knows how.
    AgentImpl.implOf(agent)
    checkKnownOp(op)
    val offset = op.offset()
    val length = op.length()
    checkNotNegative(seq, offset)
    checkLength(length)
    checkIdSpace(seq, length)
    checkOffsetSpace(offset, length)
  }

  override fun agent(): Agent = agent
  override fun seq(): Int = seq
  override fun op(): DocOp = op
  override fun length(): Int = op.length()

  override fun offsetOfUnit(index: Int): Int {
    checkUnitIndex(index)
    return when (op) {
      is DocOp.Insert -> op.offset() + index
      is DocOp.Delete -> op.offset()
    }
  }

  override fun suffixFrom(units: Int): EventImpl {
    checkUnitIndex(units)
    if (units == 0) {
      return this
    }
    return EventImpl(agent, seq + units, suffixOp(units))
  }

  /** The part of [op] from the unit [units] onward. A delete keeps its offset; see [offsetOfUnit]. */
  private fun suffixOp(units: Int): DocOp {
    return when (op) {
      is DocOp.Insert -> {
        val fragment = op.fragment()
        DocOp.ins(op.offset() + units, fragment.subSequence(units, fragment.length))
      }
      is DocOp.Delete -> DocOp.del(op.offset(), op.length() - units)
    }
  }

  /** Fails unless [index] names a unit of this run. A suffix from the end would be empty. */
  private fun checkUnitIndex(index: Int) {
    require(index in 0 until length()) {
      "The unit index $index is outside the run of length ${length()}"
    }
  }

  override fun toString(): String {
    return "$op by $agent, seq $seq"
  }

  companion object {
    /**
     * [event] as the one implementation the graph trusts. The graph keeps an event forever, and the
     * checks of the constructor are what make its length and its id range safe to store.
     */
    fun implOf(event: Event): EventImpl {
      require(event is EventImpl) {
        "Foreign Event implementation: ${event.javaClass.name}"
      }
      return event
    }
  }
}

private fun checkKnownOp(op: DocOp) {
  require(op is InsertDocOpImpl || op is DeleteDocOpImpl) {
    "Foreign DocOp implementation: ${op.javaClass.name}. An event keeps its op forever, " +
    "so the op must come from DocOp.ins or DocOp.del."
  }
}

private fun checkNotNegative(seq: Int, offset: Int) {
  require(seq >= 0) {
    "Negative seq: $seq"
  }
  require(offset >= 0) {
    "Negative offset: $offset"
  }
}

/** An empty op is a legal no-op, but an empty event would own no id and name nothing. */
private fun checkLength(length: Int) {
  require(length >= 1) {
    "The event length is not positive: $length"
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

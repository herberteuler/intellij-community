// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.DocText
import com.intellij.openapi.editor.experimental.DocTextBranch
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph
import com.intellij.openapi.editor.impl.DocTextImpl

/**
 * See [DocTextBranch].
 *
 * The value is a triple: the [graph] that records the history, the [agent] that authors
 * new events, and an [inner] [DocText] that holds the materialized text. [text] returns
 * [inner] as is, so the text and the line data behave exactly like [DocTextImpl]. A local
 * [applyOp] appends events at the graph's frontier and edits [inner] directly; the
 * Eg-walker replay runs only inside [merge].
 *
 * Prototype limits, deliberate:
 * - One event per character, no run-length encoding. A large document costs a large graph.
 * - A merge with concurrent history replays the full graph, not only the concurrent
 *   region. The paper's partial replay from a critical version is a follow-up.
 */
internal class DocTextBranchImpl private constructor(
  private val graph: EventGraphImpl,
  private val agent: Agent,
  private val nextSeq: Int,
  private val inner: DocText,
) : DocTextBranch {

  init {
    checkTextMatchesGraph()
  }

  override fun text(): DocText {
    return inner
  }

  override fun applyOp(op: DocOp): DocTextBranch {
    return when (op) {
      is DocOp.Insert -> applyInsert(op)
      is DocOp.Delete -> applyDelete(op)
    }
  }

  override fun agent(): Agent {
    return agent
  }

  override fun graph(): EventGraph {
    return graph
  }

  override fun fork(agent: Agent): DocTextBranch {
    return DocTextBranchImpl(graph, agent, nextSeqOf(graph, agent), inner)
  }

  override fun merge(other: DocTextBranch): DocTextBranch {
    val otherImpl = implOf(other)
    val result = graph.mergeFromImpl(otherImpl.graph)
    val merged = result.graph
    if (merged.size() == graph.size()) {
      // The other branch brought nothing new.
      return this
    }
    val mergedVersion = merged.versionImpl()
    val newInner = if (mergedVersion == result.remappedOtherVersion) {
      // A fast-forward: this branch's history is inside the other branch's history.
      otherImpl.inner
    }
    else {
      merged.replay(mergedVersion)
    }
    return DocTextBranchImpl(merged, agent, nextSeq, newInner)
  }

  private fun applyInsert(op: DocOp.Insert): DocTextBranch {
    val fragment = op.fragment()
    if (fragment.isEmpty()) {
      return this
    }
    // The inner text validates the offset before the graph changes.
    val newInner = inner.applyOp(op)
    val offset = op.offset()
    var newGraph = graph
    for (i in fragment.indices) {
      newGraph = newGraph.appendAtTip(Event.createInsert(agent, nextSeq + i, offset + i, fragment[i]))
    }
    return DocTextBranchImpl(newGraph, agent, nextSeq + fragment.length, newInner)
  }

  private fun applyDelete(op: DocOp.Delete): DocTextBranch {
    val length = op.length()
    if (length == 0) {
      return this
    }
    val newInner = inner.applyOp(op)
    val offset = op.offset()
    var newGraph = graph
    for (i in 0 until length) {
      // Every event deletes at the same position: the previous event shifted the text.
      newGraph = newGraph.appendAtTip(Event.createDelete(agent, nextSeq + i, offset))
    }
    return DocTextBranchImpl(newGraph, agent, nextSeq + length, newInner)
  }

  private fun checkTextMatchesGraph() {
    require(nextSeq >= 0) {
      "Negative nextSeq: $nextSeq"
    }
  }

  override fun toString(): String {
    return "DocTextBranch(agent=$agent, events=${graph.size()}, length=${inner.length()})"
  }

  companion object {
    fun create(chars: CharSequence, agent: Agent): DocTextBranchImpl {
      var graph = EventGraphImpl.empty()
      for (i in chars.indices) {
        graph = graph.appendAtTip(Event.createInsert(agent, i, i, chars[i]))
      }
      return DocTextBranchImpl(graph, agent, chars.length, DocText.createText(chars))
    }

    private fun implOf(branch: DocTextBranch): DocTextBranchImpl {
      require(branch is DocTextBranchImpl) {
        "Foreign DocTextBranch implementation: ${branch.javaClass.name}"
      }
      return branch
    }

    /** The next free seq of [agent] in [graph]: one scan over the events. */
    private fun nextSeqOf(graph: EventGraphImpl, agent: Agent): Int {
      var next = 0
      for (lv in 0 until graph.size()) {
        val event = graph.eventOf(lv)
        if (event.agent() == agent && event.seq() >= next) {
          next = event.seq() + 1
        }
      }
      return next
    }
  }
}

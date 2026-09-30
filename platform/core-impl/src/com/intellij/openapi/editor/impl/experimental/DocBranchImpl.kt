// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.DocText
import com.intellij.openapi.editor.experimental.DocBranch
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph
import com.intellij.openapi.editor.impl.DocTextImpl

/**
 * See [DocBranch].
 *
 * The value is a triple: the [graph] that records the history, the [agent] that authors
 * new events, and the [docText] that holds the materialized text. [text] returns [docText]
 * as is, so the text and the line data behave exactly like [DocTextImpl]. A local
 * [applyOp] appends events at the graph's frontier and edits [docText] directly; the
 * Eg-walker replay runs only inside [merge].
 *
 * A merge with concurrent history replays only the region above the common ancestor
 * (the paper's partial replay): one lazily-split placeholder item stands in for the
 * older document, and the new units apply to [docText] as ordinary [DocOp]s. The merge
 * cost depends on the size of the change and of the concurrent region, not on the size of
 * either history.
 *
 * Prototype limits, deliberate:
 * - A keystroke extends the newest run when it continues it (see [EventGraph.append]), but a
 *   backspace never does. Each backspace therefore still costs a run of its own.
 * - The replay scans its item list linearly. `findItemIdx` always starts at the head, and
 *   `findByCurPos` restarts there whenever the target sits before the cached cursor. The
 *   list holds the walked region and not the document, so a merge stays cheap and a FULL
 *   replay is what this costs.
 */
internal class DocBranchImpl private constructor(
  private val docText: DocText,
  private val agent: Agent,
  private val graph: EventGraphImpl,
) : DocBranch {

  init {
    // A foreign agent would pass until the first edit, and fail there.
    AgentImpl.implOf(agent)
  }

  override fun text(): DocText {
    return docText
  }

  override fun applyOp(op: DocOp): DocBranch {
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

  override fun fork(agent: Agent): DocBranch {
    return DocBranchImpl(docText, agent, graph)
  }

  override fun merge(other: DocBranch): DocBranch {
    val otherImpl = implOf(other)
    val result = graph.mergeFromImpl(otherImpl.graph)
    if (result.addsNothing()) {
      return this
    }
    val merged = result.graph
    val newDocText = if (result.isFastForward()) {
      // This branch's history is inside the other branch's history, so its text is ready.
      otherImpl.docText
    } else {
      // A partial replay: only the region above the common ancestor is walked, and
      // only the new units reach the sink, batched into ordinary ops over the text.
      val sink = BatchingSink(docText)
      EgWalkerReplay.mergeInto(merged, graph.versionImpl(), sink)
      sink.result()
    }
    return DocBranchImpl(newDocText, agent, merged)
  }

  private fun applyInsert(op: DocOp.Insert): DocBranch {
    val fragment = op.fragment()
    if (fragment.isEmpty()) {
      return this
    }
    // The inner text validates the offset before the graph changes.
    val newDocText = docText.applyOp(op)
    return DocBranchImpl(newDocText, agent, appendLocal(op))
  }

  private fun applyDelete(op: DocOp.Delete): DocBranch {
    val length = op.length()
    if (length == 0) {
      return this
    }
    val newDocText = docText.applyOp(op)
    return DocBranchImpl(newDocText, agent, appendLocal(op))
  }

  /**
   * The graph with [op] appended at its frontier, under the next free seq of [agent].
   *
   * The graph is the only record of that seq. A merge can bring in units of [agent] that this
   * value never saw, for example from a descendant of it, and a seq kept beside the graph would
   * then name a unit that already exists.
   */
  private fun appendLocal(op: DocOp): EventGraphImpl {
    return graph.appendAtTip(Event.create(agent, graph.nextSeqFor(agent), op))
  }

  override fun toString(): String {
    return "DocBranch(agent=$agent, events=${graph.size()}, length=${docText.length()})"
  }

  companion object {
    fun create(chars: CharSequence, agent: Agent): DocBranchImpl {
      var graph = EventGraphImpl.empty()
      if (chars.isNotEmpty()) {
        val insert = Event.create(agent, 0, DocOp.ins(0, chars))
        graph = graph.appendAtTip(insert)
      }
      return DocBranchImpl(DocText.createText(chars), agent, graph)
    }

    private fun implOf(branch: DocBranch): DocBranchImpl {
      require(branch is DocBranchImpl) {
        "Foreign DocBranch implementation: ${branch.javaClass.name}"
      }
      return branch
    }
  }
}

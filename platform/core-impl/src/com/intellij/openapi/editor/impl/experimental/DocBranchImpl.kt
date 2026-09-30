// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.DocText
import com.intellij.openapi.editor.experimental.DocBranch
import com.intellij.openapi.editor.experimental.DocMerge
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph
import com.intellij.openapi.editor.impl.DocTextImpl
import java.util.Collections

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
 * older document, and the new units apply to [docText] as ordinary [DocOp]s. Those ops are
 * the op stream of [mergeWithOps]. The merge cost depends on the size of the change and of the
 * concurrent region, not on the size of either history.
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
    return mergeWithOps(other).branch()
  }

  override fun mergeWithOps(other: DocBranch): DocMerge {
    val otherImpl = implOf(other)
    val result = graph.mergeFromImpl(otherImpl.graph)
    if (result.addsNothing()) {
      // The same kind of list as the other outcomes, so a caller sees one behaviour.
      return DocMergeImpl.ready(this, Collections.emptyList())
    }
    val merged = result.graph
    if (result.isFastForward()) {
      // This branch's history is inside the other branch's history, so its text is ready. The ops
      // would cost a replay of the change, so they wait until a caller asks for them.
      val branch = DocBranchImpl(otherImpl.docText, agent, merged)
      return DocMergeImpl.deferred(branch) {
        opsOfFastForward(merged, otherImpl.docText)
      }
    }
    // A partial replay: only the region above the common ancestor is walked, and only the new
    // units reach the sink, batched into ordinary ops over the text.
    val sink = replayOnto(merged)
    return DocMergeImpl.ready(DocBranchImpl(sink.result(), agent, merged), sink.ops())
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

  /**
   * The text of this branch, with everything that [merged] holds beyond this branch applied, in a
   * sink that recorded the ops. [merged] must come from a merge into this branch, so the lvs of this
   * branch name the same units there.
   */
  private fun replayOnto(merged: EventGraphImpl): BatchingSink {
    val sink = BatchingSink(docText)
    EgWalkerReplay.mergeInto(merged, graph.versionImpl(), sink)
    return sink
  }

  /**
   * The ops of a fast-forward to [merged], whose text is [expected]. The replay walks only the
   * units above this branch, so it costs the change. A replay that ends at another length would
   * hand an editor ops for a text the branch does not have, so that fails here.
   */
  private fun opsOfFastForward(merged: EventGraphImpl, expected: DocText): List<DocOp> {
    val sink = replayOnto(merged)
    val replayed = sink.result()
    require(replayed.length() == expected.length()) {
      "The ops of a fast-forward build a text of length ${replayed.length()}, but the merged text has ${expected.length()}"
    }
    return sink.ops()
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

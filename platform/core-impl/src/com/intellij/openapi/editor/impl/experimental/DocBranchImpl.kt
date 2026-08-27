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
 * new events, and an [inner] [DocText] that holds the materialized text. [text] returns
 * [inner] as is, so the text and the line data behave exactly like [DocTextImpl]. A local
 * [applyOp] appends events at the graph's frontier and edits [inner] directly; the
 * Eg-walker replay runs only inside [merge].
 *
 * A merge with concurrent history replays only the region above the common ancestor
 * (the paper's partial replay): one lazily-split placeholder item stands in for the
 * older document, and the new units apply to [inner] as ordinary [DocOp]s. The merge
 * cost depends on the size of the concurrent region, not on the document size.
 *
 * Prototype limits, deliberate:
 * - The events are run-length encoded, but adjacent runs never coalesce, and the replay
 *   tracks one item per character of the region.
 * - An edit that lands far from the cached cursor scans the region's item list linearly.
 */
internal class DocBranchImpl private constructor(
  private val graph: EventGraphImpl,
  private val agent: Agent,
  private val nextSeq: Int,
  private val inner: DocText,
) : DocBranch {

  init {
    checkTextMatchesGraph()
  }

  override fun text(): DocText {
    return inner
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
    return DocBranchImpl(graph, agent, graph.nextSeqFor(agent), inner)
  }

  override fun merge(other: DocBranch): DocBranch {
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
      // A partial replay: only the region above the common ancestor is walked, and
      // only the new units reach the sink, batched into ordinary ops over the text.
      val sink = BatchingSink(inner)
      EgWalkerReplay.mergeInto(merged, graph.versionImpl(), sink)
      sink.result()
    }
    return DocBranchImpl(merged, agent, nextSeq, newInner)
  }

  private fun applyInsert(op: DocOp.Insert): DocBranch {
    val fragment = op.fragment()
    if (fragment.isEmpty()) {
      return this
    }
    // The inner text validates the offset before the graph changes.
    val newInner = inner.applyOp(op)
    val newGraph = graph.appendAtTip(Event.createInsert(agent, nextSeq, op.offset(), fragment))
    return DocBranchImpl(newGraph, agent, nextSeq + fragment.length, newInner)
  }

  private fun applyDelete(op: DocOp.Delete): DocBranch {
    val length = op.length()
    if (length == 0) {
      return this
    }
    val newInner = inner.applyOp(op)
    val newGraph = graph.appendAtTip(Event.createDelete(agent, nextSeq, op.offset(), length))
    return DocBranchImpl(newGraph, agent, nextSeq + length, newInner)
  }

  private fun checkTextMatchesGraph() {
    require(nextSeq >= 0) {
      "Negative nextSeq: $nextSeq"
    }
  }

  override fun toString(): String {
    return "DocBranch(agent=$agent, events=${graph.size()}, length=${inner.length()})"
  }

  companion object {
    fun create(chars: CharSequence, agent: Agent): DocBranchImpl {
      var graph = EventGraphImpl.empty()
      if (chars.isNotEmpty()) {
        graph = graph.appendAtTip(Event.createInsert(agent, 0, 0, chars))
      }
      return DocBranchImpl(graph, agent, chars.length, DocText.createText(chars))
    }

    private fun implOf(branch: DocBranch): DocBranchImpl {
      require(branch is DocBranchImpl) {
        "Foreign DocBranch implementation: ${branch.javaClass.name}"
      }
      return branch
    }
  }
}

/**
 * Coalesces the per-unit merge effects into fragment and range ops before they reach
 * the text. N successive inserts at the positions `pos, pos + 1, ...` equal one
 * fragment insert at `pos`; N successive deletes at one position equal one delete of
 * the length N. This is also the op stream an editor integration would fire as events.
 */
private class BatchingSink(private var updated: DocText) : EgWalkerReplay.Sink {
  private var kind = NONE
  private var start = 0
  private val fragment = StringBuilder()
  private var deleteCount = 0

  override fun insert(pos: Int, character: Char) {
    if (kind != INSERT || pos != start + fragment.length) {
      flush()
      kind = INSERT
      start = pos
    }
    fragment.append(character)
  }

  override fun delete(pos: Int) {
    if (kind != DELETE || pos != start) {
      flush()
      kind = DELETE
      start = pos
    }
    deleteCount++
  }

  fun result(): DocText {
    flush()
    return updated
  }

  private fun flush() {
    when (kind) {
      INSERT -> updated = updated.applyOp(InsertDocOp(start, fragment.toString()))
      DELETE -> updated = updated.applyOp(DeleteDocOp(start, deleteCount))
    }
    kind = NONE
    fragment.setLength(0)
    deleteCount = 0
  }

  companion object {
    private const val NONE = 0
    private const val INSERT = 1
    private const val DELETE = 2
  }
}

private class InsertDocOp(private val offset: Int, private val fragment: String) : DocOp.Insert {
  override fun offset(): Int = offset
  override fun fragment(): CharSequence = fragment
}

private class DeleteDocOp(private val offset: Int, private val length: Int) : DocOp.Delete {
  override fun offset(): Int = offset
  override fun length(): Int = length
}

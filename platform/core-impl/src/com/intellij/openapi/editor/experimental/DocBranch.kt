// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.DocBranchImpl

/**
 * An immutable document that records its edit history in an Eg-walker [EventGraph]
 * (arXiv 2409.14252). Two divergent copies of one document can then [merge] without losing either
 * side.
 *
 * The branch is a value: [applyOp], [fork], and [merge] return a new branch. The materialized
 * document at the branch's version is available through [text].
 *
 * A local [applyOp] is cheap: it appends events at the current version and edits the text
 * directly. The CRDT machinery runs only inside [merge] and [DocMerge.ops], on the concurrent
 * region, and is discarded afterwards.
 *
 * Contract:
 * - Branches that edit concurrently must edit under different [Agent]s. [fork] hands out
 *   that identity. Two concurrent edits under one agent break the event id uniqueness.
 * - [merge] expects branches that descend from one [createBranch] value. Branches with no
 *   common history merge into a concatenated document. That is correct for a CRDT, but it
 *   is not the intended use.
 * - `a.merge(b)` and `b.merge(a)` produce the same text. A merge with an ancestor changes
 *   nothing. A merge with a descendant fast-forwards. A repeated merge changes nothing.
 */
interface DocBranch {
  /**
   * The materialized document at this branch's version.
   */
  fun text(): DocText

  /**
   * The version of [text]. Under the agent contract, it stays valid in every branch that merges
   * this one, and a replay of it there returns [text].
   */
  fun version(): Version

  /**
   * A branch with [op] applied at this branch's version: the same edit as [DocText.applyOp].
   */
  fun applyOp(op: DocTextOp): DocBranch

  /**
   * The identity this branch edits under.
   */
  fun agent(): Agent

  /**
   * The event graph that records this branch's history.
   */
  fun graph(): EventGraph

  /**
   * A copy of this branch that edits under [agent]. The state and the history are shared.
   */
  fun fork(agent: Agent): DocBranch

  /**
   * A branch that contains the histories of both this branch and [other].
   * Concurrent edits are resolved deterministically; no edit is dropped.
   * The result keeps this branch's [agent].
   *
   * Throws [EventIdClashException] when the merge finds that the two branches broke the agent
   * contract. The check samples, so [EventIdClashException] says what it can miss. A merge that
   * fails changes nothing.
   *
   * The result is the branch of [mergeWithOps] for the same two branches.
   */
  fun merge(other: DocBranch): DocBranch

  /**
   * The same merge as [merge], plus the ops that turn the [text] of this branch into the merged
   * text. An editor needs them to apply a merge to its document. See [DocMerge] for their order
   * and their cost.
   */
  fun mergeWithOps(other: DocBranch): DocMerge

  companion object {
    @JvmStatic
    fun createBranch(chars: CharSequence, agent: Agent): DocBranch {
      return DocBranchImpl.create(chars, agent)
    }
  }
}

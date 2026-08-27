// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.DocBranchImpl

/**
 * An immutable document that records its edit history in an Eg-walker [EventGraph], so two
 * divergent copies of one document can [merge] without losing either side (arXiv 2409.14252).
 *
 * The branch is a value: [applyOp], [fork], and [merge] return a new branch. The materialized
 * document at the branch's version is available through [text].
 *
 * A local [applyOp] is cheap: it appends events at the current version and edits the text
 * directly. The CRDT machinery runs only inside [merge], on the concurrent region, and is
 * discarded afterwards.
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
  /** The materialized document at this branch's version. */
  fun text(): DocText

  /** A branch with [op] applied at this branch's version: the same edit as [DocText.applyOp]. */
  fun applyOp(op: DocOp): DocBranch

  /** The identity this branch edits under. */
  fun agent(): Agent

  /** The event graph that records this branch's history. */
  fun graph(): EventGraph

  /** A copy of this branch that edits under [agent]. The state and the history are shared. */
  fun fork(agent: Agent): DocBranch

  /**
   * A branch that contains the histories of both this branch and [other].
   * Concurrent edits are resolved deterministically; no edit is dropped.
   * The result keeps this branch's [agent].
   */
  fun merge(other: DocBranch): DocBranch

  companion object {
    fun createBranch(chars: CharSequence, agent: Agent): DocBranch = DocBranchImpl.create(chars, agent)
  }
}

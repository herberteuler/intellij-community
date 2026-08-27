// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.EventGraphImpl

/**
 * The Eg-walker event graph (arXiv 2409.14252): an append-only DAG of original [Event]s.
 * It is the only state the algorithm needs besides the document text.
 *
 * The graph is an immutable value. [append] and [mergeFrom] return a new graph and leave
 * this one untouched. Successive graphs share storage, so an append at the tip is cheap.
 *
 * This prototype stores one event per character and does not run-length encode anything.
 * That matches the reference implementation and is the first optimization to add later.
 */
interface EventGraph {
  /** The number of events in the graph. */
  fun size(): Int

  /** The paper's `Version(G)`: the current frontier of the graph. */
  fun version(): Version

  /**
   * Returns a graph with [event] appended.
   *
   * [parents] must be a version of this graph, and must be transitively reduced.
   * The event id must be new: one (agent, seq) pair names one event forever.
   */
  fun append(event: Event, parents: Version): EventGraph

  /**
   * Returns the union of this graph and [other], joined by event ids.
   * Events of [other] that this graph already contains are kept once.
   */
  fun mergeFrom(other: EventGraph): EventGraph

  /**
   * The paper's `replay(G)`, generalized to the subgraph `Events(version)`:
   * builds the document at [version] from scratch.
   *
   * The replay walks the events in a topological order. It resolves concurrent
   * insertions with the FugueMax order, so every replica computes the same text.
   */
  fun replay(version: Version): DocText

  fun replay(): DocText = replay(version())

  companion object {
    fun createGraph(): EventGraph = EventGraphImpl.empty()
  }
}

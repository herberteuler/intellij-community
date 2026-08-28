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
 * The storage is run-length encoded: one [Event] run of n characters costs one entry,
 * not n. Adjacent runs never coalesce; the replay still tracks one item per character.
 * Both are follow-up optimizations.
 */
interface EventGraph {
  /** The number of single-character operations in the graph, summed over all runs. */
  fun size(): Int

  /** The number of stored [Event] runs. `runCount() <= size()`; the gap is the encoding win. */
  fun runCount(): Int

  /** The paper's `Version(G)`: the current frontier of the graph. */
  fun version(): Version

  /**
   * Returns a graph with the [event] run appended.
   *
   * [parents] must be a version of this graph, and must be transitively reduced.
   * Every id the run covers must be new: one (agent, seq) pair names one unit forever.
   */
  fun append(event: Event, parents: Version): EventGraph

  /**
   * Returns the union of this graph and [other], joined by event ids.
   * Units of [other] that this graph already contains are kept once; when this graph
   * holds only the leading units of a run, the rest of the run is appended.
   */
  fun mergeFrom(other: EventGraph): EventGraph

  /**
   * The paper's `replay(G)`, generalized to the subgraph `Events(version)`:
   * builds the document at [version] from scratch.
   *
   * The replay walks the events in a topological order. It resolves concurrent
   * insertions with the Fugue order, so every replica computes the same text.
   */
  fun replay(version: Version): DocText

  fun replay(): DocText = replay(version())

  companion object {
    fun createGraph(): EventGraph = EventGraphImpl.empty()
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.ex.experimental

import com.intellij.openapi.editor.impl.experimental.EventGraphImpl

/**
 * The Eg-walker event graph (arXiv 2409.14252): an append-only DAG of original [Event]s.
 * It is the only state the algorithm needs besides the document text.
 *
 * The graph is an immutable value. [append] and [mergeFrom] return a new graph and leave
 * this one untouched. Successive graphs share their storage, so an append is cheap, and an older
 * graph appends as cheaply as the newest one.
 *
 * The storage is run-length encoded: one [Event] run of n characters costs one entry,
 * not n. An [append] that continues the newest run extends it, so typing costs one run per
 * burst and not one per keystroke.
 */
interface EventGraph {
  /**
   * The number of units in the graph, summed over all runs. See [Event] for a unit.
   */
  fun size(): Int

  /**
   * The number of stored [Event] runs. `runCount() <= size()`; the gap is the encoding win.
   */
  fun runCount(): Int

  /**
   * The paper's `Version(G)`: the current frontier of the graph.
   */
  fun version(): Version

  /**
   * Returns a graph with the [event] run appended.
   *
   * This graph must hold every head of [parents], or the append fails. The heads must be
   * transitively reduced, as they are in every version that a graph returns.
   *
   * The seq of the run must be the next free seq of its agent in this graph. So the seqs of one
   * agent ascend and leave no gap. That keeps the rule that one (agent, seq) pair names one unit
   * forever. It also lets [mergeFrom] compare two histories by agent instead of by run. Each agent
   * owns its own seq space, so two agents interleave freely.
   *
   * When [event] continues the newest run, the append extends that run and adds none. The
   * event continues the run when all of these hold:
   * - it has the same agent, and its seq is the next one after the run;
   * - [parents] names the last unit of the run and nothing else;
   * - the op has the same kind, and it starts where the run would edit next. That is the end of an
   *   insert, or the offset of a delete;
   * - an insert run stays within [MAX_COALESCED_INSERT] characters;
   * - a delete run stays within the offset space, so its offset plus its length fits an `Int`.
   *
   * The units, their ids, and their parents are the same either way, so only [runCount] shows
   * the difference. A backspace does not continue a delete run, because its units walk
   * backwards.
   */
  fun append(event: Event, parents: Version): EventGraph

  /**
   * Returns the union of this graph and [other], joined by event ids. Units of [other] that this
   * graph already contains are kept once. When this graph holds only the leading units of a run,
   * the merge appends the rest of the run.
   *
   * The cost is the size of the CHANGE. The two graphs compare one integer per agent, so a
   * merge never walks the history they share. It reads only a few shared units per agent for
   * the id check, and one run for each parent of a new run.
   *
   * Throws [EventIdClashException] when the two graphs give one id to two operations.
   */
  fun mergeFrom(other: EventGraph): EventGraph

  /**
   * The paper's `replay(G)`, generalized to the subgraph `Events(version)`:
   * builds the document at [version] from scratch. This graph must hold every head of [version],
   * or the replay fails.
   *
   * The replay walks the events in a topological order. It resolves concurrent
   * insertions with the Fugue order, so every replica computes the same text.
   */
  fun replay(version: Version): DocText

  fun replay(): DocText = replay(version())

  companion object {
    /**
     * The longest insert run that [append] builds by extending the newest run. Each extension
     * copies the fragment of the run, and this bounds that copy. One [Event] may still be
     * longer, and nothing then extends it.
     */
    const val MAX_COALESCED_INSERT: Int = 256

    @JvmStatic
    fun createGraph(): EventGraph {
      return EventGraphImpl.empty()
    }
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/**
 * The Eg-walker replay: rebuilds the document at a version from the event graph.
 * [ReplayWalker] holds the state and does the work; this is the entry point.
 *
 * This is a direct port of the reference implementation
 * (`Resources/eg-walker/eg-walker-reference/src/index.ts`). A function that the reference
 * also has keeps the reference's name, so the two stay easy to compare. A helper that only
 * this port needs gets a descriptive name.
 *
 * The walk keeps the document at two versions at once: the *prepare* version, where the
 * next event was authored, and the *effect* version, with every walked event applied. The
 * walk moves the prepare version to each event's parents, applies the event, and reports
 * the effect to a [Sink].
 *
 * The walk is run-length encoded on both sides. One item covers a whole run, and the walk
 * consumes as much of a run as the version list holds. An item splits only where an op
 * needs a boundary inside it: a concurrent insert, a partial delete, or a partial retreat
 * or advance. Costs: the item list is scanned linearly, but from a cached cursor, so a
 * sequential run advances in place. The worst case stays quadratic in the NUMBER OF ITEMS
 * of the walked region, which is what the run-length encoding shrinks.
 */
internal object EgWalkerReplay {

  internal interface Sink {
    fun insert(pos: Int, character: Char)
    fun delete(pos: Int)
  }

  /**
   * Rebuilds the text at [version] from scratch and reports every effect to [sink], in order.
   */
  fun replay(graph: EventGraphImpl, version: VersionImpl, sink: Sink) {
    ReplayWalker(graph, placeholderCount = 0).replayAt(version, sink)
  }

  /**
   * Merges everything the graph holds beyond [branchVersion] into a document that is
   * already at [branchVersion], and reports only the new effects to [sink]. This is
   * the paper's partial replay: only the region above the common ancestor is walked.
   *
   * One placeholder item stands in for the whole document at the common ancestor, so
   * the units at or below it are never replayed, and it splits lazily where the
   * region's ops land. A port of `mergeChangesIntoBranch` from the reference
   * implementation, with the paper's single-placeholder representation.
   */
  fun mergeInto(graph: EventGraphImpl, branchVersion: VersionImpl, sink: Sink) {
    val conflict = graph.findConflicting(branchVersion.lvs, graph.versionImpl().lvs)
    // One span of placeholder units, at least as long as the document at the common
    // ancestor. The trailing extras sit after every reachable position, inert.
    val walker = ReplayWalker(graph, branchVersion.unitSpan())
    walker.startAt(conflict.commonAncestor)
    // The branch's own units above the ancestor rebuild the concurrency context. The
    // document already has them, so this phase reports nothing.
    walker.walk(conflict.conflictLvs, sink = null)
    // The units only in the merged history are the new ones, so they report.
    walker.walk(conflict.newLvs, sink)
  }
}

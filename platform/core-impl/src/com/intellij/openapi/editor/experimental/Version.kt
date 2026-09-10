// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.LV
import com.intellij.openapi.editor.impl.experimental.VersionImpl

/**
 * A version of an [EventGraph]: the paper's `Version(G)`, the set of events with no children.
 * The paper also calls this set the frontier.
 *
 * A version identifies a document state: `Events(V)` is the set of all events at or before it,
 * and a replay of that set is the document at this version.
 *
 * A version is opaque and belongs to the graph that produced it. Two versions are [equals]
 * when they name the same event set of the same graph.
 */
interface Version {
  /** `true` for the version of the empty graph, before any event. */
  fun isRoot(): Boolean

  companion object {
    fun root(): Version = VersionImpl.ROOT
    fun of(lv: LV, vararg lvs: LV): Version = VersionImpl(intArrayOf(lv, *lvs))
  }
}

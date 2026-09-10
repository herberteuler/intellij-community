// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/**
 * A local version: the index of one single-character operation (a unit) in the event
 * graph, in the graph's own append order. The reference implementation calls this LV.
 *
 * An LV is local to one graph value: after a merge the same unit can sit at a different
 * LV in the merged graph, so LVs never travel between graphs without a remap. The value
 * -1 marks "no unit" where a sentinel is needed.
 *
 * An LV is not a seq. A seq is the durable half of an event id and travels between graphs
 * unchanged, which is why [EventStore.lvOfSeq] has to translate one into the other.
 */
internal typealias LV = Int

/** No unit: the document start for a left origin, the document end for a right parent. */
internal const val NO_UNIT: LV = -1

/**
 * A version, which the paper calls the frontier: the LVs that have no child. The array is
 * sorted ascending, holds no duplicate, and is transitively reduced, so no entry is an
 * ancestor of another. [VersionImpl] is the same thing with the invariant enforced.
 *
 * A frontier names a document state. Contrast [LvList], which names units to process.
 */
internal typealias Frontier = IntArray

/**
 * The LVs that a walk must visit, sorted ascending, one entry per unit.
 *
 * This is NOT a [Frontier]: it names every unit in a region, not only the heads. The two
 * are never interchangeable, although Kotlin resolves both aliases to `IntArray` and so
 * cannot enforce that. Where a mix-up would be dangerous, the code takes a [VersionImpl]
 * instead, which is a real type.
 */
internal typealias LvList = IntArray

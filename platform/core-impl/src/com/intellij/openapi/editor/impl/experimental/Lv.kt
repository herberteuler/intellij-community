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
 * An `IntArray` in this package whose name or KDoc says "lvs", "parents", or "frontier"
 * is an array of LVs.
 */
internal typealias LV = Int

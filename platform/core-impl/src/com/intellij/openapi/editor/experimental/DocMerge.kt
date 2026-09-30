// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

/**
 * The result of [DocBranch.mergeWithOps]: the merged branch, plus the ops that turn the text of the
 * receiver into the text of that branch.
 *
 * An editor applies these ops to its document, so the carets, the markers and every other listener
 * follow a merge like any other edit.
 *
 * The value is immutable, and any thread may read it.
 */
interface DocMerge {
  /** The merged branch: the value that [DocBranch.merge] returns for the same two branches. */
  fun branch(): DocBranch

  /**
   * The ops from the text of the receiver to the text of [branch], in apply order.
   *
   * Apply them one after another. The offset of each op indexes the text after all the ops before
   * it, so the text of the receiver with every op applied in turn equals the text of [branch]. The
   * texts between two ops are steps of this list only, and no replica ever held them.
   *
   * Each op comes from [DocOp.ins] or [DocOp.del], and no op is empty. A merge that changes no text
   * has no ops. The list cannot change, and every call returns the same list.
   *
   * A fast-forward needs no replay to build its text, so it builds its ops only on the first call.
   * That call costs a replay of the change, and not of the document.
   */
  fun ops(): List<DocOp>
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.DeleteDocTextOpImpl
import com.intellij.openapi.editor.impl.experimental.InsertDocTextOpImpl

/**
 * One edit of a document: what to change, and where.
 *
 * An op is a command, not a record. Its [offset] indexes the document that the op applies
 * to, so an op is only meaningful against that one document state.
 *
 * Two ops are equal when they make the same change at the same offset.
 */
sealed interface DocTextOp {
  /**
   * The position in the document that this op changes.
   */
  fun offset(): Int

  /**
   * The number of characters that this op inserts or deletes.
   */
  fun length(): Int

  interface Insert : DocTextOp {
    fun fragment(): CharSequence
  }

  /**
   * A delete carries no content, so [offset] and [length] describe it in full.
   */
  interface Delete : DocTextOp

  companion object {
    /**
     * An insert of [fragment] at [offset]. The op detaches itself from [fragment], so a
     * caller may keep changing a mutable sequence afterwards. The copy is free when the
     * sequence is already immutable, which a `String` is.
     *
     * The op does not check the bounds. The document rejects an offset it cannot use, and an empty
     * fragment is a legal op that changes nothing.
     */
    @JvmStatic
    fun insertOp(offset: Int, fragment: CharSequence): Insert {
      return InsertDocTextOpImpl(offset, fragment)
    }

    /**
     * A delete of [length] characters at [offset]. A zero length is a legal op that changes nothing.
     */
    @JvmStatic
    fun deleteOp(offset: Int, length: Int): Delete {
      return DeleteDocTextOpImpl(offset, length)
    }
  }
}

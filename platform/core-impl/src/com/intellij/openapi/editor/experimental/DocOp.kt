// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.DeleteDocOpImpl
import com.intellij.openapi.editor.impl.experimental.InsertDocOpImpl

/**
 * One edit of a document: what to change, and where.
 *
 * An op is a command, not a record. Its offset indexes the document that the op applies
 * to, so an op is only meaningful against that one document state. An [Event] is the
 * durable form: the same change, plus the identity that names it forever.
 */
sealed interface DocOp {
  interface Insert : DocOp {
    fun offset(): Int
    fun fragment(): CharSequence
  }

  interface Delete : DocOp {
    fun offset(): Int
    fun length(): Int
  }

  companion object {
    /**
     * An insert of [fragment] at [offset]. The op detaches itself from [fragment], so a
     * caller may keep changing a mutable sequence afterwards. The copy is free when the
     * sequence is already immutable, which a `String` is.
     *
     * The op holds no opinion about the bounds: the document rejects an offset it cannot
     * use, and an empty fragment is a legal op that changes nothing.
     */
    fun ins(offset: Int, fragment: CharSequence): Insert = InsertDocOpImpl(offset, fragment)

    /** A delete of [length] characters at [offset]. A zero length is a legal op that changes nothing. */
    fun del(offset: Int, length: Int): Delete = DeleteDocOpImpl(offset, length)
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.util.text.ImmutableCharSequence

internal class InsertDocOpImpl(
  private val offset: Int,
  fragment: CharSequence,
) : DocOp.Insert {
  // A copy detaches the op from a mutable CharSequence the caller may keep. It is free
  // for a sequence that is already immutable, and a String is the usual case.
  private val fragment: CharSequence = ImmutableCharSequence.asImmutable(fragment)

  override fun offset(): Int = offset
  override fun fragment(): CharSequence = fragment

  override fun toString(): String {
    return "ins($offset, ${fragment.quotedForMessage()})"
  }
}

internal class DeleteDocOpImpl(
  private val offset: Int,
  private val length: Int,
) : DocOp.Delete {
  override fun offset(): Int = offset
  override fun length(): Int = length

  override fun toString(): String {
    return "del($offset, len=$length)"
  }
}

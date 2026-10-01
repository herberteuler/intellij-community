// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.DocTextOp
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.text.ImmutableCharSequence

internal class InsertDocTextOpImpl(
  private val offset: Int,
  fragment: CharSequence,
) : DocTextOp.Insert {
  /**
   * A copy detaches the op from a mutable CharSequence the caller may keep.
   * It is free for a sequence that is already immutable, and a String is the usual case.
   */
  private val fragment: CharSequence = ImmutableCharSequence.asImmutable(fragment)

  override fun offset(): Int = offset
  override fun length(): Int = fragment.length
  override fun fragment(): CharSequence = fragment

  override fun equals(other: Any?): Boolean {
    return other is InsertDocTextOpImpl &&
           offset == other.offset &&
           fragment.contentEquals(other.fragment)
  }

  override fun hashCode(): Int {
    return 31 * offset + StringUtil.stringHashCode(fragment)
  }

  override fun toString(): String {
    return "ins($offset, ${fragment.quotedForMessage()})"
  }
}

internal class DeleteDocTextOpImpl(
  private val offset: Int,
  private val length: Int,
) : DocTextOp.Delete {
  override fun offset(): Int = offset
  override fun length(): Int = length

  override fun equals(other: Any?): Boolean {
    return other is DeleteDocTextOpImpl &&
           offset == other.offset &&
           length == other.length
  }

  override fun hashCode(): Int {
    return 31 * offset + length
  }

  override fun toString(): String {
    return "del($offset, len=$length)"
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.ex.LineIterator
import com.intellij.openapi.editor.impl.DocTextImpl
import com.intellij.openapi.util.TextRange
import com.intellij.util.text.ImmutableCharSequence

interface DocText {
  fun chars(): ImmutableCharSequence
  fun cachedChars(): CharSequence
  fun string(range: TextRange): String
  fun string(): String
  fun length(): Int
  fun lineCount(): Int
  fun lineNumber(offset: Int): Int
  fun lineStartOffset(line: Int): Int
  fun lineEndOffset(line: Int): Int
  fun lineSeparatorLength(line: Int): Int
  fun lineIterator(): LineIterator
  fun applyOp(op: DocTextOp): DocText
  companion object {
    fun createText(chars: CharSequence): DocText {
      return DocTextImpl(chars)
    }
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl

import com.intellij.openapi.editor.ex.DocumentNewOps
import com.intellij.openapi.editor.ex.DocumentOp

internal class DocumentNewOpsImpl : DocumentNewOps {
  override fun createModStampOp(stamp: Long, incSequence: Boolean): DocumentOp.ModStamp {
    return ModStampImpl(stamp, incSequence)
  }

  override fun createUnmodifiedLinesOp(startLine: Int, endLine: Int, exceptLines: IntArray): DocumentOp.UnmodifiedLines {
    require(startLine >= 0)
    require(endLine >= 0)
    return UnmodifiedLinesImpl(startLine, endLine, exceptLines)
  }

  private class ModStampImpl(
    private val stamp: Long,
    private val incSequence: Boolean,
  ) : DocumentOp.ModStamp {
    override fun modStamp(): Long = stamp
    override fun incSequence(): Boolean = incSequence
  }

  private class UnmodifiedLinesImpl(
    private val startLine: Int,
    private val endLine: Int,
    private val exceptLines: IntArray,
  ) : DocumentOp.UnmodifiedLines {
    override fun startLine(): Int = startLine
    override fun endLine(): Int = endLine
    override fun exceptLines(): IntArray = exceptLines
  }
}

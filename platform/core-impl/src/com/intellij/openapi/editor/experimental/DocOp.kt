// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

sealed interface DocOp {
  interface Insert : DocOp {
    fun offset(): Int
    fun fragment(): CharSequence
  }

  interface Delete : DocOp {
    fun offset(): Int
    fun length(): Int
  }
}

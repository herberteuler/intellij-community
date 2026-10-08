// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor

import com.intellij.openapi.editor.ex.DocumentOp
import com.intellij.openapi.editor.ex.DocumentPatch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

internal class DocumentPatchTest {

  @Test
  fun `simple patch is printed without the origin and move fields`() {
    val patch = DocumentPatch.simple(
      startOffset = 1,
      endOffset = 2,
      newFragment = "x",
      newModStamp = 7L,
      clearLineFlags = false,
    )
    assertEquals(
      "DocumentPatch(startOffset=1, endOffset=2, newFragment.length=1, newModStamp=7, clearLineFlags=false)",
      patch.toString(),
    )
  }

  @Test
  fun `complex patch is printed with the fields differing from the applied range`() {
    val patch = DocumentPatch.complex(
      startOffset = 5,
      endOffset = 6,
      newFragment = "y",
      newModStamp = 8L,
      clearLineFlags = true,
      originStartOffset = 3,
      originEndOffset = 6, // same as endOffset, so it is omitted
      moveOffset = 4,
    )
    assertEquals(
      "DocumentPatch(startOffset=5, endOffset=6, newFragment.length=1" +
      ", originStartOffset=3, moveOffset=4, newModStamp=8, clearLineFlags=true)",
      patch.toString(),
    )
  }

  @Test
  fun `the op of a move half carries the move offset`() {
    val moveInsert = DocumentPatch.complex(
      startOffset = 4,
      endOffset = 4,
      newFragment = "01",
      newModStamp = 7L,
      clearLineFlags = false,
      originStartOffset = 4,
      originEndOffset = 4,
      moveOffset = 0,
    )
    assertEquals(DocumentOp.insertOp(4, "01", 0), moveInsert.ops().first())
    val moveDelete = DocumentPatch.complex(
      startOffset = 0,
      endOffset = 2,
      newFragment = "",
      newModStamp = 8L,
      clearLineFlags = false,
      originStartOffset = 0,
      originEndOffset = 2,
      moveOffset = 4,
    )
    assertEquals(DocumentOp.deleteOp(0, 2, 4), moveDelete.ops().first())
  }

  @Test
  fun `the ops of a replace move nothing, whatever move offset the patch carries`() {
    val replace = DocumentPatch.complex(
      startOffset = 1,
      endOffset = 2,
      newFragment = "x",
      newModStamp = 9L,
      clearLineFlags = false,
      originStartOffset = 1,
      originEndOffset = 2,
      moveOffset = 2,
    )
    val plainOps = listOf(DocumentOp.deleteOp(1, 1), DocumentOp.insertOp(1, "x"))
    assertEquals(plainOps, replace.ops().take(2))
  }

  @Test
  fun `patch freezes a mutable new fragment`() {
    val fragment = StringBuilder("new")
    val patch = DocumentPatch.simple(
      startOffset = 1,
      endOffset = 2,
      newFragment = fragment,
      newModStamp = 9L,
      clearLineFlags = false,
    )

    fragment.replace(0, fragment.length, "changed")

    assertEquals("new", patch.newFragment().toString())
  }
}

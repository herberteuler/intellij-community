// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown.reference

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.ElementManipulators
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.intellij.plugins.markdown.lang.psi.MarkdownPsiElementFactory
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownTestLink
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@TestApplication
internal class MarkdownTestLinkManipulatorTest {
  companion object {
    private val projectFixture = projectFixture()
  }

  @Test
  fun `renames a path segment and preserves the test description`() = timeoutRunBlocking {
    checkChange(TextRange(9, 19), "Renamed.kt", "../tests/Renamed.kt")
  }

  @Test
  fun `replaces the path and preserves the test description`() = timeoutRunBlocking {
    checkChange(TextRange(0, 19), "../../shared/Example.kt", "../../shared/Example.kt")
  }

  @Test
  fun `encodes renamed path segments`() = timeoutRunBlocking {
    val names = mapOf(
      "Example Test.kt" to "Example%20Test.kt",
      "Example  Test.kt" to "Example%20%20Test.kt",
      "Example%20Test.kt" to "Example%2520Test.kt",
      "Example+Test.kt" to "Example+Test.kt",
      "Example#Test?.kt" to "Example%23Test%3F.kt",
      "Example[Test].kt" to "Example%5BTest%5D.kt",
      "Example`Test.kt" to "Example%60Test.kt",
      "\u0422\u0435\u0441\u0442.kt" to "%D0%A2%D0%B5%D1%81%D1%82.kt",
    )
    for ((name, encodedName) in names) {
      checkChange(TextRange(9, 19), name, "../tests/$encodedName")
    }
  }

  @Test
  fun `encodes a full path with spaces and literal percent signs`() = timeoutRunBlocking {
    checkChange(TextRange(0, 19), "../test files/Example%20Test.kt", "../test%20files/Example%2520Test.kt")
  }

  @Test
  fun `preserves encoded segments during another rename`() = timeoutRunBlocking {
    checkChange(
      TextRange(16, 33), "Renamed Test.kt", "../test%20files/Renamed%20Test.kt", "../test%20files/Example%20Test.kt",
    )
  }

  @Test
  fun `encodes whitespace at the ends of a file name`() = timeoutRunBlocking {
    checkChange(TextRange(9, 19), " Example.kt ", "../tests/%20Example.kt%20")
  }

  private suspend fun checkChange(
    range: TextRange, replacement: String, expectedPath: String, originalPath: String = "../tests/Example.kt",
  ) {
    edtWriteAction {
      val file = MarkdownPsiElementFactory.createFile(projectFixture.get(), "[@test] $originalPath (`checks the result`)\n")
      val link = requireNotNull(PsiTreeUtil.findChildOfType(file, MarkdownTestLink::class.java))
      CommandProcessor.getInstance().runUndoTransparentAction {
        val changed = ElementManipulators.handleContentChange(link, range, replacement)
        assertEquals(expectedPath, changed.text)
      }
      assertEquals("[@test] $expectedPath (`checks the result`)\n", file.text)
    }
  }
}

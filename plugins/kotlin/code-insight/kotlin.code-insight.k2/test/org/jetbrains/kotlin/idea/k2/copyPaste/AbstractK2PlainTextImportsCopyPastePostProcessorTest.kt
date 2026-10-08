// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.copyPaste

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.util.io.FileUtil
import org.jetbrains.kotlin.idea.AbstractCopyPasteTest
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import java.io.File

abstract class AbstractK2PlainTextImportsCopyPastePostProcessorTest : AbstractCopyPasteTest() {
    override fun getProjectDescriptor() = KotlinWithJdkAndRuntimeLightProjectDescriptor.getInstance()

    fun doTest(@Suppress("UNUSED_PARAMETER") unused: String) {
        val testData = TestData.parse(dataFile())

        myFixture.configureByText("Copied.txt", testData.copiedText)
        myFixture.editor.selectionModel.setSelection(0, myFixture.editor.document.textLength)
        myFixture.performEditorAction(IdeActions.ACTION_COPY)

        myFixture.configureByText(testData.targetFileName, testData.targetText)
        myFixture.performEditorAction(IdeActions.ACTION_PASTE)

        assertEquals(testData.expectedText, myFixture.file.text)
    }

    private data class TestData(
        val targetFileName: String,
        val copiedText: String,
        val targetText: String,
        val expectedText: String,
    ) {
        companion object {
            private const val TARGET_FILE_NAME_DIRECTIVE = "// TARGET_FILE_NAME:"
            private const val COPIED_TEXT_DIRECTIVE = "// COPIED_TEXT"
            private const val TARGET_TEXT_DIRECTIVE = "// TARGET_TEXT"
            private const val EXPECTED_TEXT_DIRECTIVE = "// EXPECTED_TEXT"

            fun parse(file: File): TestData {
                val text = FileUtil.loadFile(file, true)
                val targetFileName = text.lines()
                    .first { it.startsWith(TARGET_FILE_NAME_DIRECTIVE) }
                    .removePrefix(TARGET_FILE_NAME_DIRECTIVE)
                    .trim()

                return TestData(
                    targetFileName,
                    text.section(COPIED_TEXT_DIRECTIVE, TARGET_TEXT_DIRECTIVE),
                    text.section(TARGET_TEXT_DIRECTIVE, EXPECTED_TEXT_DIRECTIVE),
                    text.section(EXPECTED_TEXT_DIRECTIVE, endDirective = null),
                )
            }

            private fun String.section(startDirective: String, endDirective: String?): String {
                val sectionStart = indexOf(startDirective).takeIf { it >= 0 } ?: error("Missing $startDirective")
                val contentStart = indexOf('\n', sectionStart).takeIf { it >= 0 }?.plus(1) ?: length
                val contentEnd = if (endDirective == null) {
                    length
                } else {
                    indexOf(endDirective, contentStart).takeIf { it >= 0 } ?: error("Missing $endDirective")
                }

                return substring(contentStart, contentEnd).trimLineBreaks()
            }

            private fun String.trimLineBreaks(): String = trim('\n', '\r')
        }
    }
}

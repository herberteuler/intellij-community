// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.copyPaste

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.codeInsight.editorActions.TextBlockTransferableData
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.idea.base.codeInsight.copyPaste.ReviewAddedImports.reviewAddedImports
import org.jetbrains.kotlin.idea.base.psi.imports.addImport
import org.jetbrains.kotlin.psi.KtCodeFragment
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.resolve.ImportPath
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException

internal object KotlinPlainTextImportsTransferableData : TextBlockTransferableData {
    override fun getFlavor(): DataFlavor = dataFlavor

    val dataFlavor: DataFlavor by lazy {
        val dataClass = KotlinPlainTextImportsTransferableData::class.java

        DataFlavor(
            DataFlavor.javaJVMLocalObjectMimeType + ";class=" + dataClass.name,
            dataClass.simpleName,
            dataClass.classLoader,
        )
    }
}

/**
 * Moves a leading import block from pasted plain text to the file import list.
 */
internal class KotlinPlainTextImportsCopyPastePostProcessor : CopyPastePostProcessor<KotlinPlainTextImportsTransferableData>() {
    override fun collectTransferableData(
        file: PsiFile,
        editor: Editor,
        startOffsets: IntArray,
        endOffsets: IntArray
    ): List<KotlinPlainTextImportsTransferableData> = emptyList()

    override fun extractTransferableData(content: Transferable): List<KotlinPlainTextImportsTransferableData> {
        if (CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE == CodeInsightSettings.NO) return emptyList()
        if (content.hasIdeSourceData()) return emptyList()
        if (!content.isDataFlavorSupported(DataFlavor.stringFlavor)) return emptyList()

        val pastedText = try {
            content.getTransferData(DataFlavor.stringFlavor) as? String
        } catch (_: UnsupportedFlavorException) {
            null
        } catch (_: IOException) {
            null
        }

        return if (pastedText == null || !pastedText.mayStartWithImportBlock()) {
            emptyList()
        } else {
            listOf(KotlinPlainTextImportsTransferableData)
        }
    }

    override fun processTransferableData(
        project: com.intellij.openapi.project.Project,
        editor: Editor,
        bounds: RangeMarker,
        caretOffset: Int,
        indented: Ref<in Boolean>,
        values: List<KotlinPlainTextImportsTransferableData>
    ) {
        if (values.singleOrNull() == null) return

        val document = editor.document
        val targetFile = PsiDocumentManager.getInstance(project).getPsiFile(document) as? KtFile ?: return
        if (targetFile is KtCodeFragment) return

        val pastedText = document.getText(bounds.textRange)
        val importBlock = parseLeadingImportBlock(project, pastedText) ?: return

        runWriteAction {
            val importedBefore = targetFile.importDirectives.mapNotNull { it.importPath }.toSet()
            document.replaceString(bounds.startOffset, bounds.endOffset, importBlock.textWithoutImports)
            PsiDocumentManager.getInstance(project).commitDocument(document)

            for ((fqName, isAllUnder, alias) in importBlock.imports) {
                targetFile.addImport(fqName, isAllUnder, alias)
            }

            val addedImports = importBlock.imports
                .filterNot { it in importedBefore }
                .map { it.fqName.asString() }
                .toSortedSet()

            reviewAddedImports(project, editor, targetFile, addedImports)
        }
    }

    private fun String.mayStartWithImportBlock(): Boolean {
        return lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trimStart()
            ?.startsWith("import ") == true
    }

    private fun Transferable.hasIdeSourceData(): Boolean =
        transferDataFlavors.any { flavor ->
            val flavorClassName = flavor.representationClass?.name ?: return@any false
            flavorClassName == "org.jetbrains.kotlin.j2k.copyPaste.ConvertJavaCopyPasteProcessor" ||
                    flavorClassName == "org.jetbrains.kotlin.j2k.copyPaste.CopiedKotlinCode" ||
                    flavor == KotlinReferenceTransferableData.dataFlavor
        }

    private fun parseLeadingImportBlock(
        project: com.intellij.openapi.project.Project,
        pastedText: String
    ): ImportBlock? {
        val importLines = mutableListOf<String>()
        var blockEndOffset = 0
        var offset = 0
        var hasImport = false

        while (offset < pastedText.length) {
            val nextLineStart = pastedText.indexOf('\n', offset).let {
                if (it == -1) pastedText.length else it + 1
            }
            val line = pastedText.substring(offset, nextLineStart)
            val trimmedLine = line.trim()

            when {
                trimmedLine.isEmpty() -> {
                    if (hasImport) blockEndOffset = nextLineStart
                }
                trimmedLine.startsWith("import ") -> {
                    hasImport = true
                    importLines += trimmedLine
                    blockEndOffset = nextLineStart
                }
                else -> break
            }

            offset = nextLineStart
        }

        if (!hasImport) return null

        val imports = KtPsiFactory(project)
            .createFile(importLines.joinToString(separator = "\n", postfix = "\nfun __pasteImportBlockSentinel() {}"))
            .importDirectives
            .mapNotNull { it.importPath }

        if (imports.size != importLines.size) return null

        return ImportBlock(imports, pastedText.substring(blockEndOffset))
    }

    private data class ImportBlock(
        val imports: List<ImportPath>,
        val textWithoutImports: String,
    )
}

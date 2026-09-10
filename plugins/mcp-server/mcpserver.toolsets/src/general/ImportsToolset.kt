// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("FunctionName", "unused")
@file:OptIn(ExperimentalSerializationApi::class)

package com.intellij.mcpserver.toolsets.general

import com.intellij.mcpserver.McpServerBundle
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue.FALSE
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.imports.McpImportCandidate
import com.intellij.mcpserver.imports.McpMissingImport
import com.intellij.mcpserver.imports.McpMissingImportProvider
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.mcpserver.reportToolActivity
import com.intellij.mcpserver.toolsets.Constants
import com.intellij.mcpserver.util.awaitExternalChangesAndIndexing
import com.intellij.mcpserver.util.checkIndexingInProgress
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

private const val MODE_DIFF: String = "diff"
private const val MODE_APPLY: String = "apply"
private const val AMBIGUITY_REPORT: String = "report"
private const val AMBIGUITY_BEST: String = "best"

private const val REASON_SYMBOL_NOT_FOUND: String = "symbol_not_found"
private const val REASON_FIX_NOT_APPLIED: String = "fix_not_applied"

/** The maximum number of diff lines per call, so a large batch cannot flood the context of the agent. */
private const val MAX_IMPORT_DIFF_LINES: Int = 200

class ImportsToolset : McpToolset {
  /**
   * Hides the tool in an IDE where no language adds imports.
   *
   * The tool itself knows no language. It works only through [McpMissingImportProvider], and today
   * only the Java and the Kotlin plugin register one. GoLand and Rider would therefore get a tool
   * that refuses every file. A product turns the tool on by adding a provider, and needs no change
   * here.
   */
  override fun isEnabled(): Boolean = McpMissingImportProvider.EP.hasAnyExtensions()

  override fun isExperimental(): Boolean = true

  override fun displayName(): String = McpServerBundle.message("toolset.display.name.imports")

  override fun displayDescription(toolName: String): String = McpServerBundle.message("tool.description.$toolName")

  @McpToolHints(destructiveHint = FALSE, openWorldHint = FALSE)
  @McpTool
  @McpDescription("""
        |Adds the imports that the specified Java and Kotlin files are missing, with the code insight of the IDE.
        |Write the code first and leave the imports out, then call this tool. It resolves a short name the way the editor does, so it also finds an extension function, a top-level function, and a typealias.
        |A Java static method or a Java static field gets an `import static`. `staticMember: true` marks such a candidate, so an entry of `ambiguous` tells you which form to write.
        |`mode` defaults to `$MODE_APPLY`: the tool writes the files and saves them, so read such a file again before you edit it. `mode: $MODE_DIFF` only previews the change and writes nothing.
        |`ambiguity` defaults to `$AMBIGUITY_REPORT`: a name with several candidates goes to `ambiguous` and gets no import, so pick a candidate by the context of your task. `ambiguity: $AMBIGUITY_BEST` takes the first candidate of the IDE.
        |`optimize` defaults to false, so every name gets its own import. `optimize: true` then runs Optimize Imports with the style of the project, which can join several imports of one package into one import on demand, and also removes the unused imports and sorts the rest. Ask for it only when you want the file to match the style of the project.
        |`diff` shows every import that the file gained. A name that the file already resolves is in no list.
        |`unresolved` holds a name that the tool did not import. `$REASON_SYMBOL_NOT_FOUND` means that the module of the file sees no declaration of the name, so check that you did not invent it, or that the module has the dependency. `$REASON_FIX_NOT_APPLIED` means the IDE refused the change, so add that one import yourself.
        |A file entry with a `notAnalyzedReason` was skipped. Lines are 1-based: in `$MODE_DIFF` they point at the file on the disk, in `$MODE_APPLY` at the file after the write.
        |The diff stops after $MAX_IMPORT_DIFF_LINES lines and says how much it left out. `timedOut: true` means that the remaining files are missing from `items`.
    """)
  suspend fun add_missing_imports(
    @McpDescription("${Constants.RELATIVE_PATH_IN_PROJECT_DESCRIPTION}. Only a `.java` or a `.kt` file. Duplicate paths are ignored after normalization.")
    files: List<String>,
    @McpDescription("`$MODE_APPLY` (default) writes the files and saves them. `$MODE_DIFF` returns the change and writes nothing.")
    mode: String = MODE_APPLY,
    @McpDescription(
      "`$AMBIGUITY_REPORT` (default) reports a name with several candidates and imports nothing for it. " +
      "`$AMBIGUITY_BEST` imports the first candidate of the IDE."
    )
    ambiguity: String = AMBIGUITY_REPORT,
    @McpDescription("Whether to run Optimize Imports with the style of the project after the imports are added (default: false). It can join imports into an import on demand, and it removes the unused imports.")
    optimize: Boolean = false,
    @McpDescription("${Constants.TIMEOUT_MILLISECONDS_DESCRIPTION} (default: ${Constants.LONG_TIMEOUT_MILLISECONDS_VALUE})")
    timeout: Int = Constants.LONG_TIMEOUT_MILLISECONDS_VALUE,
  ): AddMissingImportsResult {
    val apply = when (mode) {
      MODE_DIFF -> false
      MODE_APPLY -> true
      else -> mcpFail("mode must be `$MODE_DIFF` or `$MODE_APPLY`, got `$mode`")
    }
    val takeBestCandidate = when (ambiguity) {
      AMBIGUITY_REPORT -> false
      AMBIGUITY_BEST -> true
      else -> mcpFail("ambiguity must be `$AMBIGUITY_REPORT` or `$AMBIGUITY_BEST`, got `$ambiguity`")
    }

    val context = currentCoroutineContext()
    val project = context.project
    val requestedFiles = prepareRequestedImportFiles(project, files)
    context.reportToolActivity(McpServerBundle.message("tool.activity.adding.missing.imports", requestedFiles.size))
    // The tool needs the indexes, and no annotation declares that. These two helpers are the whole
    // mechanism: the wait stays outside the timeout, so it does not eat the budget of the work
    // itself, and checkIndexingInProgress marks the answer when indexing started anyway.
    awaitExternalChangesAndIndexing(project)

    val items = ArrayList<ImportsFileResult>(requestedFiles.size)
    val (completedInTime, partialResultReason) = checkIndexingInProgress(project) {
      withTimeoutOrNull(timeout.milliseconds) {
        for (file in requestedFiles) {
          items.add(addMissingImports(project, file, takeBestCandidate, apply, optimize))
        }
      } != null
    }
    return AddMissingImportsResult(
      items = items,
      timedOut = !completedInTime,
      partialResultReason = partialResultReason,
    )
  }

  @Serializable
  data class AddMissingImportsResult(
    @property:McpDescription("One entry per requested file, in the order of the request.")
    @EncodeDefault(mode = EncodeDefault.Mode.ALWAYS)
    val items: List<ImportsFileResult> = emptyList(),
    @property:McpDescription(Constants.TIMED_OUT_DESCRIPTION)
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    val timedOut: Boolean? = false,
    @property:McpDescription("Set when the results may be incomplete, for example because the project was indexing.")
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    val partialResultReason: String? = null,
  )

  @Serializable
  data class ImportsFileResult(
    @property:McpDescription("The path of the file, as the request spelled it.")
    val filePath: String,
    @property:McpDescription("Set when the file was skipped. The other fields are then empty.")
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    val notAnalyzedReason: String? = null,
    @property:McpDescription("Unified diff of every import that the file gained, including a Java `import static`. Absent when nothing changed.")
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    val diff: String? = null,
    @property:McpDescription("Whether the tool wrote the file to the disk. False in the `diff` mode.")
    @EncodeDefault(mode = EncodeDefault.Mode.ALWAYS)
    val applied: Boolean = false,
    @property:McpDescription("The names with several candidates. The tool imported none of them.")
    @EncodeDefault(mode = EncodeDefault.Mode.ALWAYS)
    val ambiguous: List<AmbiguousImport> = emptyList(),
    @property:McpDescription("The names that no import can fix.")
    @EncodeDefault(mode = EncodeDefault.Mode.ALWAYS)
    val unresolved: List<UnresolvedName> = emptyList(),
  )

  @Serializable
  data class AmbiguousImport(
    @property:McpDescription("The short name as the code spells it.")
    @JvmField val shortName: String,
    @property:McpDescription("The 1-based line of the first reference to the name.")
    @JvmField val line: Int,
    @property:McpDescription("The text of that line without its indent, so the use of the name tells which candidate fits.")
    @JvmField val lineText: String,
    @property:McpDescription("Every import that fixes the name, best guess of the IDE first.")
    @JvmField val candidates: List<ImportCandidate>,
  )

  @Serializable
  data class ImportCandidate(
    @property:McpDescription("The fully qualified name to import. For a static member, the class and the member.")
    @JvmField val fqName: String,
    @property:McpDescription("The module or the library that holds the declaration.")
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    @JvmField val source: String? = null,
    @property:McpDescription("Whether the declaration is deprecated.")
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    @JvmField val deprecated: Boolean = false,
    @property:McpDescription("Whether the import is a Java `import static` of a method or a field.")
    @EncodeDefault(mode = EncodeDefault.Mode.NEVER)
    @JvmField val staticMember: Boolean = false,
  )

  @Serializable
  data class UnresolvedName(
    @property:McpDescription("The short name as the code spells it.")
    @JvmField val shortName: String,
    @property:McpDescription("The 1-based line of the first reference to the name.")
    @JvmField val line: Int,
    @property:McpDescription("`$REASON_SYMBOL_NOT_FOUND` or `$REASON_FIX_NOT_APPLIED`.")
    @JvmField val reason: String,
  )
}

private class RequestedImportFile(
  @JvmField val path: String,
  @JvmField val virtualFile: VirtualFile,
)

/** The names that the change left unresolved, in the two lists of the answer. */
private class ImportReport(
  @JvmField val ambiguous: List<ImportsToolset.AmbiguousImport>,
  @JvmField val unresolved: List<ImportsToolset.UnresolvedName>,
)

private sealed interface FileAnalysis {
  class Skipped(@JvmField val reason: String) : FileAnalysis

  data class Planned(
    val document: Document,
    val textBefore: String,
    val textAfter: String,
    val psiModificationCount: Long,
    val psiFile: PsiFile,
    val report: ImportReport,
  ) : FileAnalysis
}

private fun analyzeFile(project: Project, virtualFile: VirtualFile, takeBestCandidate: Boolean, optimize: Boolean): FileAnalysis {
  val document = FileDocumentManager.getInstance().getDocument(virtualFile)
                 ?: return FileAnalysis.Skipped("The file has no text content.")
  val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
                ?: return FileAnalysis.Skipped("The file is not a part of the project.")
  val provider = McpMissingImportProvider.EP.forLanguage(psiFile.language)
                 ?: return FileAnalysis.Skipped(unsupportedLanguageReason(psiFile))
  val change = provider.addMissingImports(psiFile, takeBestCandidate, optimize)
               ?: return FileAnalysis.Skipped(unsupportedLanguageReason(psiFile))
  val textBefore = document.text
  val update = change.command.unpack().filterIsInstance<ModUpdateFileText>().firstOrNull()
  val remainingNames = if (update == null) change.missingImports.mapTo(HashSet()) { it.shortName } else change.remainingNames
  val report = reportRemainingNames(change.missingImports, remainingNames, document, takeBestCandidate)
  val psiModificationCount = PsiModificationTracker.getInstance(project).modificationCount
  return FileAnalysis.Planned(document, textBefore, update?.newText ?: textBefore, psiModificationCount, psiFile, report)
}

private suspend fun addMissingImports(
  project: Project,
  file: RequestedImportFile,
  takeBestCandidate: Boolean,
  apply: Boolean,
  optimize: Boolean,
): ImportsToolset.ImportsFileResult {
  // One read action, so the report and the change come from one state of the file.
  val analysis = readAction { analyzeFile(project, file.virtualFile, takeBestCandidate, optimize) }
  if (analysis is FileAnalysis.Skipped) return notAnalyzed(file, analysis.reason)
  val (document, textBefore, textAfter, psiModificationCount, psiFile, report) = analysis as FileAnalysis.Planned

  // writeImports drops the change when the document no longer holds textBefore.
  val applied = apply && textAfter != textBefore &&
                writeImports(project, psiFile, document, textBefore, psiModificationCount, textAfter, file.path)
  // Every reference sits under the import block, so one shift moves every line of the written file.
  val lineShift = if (applied) textAfter.lineCount() - textBefore.lineCount() else 0
  return ImportsToolset.ImportsFileResult(
    filePath = file.path,
    diff = buildUnifiedDiff(listOf(ChangedFileText(file.path, textBefore, textAfter)), MAX_IMPORT_DIFF_LINES),
    applied = applied,
    ambiguous = report.ambiguous.map { it.shifted(lineShift) },
    unresolved = report.unresolved.map { it.shifted(lineShift) },
  )
}

private fun String.lineCount(): Int = count { it == '\n' } + 1

private fun ImportsToolset.AmbiguousImport.shifted(lineShift: Int): ImportsToolset.AmbiguousImport =
  if (lineShift == 0) this else copy(line = line + lineShift)

private fun ImportsToolset.UnresolvedName.shifted(lineShift: Int): ImportsToolset.UnresolvedName =
  if (lineShift == 0) this else copy(line = line + lineShift)

private fun notAnalyzed(file: RequestedImportFile, reason: String): ImportsToolset.ImportsFileResult =
  ImportsToolset.ImportsFileResult(filePath = file.path, notAnalyzedReason = reason)

/** Names the language of the file, so the reason stays true when another language adds a provider. */
private fun unsupportedLanguageReason(psiFile: PsiFile): String =
  "The tool adds no import for ${psiFile.language.displayName}. It covers Java and Kotlin."

/**
 * Sorts the names that the change left unresolved into the lists of the answer.
 *
 * The provider already imported what it could, so a name that resolves now is in no list. A name
 * that is still missing had no candidate, several candidates, or one that the fix did not import.
 */
private fun reportRemainingNames(
  missingImports: List<McpMissingImport>,
  remainingNames: Set<String>,
  document: Document,
  takeBestCandidate: Boolean,
): ImportReport {
  val ambiguous = ArrayList<ImportsToolset.AmbiguousImport>()
  val unresolved = ArrayList<ImportsToolset.UnresolvedName>()
  for (missingImport in missingImports) {
    if (missingImport.shortName !in remainingNames) continue
    val line = document.lineOf(missingImport.offset)
    val candidates = missingImport.candidates
    when {
      candidates.isEmpty() -> unresolved.add(ImportsToolset.UnresolvedName(missingImport.shortName, line, REASON_SYMBOL_NOT_FOUND))
      candidates.size > 1 && !takeBestCandidate ->
        ambiguous.add(ImportsToolset.AmbiguousImport(missingImport.shortName, line, document.lineTextOf(line), candidates.map { it.toDto() }))
      else -> unresolved.add(ImportsToolset.UnresolvedName(missingImport.shortName, line, REASON_FIX_NOT_APPLIED))
    }
  }
  return ImportReport(ambiguous, unresolved)
}

private fun McpImportCandidate.toDto(): ImportsToolset.ImportCandidate =
  ImportsToolset.ImportCandidate(fqName = fqName, source = source, deprecated = deprecated, staticMember = staticMember)

private fun Document.lineOf(offset: Int): Int = if (offset in 0..textLength) getLineNumber(offset) + 1 else 1

/** Returns the text of the 1-based [line] without its indent. */
private fun Document.lineTextOf(line: Int): String {
  val index = line - 1
  if (index !in 0 until lineCount) return ""
  return charsSequence.subSequence(getLineStartOffset(index), getLineEndOffset(index)).toString().trim()
}

/**
 * Writes [textAfter] to the file and saves it. Returns false when the file changed under the tool.
 *
 * The write goes through [ModUpdateFileText], so the platform computes the smallest ranges to
 * replace and puts one entry into the undo history. The command also carries [textBefore], which
 * makes the platform drop the change when the document no longer holds it.
 */
private suspend fun writeImports(
  project: Project,
  psiFile: PsiFile,
  document: Document,
  textBefore: String,
  psiModificationCount: Long,
  textAfter: String,
  path: String,
): Boolean {
  val virtualFile = psiFile.virtualFile ?: return false
  val command = ModUpdateFileText(virtualFile, textBefore, textAfter, emptyList())
  val context = readAction { ActionContext.from(null, psiFile) }
  val commandName: @NlsContexts.Command String = McpServerBundle.message("command.action.add.missing.imports", path)
  val unchanged = withContext(Dispatchers.EDT) {
    // No write can start between this check and the command, because both run on the EDT.
    if (PsiModificationTracker.getInstance(project).modificationCount != psiModificationCount) return@withContext false
    CommandProcessor.getInstance().executeCommand(
      project,
      { ModCommandExecutor.getInstance().executeInBatch(context, command) },
      commandName,
      null,
    )
    true
  }
  if (!unchanged) return false

  // executeInBatch applies the ranges and commits the document itself, so only the result is read here.
  val written = readAction { document.text == textAfter }
  if (!written) return false

  // The agent reads from the disk, so an unsaved document would look like a file that did not change.
  FileDocumentManager.getInstance().saveDocument(document)
  return true
}

private fun prepareRequestedImportFiles(project: Project, files: List<String>): List<RequestedImportFile> {
  if (files.isEmpty()) {
    mcpFail("files must contain at least one path")
  }

  val requestedFiles = LinkedHashMap<Path, String>()
  for (rawPath in files) {
    val path = rawPath.trim().ifEmpty { mcpFail("files must not contain blank paths") }
    requestedFiles.putIfAbsent(resolveExistingRegularFileInProject(project = project, pathInProject = path), path)
  }

  val localFileSystem = LocalFileSystem.getInstance()
  return requestedFiles.map { (resolvedPath, path) ->
    val virtualFile = localFileSystem.refreshAndFindFileByNioFile(resolvedPath)
                      ?: mcpFail("File $resolvedPath doesn't exist or can't be opened")
    RequestedImportFile(path, virtualFile)
  }
}

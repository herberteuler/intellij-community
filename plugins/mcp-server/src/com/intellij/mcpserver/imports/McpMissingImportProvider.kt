// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.mcpserver.imports

import com.intellij.lang.LanguageExtension
import com.intellij.modcommand.ModCommand
import com.intellij.psi.PsiFile
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.annotations.ApiStatus

/**
 * Finds the imports that a file needs, and adds them.
 *
 * A language plugin registers one provider per language. The MCP tool `add_missing_imports` picks the
 * provider by the language of the file. The provider does the work of the editor quick fix, but it
 * takes no editor and no caret.
 */
@ApiStatus.Internal
interface McpMissingImportProvider {
  /**
   * Finds the names of [file] that do not resolve, and builds one change that imports every name
   * it can. Returns null when [file] is not of the language.
   */
  @RequiresReadLock
  fun addMissingImports(file: PsiFile, takeBestCandidate: Boolean, optimize: Boolean): McpImportChange?

  companion object {
    @JvmField
    val EP: LanguageExtension<McpMissingImportProvider> = LanguageExtension("com.intellij.mcpServer.missingImportProvider")
  }
}

/**
 * What [McpMissingImportProvider.addMissingImports] found and did.
 *
 * [missingImports] describes the file as it came in, so every offset points into that file.
 * [command] is the change of the file (only the first ModUpdateFileText is applied).
 * [remainingNames] holds the short names that still do not resolve after the change.
 */
@ApiStatus.Internal
class McpImportChange(
  @JvmField val missingImports: List<McpMissingImport>,
  @JvmField val command: ModCommand,
  @JvmField val remainingNames: Set<String>,
)

/**
 * One name in a file that does not resolve.
 *
 * [candidates] is empty when no import can fix the name.
 */
@ApiStatus.Internal
class McpMissingImport(
  @JvmField val shortName: String,
  @JvmField val offset: Int,
  @JvmField val candidates: List<McpImportCandidate>,
)

/**
 * One import that fixes a name.
 *
 * [fqName] is the name that goes into the import statement. For a static member it holds the class
 * and the member, as in `java.util.Arrays.asList`.
 *
 * [source] names the module or the library that holds the declaration. It is null when the provider
 * cannot tell.
 *
 * [staticMember] marks a Java `import static`. Kotlin imports a callable with a plain import, so a
 * Kotlin candidate keeps it false.
 */
@ApiStatus.Internal
class McpImportCandidate(
  @JvmField val fqName: String,
  @JvmField val source: String? = null,
  @JvmField val deprecated: Boolean = false,
  @JvmField val staticMember: Boolean = false,
)

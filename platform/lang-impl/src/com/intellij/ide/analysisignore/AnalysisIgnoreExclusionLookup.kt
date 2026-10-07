// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.analysisignore

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.backend.workspace.virtualFile
import com.intellij.platform.workspace.storage.EntityStorage
import org.jetbrains.annotations.ApiStatus

/**
 * One line of a [`.analysisignore`][ANALYSIS_IGNORE_FILE_NAME] file that excludes a file.
 */
@ApiStatus.Internal
class AnalysisIgnoreExclusion(
  val baseDir: VirtualFile,
  val pattern: AnalysisIgnorePattern,
  val excludedFile: VirtualFile,
) {
  /** The `.analysisignore` file that holds [pattern], or `null`. */
  val ignoreFile: VirtualFile?
    get() = baseDir.findChild(ANALYSIS_IGNORE_FILE_NAME)?.takeUnless { it.isDirectory }
}

/**
 * Returns every line of a `.analysisignore` file that excludes [file]. A line that matches [file] itself comes first. A default entity has
 * no file to edit, and thus its lines are not in the result. See [findDefaultExclusions] for them.
 */
@ApiStatus.Internal
fun findAnalysisIgnoreExclusions(project: Project, file: VirtualFile): List<AnalysisIgnoreExclusion> =
  findExclusions(project, file) { it.fileEntities().toList() }

/**
 * Returns every line of a [default entity][AnalysisIgnoreDefaultEntitySource] that excludes [file]. The
 * [baseDir][AnalysisIgnoreExclusion.baseDir] of a result is a project root without a `.analysisignore` file, and thus the result has no
 * [ignoreFile][AnalysisIgnoreExclusion.ignoreFile]. A line that matches [file] itself comes first. The result is empty while the defaults
 * are off, because no default entity exists then.
 */
@ApiStatus.Internal
fun findDefaultExclusions(project: Project, file: VirtualFile): List<AnalysisIgnoreExclusion> =
  findExclusions(project, file) { it.defaultEntities() }

private fun findExclusions(
  project: Project,
  file: VirtualFile,
  entitiesOf: (EntityStorage) -> List<AnalysisIgnoreEntity>,
): List<AnalysisIgnoreExclusion> {
  val entities = entitiesOf(WorkspaceModel.getInstance(project).currentSnapshot)
  if (entities.isEmpty() || !isExcluded(project, file)) return emptyList()

  val result = ArrayList<AnalysisIgnoreExclusion>()
  for (entity in entities) {
    val baseDir = entity.baseDir.virtualFile ?: continue
    collectExclusions(baseDir, entity.patterns, file, result)
  }
  return result.sortedDeepestFirst()
}

/** Adds to [result] each pattern of [lines] in [baseDir] that matches [file] or a directory between [baseDir] and [file]. */
private fun collectExclusions(baseDir: VirtualFile, lines: List<String>, file: VirtualFile, result: MutableList<AnalysisIgnoreExclusion>) {
  if (!VfsUtilCore.isAncestor(baseDir, file, true)) return

  val patterns = AnalysisIgnorePattern.compileAll(lines, baseDir.isCaseSensitive)
  var current = file
  while (current != baseDir) {
    val relativePath = VfsUtilCore.getRelativePath(current, baseDir, '/') ?: break
    for (pattern in patterns) {
      if (pattern.matches(relativePath, current.name, current.isDirectory)) {
        result.add(AnalysisIgnoreExclusion(baseDir, pattern, current))
      }
    }
    current = current.parent ?: break
  }
}

private fun isExcluded(project: Project, file: VirtualFile): Boolean =
  runReadActionBlocking { ProjectFileIndex.getInstance(project).isExcluded(file) }

/** The files of one branch: a longer path lies deeper. The sort is stable, and thus the lines of one file keep their order. */
private fun List<AnalysisIgnoreExclusion>.sortedDeepestFirst(): List<AnalysisIgnoreExclusion> =
  sortedByDescending { it.excludedFile.path.length }

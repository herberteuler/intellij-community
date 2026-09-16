// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil
import git4idea.index.getStatus
import git4idea.index.isAdded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jdom.Element
import org.jdom.JDOMException
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Copies local `.idea`/`.run` configuration into a freshly created worktree, driven entirely by the source
 * repository's `.worktreeinclude` file.
 *
 * A file counts as local only when git reports it as not yet part of a commit in the source worktree:
 * untracked, ignored, or staged but never committed. A file already present at the target path is always
 * left untouched.
 */
@Service(Service.Level.PROJECT)
internal class GitWorktreeProjectConfigService(private val project: Project) {
  companion object {
    private val LOG = logger<GitWorktreeProjectConfigService>()

    private const val IDEA_DIR_NAME = ".idea"
    private const val WORKSPACE_FILE_NAME = "workspace.xml"
    private const val PROJECT_ID_COMPONENT_NAME = "ProjectId"

    fun getInstance(project: Project): GitWorktreeProjectConfigService =
      project.getService(GitWorktreeProjectConfigService::class.java)

    /** Removes the first `<component name="[name]">` from [root], if present. */
    internal fun removeComponent(root: Element, name: String) {
      root.getChildren("component").firstOrNull { it.getAttributeValue("name") == name }?.let { root.removeContent(it) }
    }

    /**
     * Removes the `ProjectId` component from [xmlText], a `.idea/workspace.xml` file's content. Returns `null`
     * when the file has no `ProjectId` component, or fails to parse as XML, so the caller can tell "no change"
     * apart from "changed".
     */
    private fun removeProjectIdComponent(xmlText: String): String? {
      val root = try {
        JDOMUtil.load(StringReader(xmlText))
      }
      catch (e: JDOMException) {
        LOG.warn("Failed to parse workspace.xml while stripping $PROJECT_ID_COMPONENT_NAME", e)
        return null
      }
      catch (e: IOException) {
        LOG.warn("Failed to read workspace.xml while stripping $PROJECT_ID_COMPONENT_NAME", e)
        return null
      }
      val projectIdComponent = root.getChildren("component").firstOrNull { it.getAttributeValue("name") == PROJECT_ID_COMPONENT_NAME }
                                ?: return null
      root.removeContent(projectIdComponent)
      val writer = StringWriter()
      JDOMUtil.write(root, writer, "\n")
      return writer.toString()
    }

    /**
     * Replaces every occurrence of [sourceRootString] in [text] with [targetRootString]. Returns `null` when
     * [text] has no occurrence, so the caller can tell "no change" apart from "changed".
     *
     * Plain text, not XML-aware: a value already stored relative to `$PROJECT_DIR$` needs no change, since it
     * resolves correctly once the new project opens at the new root, so this only catches a literal absolute
     * path that leaked in unmacroed, such as a working directory or an env-file path.
     *
     * A boundary check keeps a match from firing on a path that merely starts with [sourceRootString]: a match
     * is replaced only when the character right after it could not continue the same file or directory name. A
     * JSON- or XML-escaped value can end a path in a quote, a comma, or another punctuation mark instead of a
     * path separator, so this checks for a name character instead of allow-listing `/` and `\`.
     */
    internal fun remapAbsolutePaths(text: String, sourceRootString: String, targetRootString: String): String? {
      if (sourceRootString.isEmpty() || sourceRootString == targetRootString) return null
      val result = StringBuilder()
      var searchFrom = 0
      var changed = false
      while (true) {
        val index = text.indexOf(sourceRootString, searchFrom)
        if (index < 0) {
          result.append(text, searchFrom, text.length)
          break
        }
        val matchEnd = index + sourceRootString.length
        result.append(text, searchFrom, index)
        if (matchEnd >= text.length || !continuesPathName(text[matchEnd])) {
          result.append(targetRootString)
          changed = true
        }
        else {
          result.append(sourceRootString)
        }
        searchFrom = matchEnd
      }
      return if (changed) result.toString() else null
    }

    /**
     * `true` for a character that could continue the same file or directory name, so a match ending right
     * before it is not a real path boundary.
     */
    private fun continuesPathName(c: Char): Boolean = c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == ' '

    /**
     * Copies [source] to [target], following a symlink to its resolved content. Returns `false` on failure,
     * including a dangling symlink, instead of throwing. IntelliJ's Eel layer routes this NIO call to the
     * right file system, so the same code works on Windows, in WSL, and in a Docker container.
     */
    internal fun copyConfigFile(source: Path, target: Path): Boolean {
      return try {
        Files.createDirectories(target.parent)
        Files.copy(source, target)
        true
      }
      catch (e: NoSuchFileException) {
        LOG.warn("Failed to copy $source to $target, a dangling symlink or missing source", e)
        false
      }
      catch (e: IOException) {
        LOG.warn("Failed to copy $source to $target", e)
        false
      }
    }

    private fun readConfigFile(path: Path): String? {
      if (!path.isRegularFile()) return null
      return try {
        Files.readString(path)
      }
      catch (e: IOException) {
        LOG.warn("Failed to read $path", e)
        null
      }
    }

    private fun writeConfigFile(text: String, path: Path): Boolean {
      return try {
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
        true
      }
      catch (e: IOException) {
        LOG.warn("Failed to write $path", e)
        false
      }
    }
  }

  /**
   * Returns the subset of [files] that git reports as not yet part of a commit in [root]: untracked, ignored,
   * or staged but never committed. A staged-but-uncommitted file has no content in any commit yet, so the new
   * worktree would not have it either unless this service copies it.
   */
  private fun findLocalFiles(root: VirtualFile, files: List<Path>): Set<Path> {
    val existingFiles = files.filter { it.isRegularFile() }
    if (existingFiles.isEmpty()) return emptySet()

    return try {
      val filePathsByAbsolutePath = existingFiles.associateBy { it.toString() }
      val filePaths = existingFiles.map { VcsUtil.getFilePath(it.toString(), false) }
      val statuses = getStatus(project, root, filePaths, withRenames = false, withUntracked = true, withIgnored = true,
                               expandIgnoredDirectories = true)
      statuses.asSequence()
        .filter { !it.isTracked() || isAdded(it.index) }
        .mapNotNull { filePathsByAbsolutePath[it.path.path] }
        .toSet()
    }
    catch (e: VcsException) {
      LOG.warn("Failed to read the git status of the local project configuration in $root", e)
      emptySet()
    }
  }

  /**
   * Returns `true` when [sourceRoot]'s `.idea/workspace.xml` is already committed to git. Reuses
   * [findLocalFiles] (git-status-based), not `git ls-files --cached`, since the latter cannot tell a
   * committed file apart from one that is staged but never committed.
   */
  internal suspend fun isIdeaConfigCommittedToGit(sourceRoot: VirtualFile): Boolean = withContext(Dispatchers.IO) {
    val workspaceXml = sourceRoot.toNioPath().resolve(IDEA_DIR_NAME).resolve(WORKSPACE_FILE_NAME)
    workspaceXml.isRegularFile() && findLocalFiles(sourceRoot, listOf(workspaceXml)).isEmpty()
  }

  /**
   * Copies every local file under [sourceRoot] that matches a pattern in its `.worktreeinclude` file, then
   * cleans up the freshly copied idea settings so the new worktree does not inherit a stale project identity
   * or an absolute path pointing back at [sourceRoot]. Returns the target paths this run failed to write.
   */
  suspend fun copyWorktreeIncludeFiles(sourceRoot: VirtualFile, targetWorktreeDir: Path): List<Path> {
    val worktreeIncludeFile = sourceRoot.toNioPath().resolve(WORKTREE_INCLUDE_FILE_NAME)
    if (!worktreeIncludeFile.isRegularFile()) return emptyList()

    return withContext(Dispatchers.IO) {
      val matches = try {
        GitWorktreeIncludeFileService.findWorktreeIncludeMatches(project, sourceRoot, worktreeIncludeFile)
      }
      catch (e: VcsException) {
        LOG.warn("Failed to read .worktreeinclude matches in $sourceRoot", e)
        return@withContext emptyList()
      }

      val sourceRootPath = sourceRoot.toNioPath()
      val localFiles = findLocalFiles(sourceRoot, matches)
      val copiedFiles = mutableListOf<Path>()
      val failedFiles = mutableListOf<Path>()
      for (sourceFile in localFiles) {
        val targetFile = targetWorktreeDir.resolve(sourceRootPath.relativize(sourceFile))
        if (targetFile.exists()) continue
        if (copyConfigFile(sourceFile, targetFile)) copiedFiles.add(targetFile) else failedFiles.add(targetFile)
      }
      failedFiles + cleanUpCopiedProjectFiles(copiedFiles, sourceRootPath, targetWorktreeDir)
    }
  }

  /**
   * Rewrites every freshly copied `.idea`/`.run` XML or `.iml` file in [copiedFiles]: remaps an absolute path
   * still pointing at [sourceRootPath] to the same place under [targetWorktreeDir], and, for
   * `.idea/workspace.xml` specifically, removes the `ProjectId` component. A newly created worktree needs its
   * own project identity; keeping the source's would make the IDE treat the two projects as one. Returns the
   * target paths this failed to write.
   */
  private fun cleanUpCopiedProjectFiles(copiedFiles: List<Path>, sourceRootPath: Path, targetWorktreeDir: Path): List<Path> {
    val sourceRootString = sourceRootPath.toString()
    val targetRootString = targetWorktreeDir.toString()
    val failedFiles = mutableListOf<Path>()
    for (targetFile in copiedFiles) {
      if (!isCleanupCandidate(targetFile, targetWorktreeDir)) continue
      val originalText = readConfigFile(targetFile) ?: continue

      var text = originalText
      remapAbsolutePaths(text, sourceRootString, targetRootString)?.let { text = it }
      if (isWorkspaceXml(targetFile, targetWorktreeDir)) {
        removeProjectIdComponent(text)?.let { text = it }
      }

      if (text != originalText && !writeConfigFile(text, targetFile)) {
        failedFiles.add(targetFile)
      }
    }
    return failedFiles
  }

  private fun isCleanupCandidate(targetFile: Path, targetWorktreeDir: Path): Boolean {
    val relative = targetWorktreeDir.relativize(targetFile).toString()
    val underIdeaOrRun = relative.startsWith("$IDEA_DIR_NAME${targetFile.fileSystem.separator}") ||
      relative.startsWith(".run${targetFile.fileSystem.separator}")
    if (!underIdeaOrRun) return false
    val fileName = targetFile.fileName.toString()
    return fileName.endsWith(".xml") || fileName.endsWith(".iml")
  }

  private fun isWorkspaceXml(targetFile: Path, targetWorktreeDir: Path): Boolean =
    targetFile == targetWorktreeDir.resolve(IDEA_DIR_NAME).resolve(WORKSPACE_FILE_NAME)
}

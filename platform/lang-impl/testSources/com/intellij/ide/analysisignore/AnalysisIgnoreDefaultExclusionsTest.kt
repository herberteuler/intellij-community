// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.analysisignore

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.RegistryKey
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndex
import com.intellij.workspaceModel.ide.OptionalExclusionUtil
import com.intellij.workspaceModel.ide.registerProjectRoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val ENABLED = "ide.analysisignore.file.enabled"

private const val DEFAULTS = "ide.analysisignore.defaults.enabled"

/**
 * The [default entity][AnalysisIgnoreDefaultEntitySource] of a project root without a `.analysisignore` file, and how a file in the root
 * directory removes it.
 */
@TestApplication
class AnalysisIgnoreDefaultExclusionsTest {
  @JvmField
  @RegisterExtension
  val projectModel: ProjectModelExtension = ProjectModelExtension()

  private val project get() = projectModel.project
  private val fileIndex get() = WorkspaceFileIndex.getInstance(project)
  private val service get() = AnalysisIgnoreService.getInstance(project)

  private lateinit var module: Module
  private lateinit var projectRoot: VirtualFile

  @BeforeEach
  fun setUp() {
    projectRoot = projectModel.baseProjectDir.newVirtualDirectory("projectRoot")
    module = projectModel.createModule()
    ModuleRootModificationUtil.addContentRoot(module, projectRoot)
    runBlocking { registerProjectRoot(project, urlOf(projectRoot)) }
    service.syncDefaultsBlocking()
    IndexingTestUtil.waitUntilIndexesAreReady(project)
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a project root without a file holds a default entity with the default lines`() = runBlocking {
    assertEquals(mapOf(projectRoot.url to AnalysisIgnoreDefaults.LINES), defaultPatternsByRoot())
    // The default entity is not a file.
    assertEquals(emptySet<String>(), service.knownBaseDirUrls())
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `the anywhere names are excluded at any depth and the root names at the root only`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val targetAtRoot = dir("projectRoot/target")
    val targetBelow = dir("projectRoot/sub/target")
    val srcDir = dir("projectRoot/src")

    assertFalse(isInContent(yarnBelow))
    assertFalse(isInContent(targetAtRoot))
    assertTrue(isInContent(targetBelow))
    assertTrue(isInContent(srcDir))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "false")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `nothing is excluded while the feature is switched off`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val targetAtRoot = dir("projectRoot/target")

    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertTrue(isInContent(yarnBelow))
    assertTrue(isInContent(targetAtRoot))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "false")
  fun `nothing is excluded while the defaults are switched off`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val targetAtRoot = dir("projectRoot/target")
    val buildDir = dir("projectRoot/build")

    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertTrue(isInContent(yarnBelow))
    assertTrue(isInContent(targetAtRoot))

    withContext(Dispatchers.EDT) { assertTrue(OptionalExclusionUtil.exclude(project, buildDir)) }
    IndexingTestUtil.suspendUntilIndexesAreReady(project)

    assertEquals("/build/\n", textOfIgnoreFile("projectRoot"))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a file at the root removes the default entity and its removal brings it back`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val targetAtRoot = dir("projectRoot/target")
    val customDir = dir("projectRoot/custom")
    val ignoreFile = writeAnalysisIgnoreFile("projectRoot", "/custom/")

    service.applyNow(ignoreFile)

    // The update that adds the entity of the file also removes the default entity.
    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertFalse(isInContent(customDir))
    assertTrue(isInContent(yarnBelow))
    assertTrue(isInContent(targetAtRoot))

    // As the VFS listener does when the file goes away.
    service.forgetNow(projectRoot.url)

    assertEquals(mapOf(projectRoot.url to AnalysisIgnoreDefaults.LINES), defaultPatternsByRoot())
    assertTrue(isInContent(customDir))
    assertFalse(isInContent(yarnBelow))
    assertFalse(isInContent(targetAtRoot))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `an empty file at the root removes the defaults`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val targetAtRoot = dir("projectRoot/target")

    discover(writeAnalysisIgnoreFile("projectRoot"))

    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertTrue(isInContent(yarnBelow))
    assertTrue(isInContent(targetAtRoot))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a file below the root removes the defaults of the root as well`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val targetAtRoot = dir("projectRoot/target")
    val customDir = dir("projectRoot/sub/custom")
    discover(writeAnalysisIgnoreFile("projectRoot/sub", "/custom/"))

    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertTrue(isInContent(yarnBelow))
    assertTrue(isInContent(targetAtRoot))
    assertFalse(isInContent(customDir))

    service.forgetNow(projectRoot.url + "/sub")

    assertFalse(isInContent(yarnBelow))
    assertFalse(isInContent(targetAtRoot))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `the file that Mark as Excluded creates keeps the defaults in effect`() = runBlocking {
    val buildDir = dir("projectRoot/build")
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val targetAtRoot = dir("projectRoot/target")
    val targetBelow = dir("projectRoot/sub/target")

    withContext(Dispatchers.EDT) { assertTrue(OptionalExclusionUtil.exclude(project, buildDir)) }
    IndexingTestUtil.suspendUntilIndexesAreReady(project)

    val expected = AnalysisIgnoreDefaults.LINES + "/build/"
    assertEquals(expected.joinToString("\n", postfix = "\n"), textOfIgnoreFile("projectRoot"))
    assertEquals(AnalysisIgnoreDefaults.LINES, service.defaultLinesAddedTo(projectRoot.findChild(ANALYSIS_IGNORE_FILE_NAME)!!))
    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertFalse(isInContent(buildDir))
    assertFalse(isInContent(yarnBelow))
    assertFalse(isInContent(targetAtRoot))
    assertTrue(isInContent(targetBelow))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `Mark as Excluded in a nested content root writes the line alone and removes the defaults`() = runBlocking {
    val subRoot = dir("projectRoot/sub")
    val buildDir = dir("projectRoot/sub/build")
    val yarnElsewhere = dir("projectRoot/other/.yarn")
    ModuleRootModificationUtil.addContentRoot(projectModel.createModule("sub"), subRoot)
    IndexingTestUtil.suspendUntilIndexesAreReady(project)

    withContext(Dispatchers.EDT) { assertTrue(OptionalExclusionUtil.exclude(project, buildDir)) }
    IndexingTestUtil.suspendUntilIndexesAreReady(project)

    // A file below the project root gets no default lines, and it removes the defaults of the root.
    assertEquals("/build/\n", textOfIgnoreFile("projectRoot/sub"))
    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertFalse(isInContent(buildDir))
    assertTrue(isInContent(yarnElsewhere))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a library below a default name keeps its files, also after Mark as Excluded`() = runBlocking {
    val libraryRoot = dir("projectRoot/.venv/lib")
    val otherInVenv = dir("projectRoot/.venv/other")
    val buildDir = dir("projectRoot/build")
    ModuleRootModificationUtil.addModuleLibrary(module, libraryRoot.url)
    IndexingTestUtil.suspendUntilIndexesAreReady(project)

    assertTrue(isInLibrary(libraryRoot))
    assertFalse(isInContent(otherInVenv))

    withContext(Dispatchers.EDT) { assertTrue(OptionalExclusionUtil.exclude(project, buildDir)) }
    IndexingTestUtil.suspendUntilIndexesAreReady(project)

    // The file now holds '.venv' as a line. A file set below the matching directory includes its files again.
    assertTrue(isInLibrary(libraryRoot))
    assertFalse(isInContent(otherInVenv))
    assertFalse(isInContent(buildDir))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a content root without a project root gets no defaults`() = runBlocking {
    val otherRoot = dir("other")
    val yarnBelow = dir("other/sub/.yarn")
    ModuleRootModificationUtil.addContentRoot(projectModel.createModule("other"), otherRoot)
    service.syncDefaultsBlocking()
    IndexingTestUtil.suspendUntilIndexesAreReady(project)

    assertEquals(setOf(projectRoot.url), defaultPatternsByRoot().keys)
    assertTrue(isInContent(yarnBelow))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a project root added later gets its default entity without a scan`() = runBlocking {
    val attachedRoot = dir("attached")
    val yarnBelow = dir("attached/sub/.yarn")
    val srcDir = dir("attached/src")

    registerProjectRoot(project, urlOf(attachedRoot))

    // The service reacts to the new project root in the event log of the Workspace Model, and no file is read for it.
    withTimeout(10.seconds) {
      while (attachedRoot.url !in defaultPatternsByRoot()) delay(10.milliseconds)
    }
    assertFalse(isInContent(yarnBelow))
    assertTrue(isInContent(srcDir))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a scan gives a new project root its default entity before it starts`() = runBlocking {
    val attachedRoot = dir("attached")
    registerProjectRoot(project, urlOf(attachedRoot))

    AnalysisIgnoreIndexableFileScanner().startSession(project)

    assertEquals(AnalysisIgnoreDefaults.LINES, defaultPatternsByRoot()[attachedRoot.url])
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a new file at a project root without a module gets the root lines too`() = runBlocking {
    val attachedRoot = dir("attached")
    registerProjectRoot(project, urlOf(attachedRoot))
    service.syncDefaultsBlocking()

    withContext(Dispatchers.EDT) { AnalysisIgnoreFileWriter.appendLine(project, attachedRoot, "/build/") }

    val expected = AnalysisIgnoreDefaults.LINES + "/build/"
    assertEquals(expected.joinToString("\n", postfix = "\n"), textOfIgnoreFile("attached"))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a new empty file at the root gets the default lines and keeps the defaults in effect`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val targetAtRoot = dir("projectRoot/target")

    // As New File does. The VFS listener queues the new file.
    val ignoreFile = writeAnalysisIgnoreFile("projectRoot")
    service.awaitPendingFills()
    service.processNow()

    assertEquals(AnalysisIgnoreDefaults.LINES.joinToString("\n", postfix = "\n"), textOfIgnoreFile("projectRoot"))
    assertEquals(AnalysisIgnoreDefaults.LINES, service.defaultLinesAddedTo(ignoreFile))
    // The file replaces the default entity with the same lines.
    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertFalse(isInContent(yarnBelow))
    assertFalse(isInContent(targetAtRoot))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a new empty file below the root stays empty and removes the defaults`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")

    val ignoreFile = writeAnalysisIgnoreFile("projectRoot/sub")
    service.awaitPendingFills()
    service.processNow()

    assertEquals("", textOfIgnoreFile("projectRoot/sub"))
    assertNull(service.defaultLinesAddedTo(ignoreFile))
    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertTrue(isInContent(yarnBelow))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a new empty file at the root gets the default lines also after a file below the root removed them`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    discover(writeAnalysisIgnoreFile("projectRoot/sub", "/custom/"))
    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertTrue(isInContent(yarnBelow))

    val rootFile = writeAnalysisIgnoreFile("projectRoot")
    service.awaitPendingFills()
    service.processNow()

    assertEquals(AnalysisIgnoreDefaults.LINES.joinToString("\n", postfix = "\n"), textOfIgnoreFile("projectRoot"))
    assertEquals(AnalysisIgnoreDefaults.LINES, service.defaultLinesAddedTo(rootFile))
    assertFalse(isInContent(yarnBelow))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a new file with text gets no default lines`() = runBlocking {
    val ignoreFile = writeAnalysisIgnoreFile("projectRoot", "/custom/")
    service.awaitPendingFills()
    service.processNow()

    assertEquals("/custom/", textOfIgnoreFile("projectRoot"))
    assertNull(service.defaultLinesAddedTo(ignoreFile))
    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "false")
  fun `a new empty file stays empty while the defaults are off`() = runBlocking {
    val ignoreFile = writeAnalysisIgnoreFile("projectRoot")
    service.awaitPendingFills()
    service.processNow()

    assertEquals("", textOfIgnoreFile("projectRoot"))
    assertNull(service.defaultLinesAddedTo(ignoreFile))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `an empty file that appears on disk gets no default lines and turns off the defaults`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")

    // As a checkout does. The empty file in the repository turns off the defaults on purpose.
    val path = projectRoot.toNioPath().resolve(ANALYSIS_IGNORE_FILE_NAME)
    Files.createFile(path)
    val ignoreFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    service.awaitPendingFills()
    service.processNow()

    assertEquals("", textOfIgnoreFile("projectRoot"))
    assertNull(service.defaultLinesAddedTo(ignoreFile))
    assertEquals(emptyMap<String, List<String>>(), defaultPatternsByRoot())
    assertTrue(isInContent(yarnBelow))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `a file in a directory that the defaults exclude leaves the defaults in place`() = runBlocking {
    val yarnBelow = dir("projectRoot/sub/.yarn")
    val nodeModules = dir("projectRoot/node_modules")
    val packageDir = dir("projectRoot/node_modules/pkg")

    // As a package that ships its own file. The file gets an entity, as every file does, but the defaults hide its directory.
    writeAnalysisIgnoreFile("projectRoot/node_modules/pkg", "/x/")
    service.awaitPendingFills()
    service.processNow()

    assertEquals(setOf(packageDir.url), service.knownBaseDirUrls())
    assertEquals(mapOf(projectRoot.url to AnalysisIgnoreDefaults.LINES), defaultPatternsByRoot())
    assertFalse(isInContent(nodeModules))
    assertFalse(isInContent(yarnBelow))
  }

  @Test
  fun `the defaults exclude a directory by a name at any depth and by a root name at the root only`() {
    assertTrue(AnalysisIgnoreDefaults.excludesDirectory("node_modules/pkg", caseSensitive = true))
    assertTrue(AnalysisIgnoreDefaults.excludesDirectory("a/b/.venv/lib", caseSensitive = true))
    assertTrue(AnalysisIgnoreDefaults.excludesDirectory("target/classes", caseSensitive = true))
    assertFalse(AnalysisIgnoreDefaults.excludesDirectory("sub/target", caseSensitive = true))
    assertFalse(AnalysisIgnoreDefaults.excludesDirectory("src/main", caseSensitive = true))
    assertTrue(AnalysisIgnoreDefaults.excludesDirectory("Node_Modules/pkg", caseSensitive = false))
    assertFalse(AnalysisIgnoreDefaults.excludesDirectory("Node_Modules/pkg", caseSensitive = true))
  }

  @Test
  @RegistryKey(key = ENABLED, value = "true")
  @RegistryKey(key = DEFAULTS, value = "true")
  fun `deleting a filled file forgets its default lines`() = runBlocking {
    val ignoreFile = writeAnalysisIgnoreFile("projectRoot")
    service.awaitPendingFills()
    service.processNow()
    assertEquals(AnalysisIgnoreDefaults.LINES, service.defaultLinesAddedTo(ignoreFile))

    writeAction { ignoreFile.delete(this@AnalysisIgnoreDefaultExclusionsTest) }

    assertNull(service.defaultLinesAddedTo(ignoreFile))
  }

  /** The patterns of each [default entity][AnalysisIgnoreDefaultEntitySource], by the URL of its project root. */
  private fun defaultPatternsByRoot(): Map<String, List<String>> =
    WorkspaceModel.getInstance(project).currentSnapshot.defaultEntities().associate { it.baseDir.url to it.patterns }

  private suspend fun isInContent(file: VirtualFile): Boolean = readAction { fileIndex.isInContent(file) }

  private suspend fun isInLibrary(file: VirtualFile): Boolean = readAction { ProjectFileIndex.getInstance(project).isInLibrary(file) }

  /** Reads [files] and updates the entities at once, as the scanning of the project does. */
  private fun discover(vararg files: VirtualFile) {
    for (file in files) {
      service.applyNow(file)
    }
  }

  private fun urlOf(file: VirtualFile) = WorkspaceModel.getInstance(project).getVirtualFileUrlManager().storeAndGet(file.url)

  private fun textOfIgnoreFile(relativeDirPath: String): String {
    val ignoreFile = requireNotNull(projectModel.baseProjectDir.virtualFileRoot.findFileByRelativePath("$relativeDirPath/$ANALYSIS_IGNORE_FILE_NAME")) {
      "No $ANALYSIS_IGNORE_FILE_NAME in $relativeDirPath"
    }
    return VfsUtilCore.loadText(ignoreFile)
  }

  private fun dir(relativePath: String): VirtualFile = projectModel.baseProjectDir.newVirtualDirectory(relativePath)

  private fun writeAnalysisIgnoreFile(relativeDirPath: String, vararg lines: String): VirtualFile =
    projectModel.baseProjectDir.newVirtualFile("$relativeDirPath/$ANALYSIS_IGNORE_FILE_NAME", lines.joinToString("\n").toByteArray())
}

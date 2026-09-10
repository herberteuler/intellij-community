// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.mcp.imports

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.fixture.fileOrDirInProjectFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests `add_missing_imports` on a Java file.
 *
 * Every candidate comes from the test project itself, so no result depends on an SDK.
 */
class JavaAddMissingImportsTest : JavaImportsTestBase() {
  private val singleCandidateFile: VirtualFile by projectFixture.fileOrDirInProjectFixture("src/Single.java")
  private val appliedFile: VirtualFile by projectFixture.fileOrDirInProjectFixture("src/Applied.java")
  private val collapseFile: VirtualFile by projectFixture.fileOrDirInProjectFixture("src/Collapse.java")

  @Test
  fun `a class with a single candidate goes into the diff and leaves the file alone`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/Single.java", mode = "diff")) { result ->
      val text = result.textContent.text
      assertContains(text, "+import one.Alpha;")
    }
    val onDisk = singleCandidateFile.textOnDisk()
    assertFalse("import one.Alpha;" in onDisk, "The diff mode must not write the file: $onDisk")
  }

  @Test
  fun `two candidates are reported and nothing is imported`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/Ambiguous.java")) { result ->
      val text = result.textContent.text
      assertContains(text, "one.Proxy")
      assertContains(text, "two.Proxy")
      assertFalse("+import" in text, "An ambiguous name must not be imported: $text")
    }
  }

  @Test
  fun `the best candidate wins when the caller asks for it`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/Ambiguous.java", ambiguity = "best")) { result ->
      val text = result.textContent.text
      assertContains(text, "+import ")
      assertContains(text, "Proxy")
    }
  }

  @Test
  fun `an invented name is reported as not found`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/Invented.java")) { result ->
      val text = result.textContent.text
      assertContains(text, "NoSuchTypeAnywhere")
      assertContains(text, "symbol_not_found")
    }
  }

  @Test
  fun `a static method of a call is imported`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/StaticCall.java")) { result ->
      val text = result.textContent.text
      assertContains(text, "one.Helpers.twice")
      assertContains(text, "+import static one.Helpers.twice;")
    }
  }

  @Test
  fun `a static field is imported`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/StaticField.java")) { result ->
      val text = result.textContent.text
      assertContains(text, "one.Helpers.LIMIT")
      assertContains(text, "+import static one.Helpers.LIMIT;")
    }
  }

  @Test
  fun `two names of one file both reach the diff`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/TwoNames.java")) { result ->
      val text = result.textContent.text
      // Each import runs as its own change, so a lost second change would mean a collision.
      assertContains(text, "+import one.Alpha;")
      assertContains(text, "+import one.Helpers;")
      assertFalse("fix_not_applied" in text, "Both changes must apply: $text")
    }
  }

  @Test
  fun `the apply mode writes the file`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/Applied.java", mode = "apply")) { result ->
      assertContains(result.textContent.text, "one.Alpha")
    }
    val onDisk = appliedFile.textOnDisk()
    assertTrue("import one.Alpha;" in onDisk, "The apply mode must write the file: $onDisk")
  }

  @Test
  fun `another language is skipped`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/notes.txt")) { result ->
      assertContains(result.textContent.text, "notAnalyzedReason")
    }
  }

  @Test
  fun `a batch reports one entry per file`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = requestFiles("src/Single.java", "src/Invented.java")) { result ->
      val text = result.textContent.text
      assertContains(text, "src/Single.java")
      assertContains(text, "src/Invented.java")
    }
  }

  @Test
  fun `ten names of two packages stay explicit and no import arrives on its own`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/Collapse.java")) { result ->
      val text = result.textContent.text
      for (name in ONE_PACKAGE_NAMES) assertContains(text, "+import one.$name;")
      for (name in TWO_PACKAGE_NAMES) assertContains(text, "+import two.$name;")
      assertFalse("import one.*" in text, "The tool must add no import on demand: $text")
      assertFalse("import two.*" in text, "The tool must add no import on demand: $text")
      // The name is ambiguous, so no import of it may reach the file.
      assertFalse("import one.Proxy;" in text, "An import of Proxy must not appear: $text")
      assertFalse("import two.Proxy;" in text, "An import of Proxy must not appear: $text")
      assertContains(text, "one.Proxy")
      assertContains(text, "two.Proxy")
    }
  }

  @Test
  fun `the type of a return narrows the candidates of the return type`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/ExpectedType.java")) { result ->
      val text = result.textContent.text
      assertContains(text, "+import one.Handle;")
      assertFalse("two.Handle" in text, "The other candidate cannot come out of that method: $text")
      assertContains(text, EMPTY_AMBIGUOUS_LIST)
    }
  }

  @Test
  fun `a member called on the variable narrows the candidates of its type`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/UsedMember.java")) { result ->
      val text = result.textContent.text
      assertContains(text, "+import one.Reader;")
      assertFalse("two.Reader" in text, "The other candidate has no member `size`: $text")
      assertContains(text, EMPTY_AMBIGUOUS_LIST)
    }
  }

  @Test
  fun `the optimize flag runs optimize imports`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/CollapseOnePackage.java", optimize = true)) { result ->
      val text = result.textContent.text
      assertContains(text, "+import one.*;")
      for (name in ONE_PACKAGE_NAMES) assertFalse("+import one.$name;" in text, "Optimize Imports must join $name: $text")
    }
  }

  @Test
  fun `the optimize flag chooses no class for a shared name`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/Collapse.java", optimize = true)) { result ->
      val text = result.textContent.text
      assertContains(text, "one.Proxy")
      assertContains(text, "two.Proxy")
      assertFalse("import one.Proxy;" in text, "An import of Proxy must not appear: $text")
      assertFalse("import two.Proxy;" in text, "An import of Proxy must not appear: $text")
    }
  }

  @Test
  fun `the apply mode writes every import explicitly`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/Collapse.java", mode = "apply")) { result ->
      assertContains(result.textContent.text, "one.Alpha")
    }
    val onDisk = collapseFile.textOnDisk()
    for (name in ONE_PACKAGE_NAMES) assertTrue("import one.$name;" in onDisk, "Missing import of $name: $onDisk")
    for (name in TWO_PACKAGE_NAMES) assertTrue("import two.$name;" in onDisk, "Missing import of $name: $onDisk")
    assertFalse("import one.*" in onDisk, "The written file must hold no import on demand: $onDisk")
    assertFalse("import two.*" in onDisk, "The written file must hold no import on demand: $onDisk")
  }
}

/** How the result spells a file entry that reports no ambiguous name. */
private const val EMPTY_AMBIGUOUS_LIST = "\"ambiguous\":[]"

private val ONE_PACKAGE_NAMES = listOf("Alpha", "Beta", "Gamma", "Delta", "Epsilon")
private val TWO_PACKAGE_NAMES = listOf("Zeta", "Eta", "Theta", "Iota", "Kappa")

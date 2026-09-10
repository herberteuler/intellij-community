// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.mcp.imports

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.IdeaTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.fixture.fileOrDirInProjectFixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JavaJdkImportsTest : JavaImportsTestBase() {
  override fun projectDirectoryName(): String = "jdkImportsProject"

  private val missingImportsFile: VirtualFile by projectFixture.fileOrDirInProjectFixture("src/MissingImports.java")

  @BeforeEach
  fun attachMockJdk() = runBlocking {
    val sdk = IdeaTestUtil.getMockJdk18()
    edtWriteAction {
      ProjectJdkTable.getInstance().addJdk(sdk, project)
      ModuleRootModificationUtil.setModuleSdk(moduleFixture.get(), sdk)
    }
    IndexingTestUtil.waitUntilIndexesAreReady(project)
  }

  @Test
  fun `every name of a large file gets its own import`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/MissingImports.java")) { result ->
      val text = result.textContent.text
      for (name in UTIL_NAMES) assertContains(text, "+import java.util.$name;")
      for (name in CONCURRENT_NAMES) assertContains(text, "+import java.util.concurrent.$name;")
      assertContains(text, "+import java.util.concurrent.atomic.AtomicInteger;")
      assertContains(text, "+import java.util.stream.Collectors;")
      assertContains(text, "+import java.math.BigDecimal;")
      for (packageName in ON_DEMAND_PACKAGES) {
        assertFalse("import $packageName.*" in text, "The tool must add no import on demand: $text")
      }
    }
  }

  @Test
  fun `no name is reported as a failed fix`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/MissingImports.java")) { result ->
      val text = result.textContent.text
      assertFalse("fix_not_applied" in text, "Every import of the file must apply: $text")
      assertFalse("symbol_not_found" in text, "Every name of the file exists in the mock JDK: $text")
    }
  }

  @Test
  fun `the optimize flag brings the import on demand back`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/MissingImports.java", optimize = true)) { result ->
      assertContains(result.textContent.text, "import java.util.*")
    }
  }

  @Test
  fun `the apply mode writes a file that needs no import on demand`() = runBlocking {
    testMcpTool(toolName = ADD_MISSING_IMPORTS, input = request("src/MissingImports.java", mode = "apply")) { result ->
      assertContains(result.textContent.text, "java.util.ArrayList")
    }
    val onDisk = missingImportsFile.textOnDisk()
    for (name in UTIL_NAMES) assertTrue("import java.util.$name;" in onDisk, "Missing import of $name: $onDisk")
    for (packageName in ON_DEMAND_PACKAGES) {
      assertFalse("import $packageName.*" in onDisk, "The written file must hold no import on demand: $onDisk")
    }
  }
}

private val UTIL_NAMES = listOf(
  "List", "ArrayList", "Map", "LinkedHashMap", "SortedMap", "TreeMap", "Set", "HashSet", "NavigableSet", "TreeSet",
  "Deque", "ArrayDeque", "Queue", "PriorityQueue", "LinkedList", "EnumMap", "EnumSet", "BitSet", "Iterator",
  "ListIterator", "Collection", "Collections", "Optional", "Comparator", "StringJoiner", "UUID", "Random",
  "Locale", "Objects",
)

private val CONCURRENT_NAMES = listOf(
  "ConcurrentMap", "ConcurrentHashMap", "CopyOnWriteArrayList", "ExecutorService", "Executors", "Future",
  "Callable", "TimeUnit",
)

private val ON_DEMAND_PACKAGES = listOf("java.util", "java.util.concurrent")

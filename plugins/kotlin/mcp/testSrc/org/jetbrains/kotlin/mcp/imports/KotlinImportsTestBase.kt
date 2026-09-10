// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.mcp.imports

import com.intellij.mcpserver.testFramework.McpToolsetTestBase
import com.intellij.mcpserver.toolsets.general.ImportsToolset
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.pathInProjectFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.BeforeEach
import java.nio.file.Path

/**
 * Opens the Kotlin project of `testResources/importsProject` and makes `src` a source root.
 *
 * The files come from the disk, because a fixture cannot create a file in a subdirectory. The module
 * and the source root come from a fixture, because the project of the MCP test base holds no module,
 * and a short name resolves only inside one.
 *
 * No file of the project names a type of the standard library. The fixture project has no SDK and no
 * standard library, so such a name would be one more unresolved name in every result.
 */
abstract class KotlinImportsTestBase : McpToolsetTestBase() {
    override fun projectTestData(): Path =
        Path.of(PathManager.getCommunityHomePath(), "plugins/kotlin/mcp/testResources/importsProject")

    protected val moduleFixture: TestFixture<Module> = projectFixture.moduleFixture("testModule")

    protected val sourceRootFixture: TestFixture<PsiDirectory> =
        moduleFixture.sourceRootFixture(pathFixture = projectFixture.pathInProjectFixture(Path.of("src")))

    @BeforeEach
    fun initSourceRootFixture() {
        sourceRootFixture.get()
    }
}

/** The function reference keeps the tests in sync with a rename of the tool. */
internal val ADD_MISSING_IMPORTS: String = ImportsToolset::add_missing_imports.name

/** Builds a request for one file. An absent argument takes the default of the tool. */
internal fun request(
    path: String,
    mode: String? = null,
    ambiguity: String? = null,
    optimize: Boolean? = null,
): JsonObject = buildJsonObject {
    putJsonArray("files") { add(JsonPrimitive(path)) }
    if (mode != null) put("mode", JsonPrimitive(mode))
    if (ambiguity != null) put("ambiguity", JsonPrimitive(ambiguity))
    if (optimize != null) put("optimize", JsonPrimitive(optimize))
}

/** Reads the file from the disk, because the tool saves the document and the agent reads it back. */
internal fun VirtualFile.textOnDisk(): String {
    refresh(false, false)
    return String(contentsToByteArray(), Charsets.UTF_8)
}

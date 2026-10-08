// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.junit5Tests.unit

import com.intellij.testFramework.junit5.TestApplication
import com.jetbrains.python.sdk.add.v2.toFileSystem
import com.intellij.platform.eel.provider.localEel
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createFile
import kotlin.io.path.createDirectories
import org.junit.jupiter.api.Assertions.assertNull
import com.intellij.openapi.util.SystemInfo
import com.intellij.python.sdk.common.PyEnvRef
import com.intellij.python.sdk.backend.fileName
import com.intellij.python.sdk.backend.pathEnvRef
import com.intellij.python.sdk.backend.resolvePythonHome
import com.jetbrains.python.sdk.add.v2.PathHolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** The env ref of an environment that its manager names by its Python binary, and how it resolves. */
@TestApplication
class PyPathEnvRefTest {
  @TempDir
  lateinit var root: Path

  private val projectDir: Path get() = root.resolve("app")

  /** [com.jetbrains.python.sdk.add.v2.FileSystem.resolveEnvRef] on the local machine. */
  private fun resolveEnvRef(envRef: PyEnvRef): Path? = runBlocking { localEel.toFileSystem().resolveEnvRef(projectDir, envRef)?.path }

  @Test
  fun `a binary inside the project gives its home, relative to the project`() {
    assertEquals(PyEnvRef(".venv"), pathEnvRef(PathHolder.Eel(projectDir.resolve(".venv/bin/python")), projectDir))
    assertEquals(PyEnvRef("sub/env"), pathEnvRef(PathHolder.Eel(projectDir.resolve("sub/env/bin/python")), projectDir))
  }

  @Test
  fun `a binary outside the project is absolute`() {
    val outside = root.resolve("other/.venv/bin/python")
    assertEquals(PyEnvRef(outside.toString()), pathEnvRef(PathHolder.Eel(outside), projectDir))
    assertEquals(PyEnvRef(outside.toString()), pathEnvRef(PathHolder.Eel(outside), projectDir = null))
  }

  @Test
  fun `a target binary is absolute`() {
    assertEquals(PyEnvRef("/srv/app/.venv/bin/python"), pathEnvRef(PathHolder.Target("/srv/app/.venv/bin/python"), projectDir))
  }

  @Test
  fun `a relative env ref resolves to the binary of its home`() {
    val binary = venv(projectDir.resolve(".venv"))
    assertEquals(binary, resolveEnvRef(PyEnvRef(".venv")))
    val outsideBinary = venv(root.resolve("other/.venv"))
    assertEquals(outsideBinary, resolveEnvRef(PyEnvRef("../other/.venv")))
  }

  @Test
  fun `an absolute env ref is the binary`() {
    val absolute = root.resolve("other/.venv/bin/python")
    assertEquals(absolute, resolveEnvRef(PyEnvRef(absolute.toString())))
  }

  @Test
  fun `a relative env ref without an interpreter resolves to nothing`() {
    assertNull(resolveEnvRef(PyEnvRef(".venv")))
  }

  @Test
  fun `an env ref round-trips`() {
    val binary = venv(projectDir.resolve("venv"))
    assertEquals(binary, resolveEnvRef(pathEnvRef(PathHolder.Eel(binary), projectDir)))
  }

  /** Creates a venv layout in [home] for the current OS, and returns its Python binary. */
  private fun venv(home: Path): Path {
    val binary = if (SystemInfo.isWindows) home.resolve("Scripts/python.exe") else home.resolve("bin/python")
    binary.parent.createDirectories()
    return binary.createFile()
  }

  @Test
  fun `the Python home of a target binary is its env folder`() {
    val home = PathHolder.Target("/srv/app/.venv/bin/python").resolvePythonHome()
    assertEquals(PathHolder.Target("/srv/app/.venv"), home)
    assertEquals(".venv", home.fileName)
  }

  @Test
  fun `the Python home of a local binary is its env folder`() {
    val home = PathHolder.Eel(projectDir.resolve("venv/bin/python")).resolvePythonHome()
    assertEquals(PathHolder.Eel(projectDir.resolve("venv")), home)
    assertEquals("venv", home.fileName)
  }
}

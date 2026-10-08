// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.junit5Tests.unit

import com.intellij.python.sdk.common.PyEnvRef
import com.intellij.python.sdk.common.PyInterpreterRef
import com.intellij.python.pytools.common.FusId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The string form of a [PyInterpreterRef], and its parse. */
class PyInterpreterRefTest {
  private val uv = FusId("uv")

  @Test
  fun `a native ref round-trips`() {
    val ref = PyInterpreterRef.native(uv, PyEnvRef("/home/me/app/.venv/bin/python"))
    assertEquals("native:uv:/home/me/app/.venv/bin/python", ref.toString())
    assertEquals(ref, PyInterpreterRef.parse(ref.toString()))
  }

  @Test
  fun `a target ref round-trips`() {
    val ref = PyInterpreterRef(PyInterpreterRef.Mode.Target("3f2a"), FusId("conda"), PyEnvRef("ml"))
    assertEquals("target:3f2a:conda:ml", ref.toString())
    assertEquals(ref, PyInterpreterRef.parse(ref.toString()))
  }

  @Test
  fun `an env ref keeps its colons`() {
    val parsed = PyInterpreterRef.parse("native:uv:C:\\app\\.venv\\Scripts\\python.exe")
    assertEquals("C:\\app\\.venv\\Scripts\\python.exe", parsed?.envRef?.value)
  }

  @Test
  fun `a ref of a tool that is not loaded still parses`() {
    // The plugin of a tool may be disabled. Its ref stays valid, so a stored interpreter is not lost.
    assertEquals(FusId("no-such-tool"), PyInterpreterRef.parse("native:no-such-tool:/python")?.manager)
  }

  @Test
  fun `a malformed ref parses to null`() {
    assertNull(PyInterpreterRef.parse("native:uv"))
    assertNull(PyInterpreterRef.parse("native:uv:"))
    assertNull(PyInterpreterRef.parse("native::/python"))
    assertNull(PyInterpreterRef.parse("target:3f2a:uv"))
    assertNull(PyInterpreterRef.parse("remote:uv:/python"))
  }
}

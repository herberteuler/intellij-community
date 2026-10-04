// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.idea

import com.intellij.util.system.OS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Collections

class DevDataLinkTest {
  @TempDir
  lateinit var tempDir: Path

  private val base: Path by lazy { tempDir.toRealPath() }
  private val workspace: Path by lazy { Files.createDirectories(base.resolve("idea")) }
  private val parent: Path by lazy { base.resolve("cache") }
  private val link: Path by lazy { workspace.resolve(DEV_DATA_LINK) }

  // The parent comes from the variable, so no test touches the cache of the user.
  private val root: Path by lazy { devDataRoot(workspace, ::getenv, OS.Linux)!! }

  private fun getenv(name: String): String? = if (name == DEV_DATA_ROOT_VARIABLE) parent.toString() else null

  private fun ensure(): List<String> {
    val warnings = ArrayList<String>()
    ensureDevDataLink(workspace.toString(), ::getenv, OS.Linux) { warnings.add(it) }
    return warnings
  }

  @Test
  fun `the root is per checkout and on by default only on macOS`() {
    val checkout = Path.of("/Users/dev/projects/idea")
    val home = mapOf("HOME" to "/Users/dev")
    val overridden = home + (DEV_DATA_ROOT_VARIABLE to "/data/dd")
    assertEquals(Path.of("/Users/dev/Library/Caches/JetBrains/MonorepoDevData/idea-f7972983"), devDataRoot(checkout, home::get, OS.macOS))
    assertEquals(Path.of("/data/dd/idea-f7972983"), devDataRoot(checkout, overridden::get, OS.macOS))
    assertEquals(null, devDataRoot(checkout, home::get, OS.Linux))
    assertEquals(Path.of("/data/dd/idea-f7972983"), devDataRoot(checkout, overridden::get, OS.Linux))
    assertEquals(null, devDataRoot(checkout, overridden::get, OS.Windows))
  }

  @Test
  fun `a fresh checkout gets the link and the marker`() {
    assertEquals(emptyList<String>(), ensure())
    assertLink(link, root)
    assertContent(root.resolve(DEV_DATA_WORKSPACE_MARKER), "$workspace\n")
    // a second launch keeps the link
    assertEquals(emptyList<String>(), ensure())
    assertLink(link, root)
  }

  @Test
  fun `an existing link decides and gets the marker`() {
    val elsewhere = base.resolve("elsewhere")
    Files.createDirectories(link.parent)
    Files.createSymbolicLink(link, elsewhere)
    ensure()
    assertLink(link, elsewhere)
    assertContent(elsewhere.resolve(DEV_DATA_WORKSPACE_MARKER), "$workspace\n")
    assertFalse(Files.exists(root), "created the formula root beside an existing link")
  }

  @Test
  fun `the directory of an older launch moves by rename`() {
    write(link.resolve("idea/config/options/laf.xml"), "dark")
    assertEquals(emptyList<String>(), ensure())
    assertLink(link, root)
    assertContent(root.resolve("idea/config/options/laf.xml"), "dark")
    assertContent(root.resolve(DEV_DATA_WORKSPACE_MARKER), "$workspace\n")
  }

  @Test
  fun `a root that lacks the entries gets them`() {
    write(root.resolve("rider/config/a.xml"), "rider")
    write(link.resolve("idea/config/b.xml"), "idea")
    ensure()
    assertLink(link, root)
    assertContent(root.resolve("rider/config/a.xml"), "rider")
    assertContent(root.resolve("idea/config/b.xml"), "idea")
  }

  @Test
  fun `a name collision keeps the directory`() {
    write(root.resolve("idea/config/a.xml"), "root")
    write(link.resolve("idea/config/a.xml"), "workspace")
    val warnings = ensure()
    assertTrue(warnings.single().contains("both hold idea"), "warnings: $warnings")
    assertTrue(Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS))
    assertContent(link.resolve("idea/config/a.xml"), "workspace")
    assertContent(root.resolve("idea/config/a.xml"), "root")
  }

  @Test
  fun `a live row keeps the directory`() {
    // the parent process of the test runs for sure
    val lock = link.resolve("idea/config/.lock")
    write(lock, liveForeignPid().toString())
    val warnings = ensure()
    assertTrue(warnings.single().contains("a dev IDE runs from"), "warnings: $warnings")
    assertTrue(Files.isDirectory(link, LinkOption.NOFOLLOW_LINKS), "moved the dev data of a running IDE")
    // a stale lock does not keep the directory
    write(lock, "999999999")
    assertEquals(emptyList<String>(), ensure())
    assertLink(link, root)
  }

  @Test
  fun `a file in place of the directory stays`() {
    write(link, "not a directory")
    val warnings = ensure()
    assertTrue(warnings.single().contains("is not a directory"), "warnings: $warnings")
    assertContent(link, "not a directory")
  }

  @Test
  fun `an out outside the workspace stays`() {
    val out = base.resolve("out-elsewhere")
    write(out.resolve("dev-data/idea/config/a.xml"), "x")
    Files.createSymbolicLink(workspace.resolve("out"), out)
    ensure()
    assertTrue(Files.isDirectory(out.resolve("dev-data"), LinkOption.NOFOLLOW_LINKS), "touched a dev-data directory outside the workspace")
    assertFalse(Files.exists(root), "created a root for an out outside the workspace")
  }

  @Test
  fun `concurrent launches make one link`() {
    for (index in 0 until 20) {
      write(link.resolve("row$index/config/a.xml"), index.toString())
    }
    val warnings = Collections.synchronizedList(ArrayList<String>())
    val threads = (0 until 8).map {
      Thread { ensureDevDataLink(workspace.toString(), ::getenv, OS.Linux) { warnings.add(it) } }.apply { start() }
    }
    threads.forEach { it.join() }
    assertEquals(emptyList<String>(), warnings)
    assertLink(link, root)
    for (index in 0 until 20) {
      assertContent(root.resolve("row$index/config/a.xml"), index.toString())
    }
  }

  @Test
  fun `a new root reports the roots of deleted checkouts and deletes nothing`() {
    write(parent.resolve("gone-1").resolve(DEV_DATA_WORKSPACE_MARKER), "${base.resolve("gone")}\n")
    write(parent.resolve("alive-2").resolve(DEV_DATA_WORKSPACE_MARKER), "$workspace\n")
    // the parent of the checkout is missing, so its volume can be unmounted
    write(parent.resolve("unmounted-3").resolve(DEV_DATA_WORKSPACE_MARKER), "/Volumes/missing/checkout\n")
    val warnings = ensure()
    assertTrue(warnings.single().startsWith("NOTE: the dev data ${parent.resolve("gone-1")} "), "warnings: $warnings")
    for (name in listOf("gone-1", "alive-2", "unmounted-3")) {
      assertTrue(Files.exists(parent.resolve(name)), "removed $name")
    }
  }

  @Test
  fun `the stale launcher homes go and a live one stays`() {
    val target = write(base.resolve("runfile.jar"), "jar")
    val stale = link.resolve("idea/homes/999999999")
    Files.createDirectories(stale.resolve("lib"))
    Files.createSymbolicLink(stale.resolve("lib/runfile.jar"), target)
    val live = link.resolve("rider/homes").resolve(liveForeignPid().toString())
    Files.createDirectories(live)
    write(link.resolve("rider/homes/999999998/product-info.json"), "{}")

    assertEquals(emptyList<String>(), ensure())
    assertFalse(Files.exists(root.resolve("idea/homes")), "kept the homes directory of stale homes")
    assertTrue(Files.isDirectory(root.resolve("idea")))
    assertContent(target, "jar")
    assertTrue(Files.isDirectory(live), "removed the home of a live process")
    assertFalse(Files.exists(root.resolve("rider/homes/999999998")), "kept a stale home beside a live one")
  }

  private fun liveForeignPid(): Long = ProcessHandle.current().parent().orElseThrow().pid()
}

private fun write(file: Path, content: String): Path {
  Files.createDirectories(file.parent)
  return Files.writeString(file, content)
}

private fun assertLink(link: Path, target: Path) {
  assertTrue(Files.isSymbolicLink(link), "$link is not a link")
  assertEquals(target, Files.readSymbolicLink(link), "$link links to the wrong target")
}

private fun assertContent(file: Path, expected: String) {
  assertEquals(expected, Files.readString(file), file.toString())
}

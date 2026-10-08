// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.testFramework.junit5.eel.fs

import com.intellij.platform.eel.EelResult
import com.intellij.platform.eel.fs.EelFileInfo
import com.intellij.platform.eel.fs.EelFileSystemApi
import com.intellij.platform.eel.fs.EelFileSystemPosixApi
import com.intellij.platform.eel.fs.EelPosixFileInfo
import com.intellij.platform.eel.fs.createTemporaryDirectory
import com.intellij.platform.eel.fs.createTemporaryFile
import com.intellij.platform.eel.fs.listDirectoryWithAttrs
import com.intellij.platform.eel.fs.openForWriting
import com.intellij.platform.eel.fs.stat
import com.intellij.platform.eel.getOrThrow
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.testFramework.junit5.eel.params.api.EelHolder
import com.intellij.platform.testFramework.junit5.eel.params.api.TestApplicationWithEel
import com.intellij.testFramework.common.timeoutRunBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.params.ParameterizedClass

/** Regression coverage for IJPL-250517. */
@TestApplicationWithEel(osesMayNotHaveRemoteEels = [OS.WINDOWS, OS.LINUX, OS.MAC])
@ParameterizedClass
class EelPosixSymlinkStatTest(private val eelHolder: EelHolder) {
  @Test
  fun `symlink to directory is resolved according to policy`(): Unit = timeoutRunBlocking {
    val fs = posixFileSystem()
    withTemporaryRoot(fs) { root ->
      val targetDir = fs.createTemporaryDirectory().parentDirectory(root).prefix("target-dir-").getOrThrow()
      val link = root.getChild("link-to-dir")
      fs.createSymbolicLink(EelFileSystemPosixApi.SymbolicLinkTarget.Absolute(targetDir), link).getOrThrow()

      assertInstanceOf(EelPosixFileInfo.Type.Symlink.Unresolved::class.java, fs.stat(link).doNotResolve().getOrThrow().type)

      val resolved = fs.stat(link).justResolve().getOrThrow().type
      val absolute = assertInstanceOf(EelPosixFileInfo.Type.Symlink.Resolved.Absolute::class.java, resolved)
      assertEquals(targetDir, absolute.result)

      assertInstanceOf(EelFileInfo.Type.Directory::class.java, fs.stat(link).resolveAndFollow().getOrThrow().type)
    }
  }

  @Test
  fun `relative symlink keeps its raw target on JUST_RESOLVE`(): Unit = timeoutRunBlocking {
    val fs = posixFileSystem()
    withTemporaryRoot(fs) { root ->
      val targetFile = fs.createTemporaryFile().parentDirectory(root).prefix("target-file-").getOrThrow()
      fs.openForWriting(targetFile).getOrThrow().close().getOrThrow()
      val link = root.getChild("relative-link")
      fs.createSymbolicLink(EelFileSystemPosixApi.SymbolicLinkTarget.Relative(listOf(targetFile.fileName)), link).getOrThrow()

      val resolved = fs.stat(link).justResolve().getOrThrow().type
      val relative = assertInstanceOf(EelPosixFileInfo.Type.Symlink.Resolved.Relative::class.java, resolved)
      assertEquals(targetFile.fileName, relative.result)

      assertInstanceOf(EelFileInfo.Type.Regular::class.java, fs.stat(link).resolveAndFollow().getOrThrow().type)
    }
  }

  @Test
  fun `dangling symlink is available without following`(): Unit = timeoutRunBlocking {
    val fs = posixFileSystem()
    withTemporaryRoot(fs) { root ->
      val targetName = "missing-target"
      val link = root.getChild("broken-link")
      fs.createSymbolicLink(EelFileSystemPosixApi.SymbolicLinkTarget.Relative(listOf(targetName)), link).getOrThrow()

      assertInstanceOf(EelPosixFileInfo.Type.Symlink.Unresolved::class.java, fs.stat(link).doNotResolve().getOrThrow().type)

      val resolved = fs.stat(link).justResolve().getOrThrow().type
      val relative = assertInstanceOf(EelPosixFileInfo.Type.Symlink.Resolved.Relative::class.java, resolved)
      assertEquals(targetName, relative.result)

      val followed = fs.stat(link).resolveAndFollow().eelIt()
      val error = assertInstanceOf(EelResult.Error::class.java, followed)
      assertInstanceOf(EelFileSystemApi.StatError.DoesNotExist::class.java, error.error)
    }
  }

  @Test
  fun `directory listing applies symlink policy to every child`(): Unit = timeoutRunBlocking {
    val fs = posixFileSystem()
    withTemporaryRoot(fs) { root ->
      val targetDir = fs.createTemporaryDirectory().parentDirectory(root).prefix("target-dir-").getOrThrow()
      val linkName = "link-to-dir"
      fs.createSymbolicLink(EelFileSystemPosixApi.SymbolicLinkTarget.Absolute(targetDir), root.getChild(linkName)).getOrThrow()
      val brokenLinkName = "broken-link"
      fs.createSymbolicLink(
        EelFileSystemPosixApi.SymbolicLinkTarget.Relative(listOf("missing-target")),
        root.getChild(brokenLinkName),
      ).getOrThrow()

      val resolved = fs.listDirectoryWithAttrs(root).justResolve().getOrThrow().single { it.first == linkName }.second.type
      val absolute = assertInstanceOf(EelPosixFileInfo.Type.Symlink.Resolved.Absolute::class.java, resolved)
      assertEquals(targetDir, absolute.result)

      val followed = fs.listDirectoryWithAttrs(root).resolveAndFollow().getOrThrow().single { it.first == linkName }.second.type
      assertInstanceOf(EelFileInfo.Type.Directory::class.java, followed)

      val unresolved = fs.listDirectoryWithAttrs(root).doNotResolve().getOrThrow().toMap()
      assertInstanceOf(EelPosixFileInfo.Type.Symlink.Unresolved::class.java, unresolved.getValue(linkName).type)
      assertInstanceOf(EelPosixFileInfo.Type.Symlink.Unresolved::class.java, unresolved.getValue(brokenLinkName).type)

      val brokenResolved = fs.listDirectoryWithAttrs(root).justResolve().getOrThrow().toMap().getValue(brokenLinkName).type
      val relative = assertInstanceOf(EelPosixFileInfo.Type.Symlink.Resolved.Relative::class.java, brokenResolved)
      assertEquals("missing-target", relative.result)

      val followedEntries = fs.listDirectoryWithAttrs(root).resolveAndFollow().getOrThrow().mapTo(mutableSetOf()) { it.first }
      assertEquals(setOf(targetDir.fileName, linkName), followedEntries)
    }
  }

  private suspend fun withTemporaryRoot(fs: EelFileSystemPosixApi, action: suspend (EelPath) -> Unit) {
    val root = fs.createTemporaryDirectory().prefix("ijpl-250517-").getOrThrow()
    var testFailure: Throwable? = null
    try {
      action(root)
    }
    catch (t: Throwable) {
      testFailure = t
      throw t
    }
    finally {
      try {
        fs.delete(root, recursive = true).getOrThrow()
      }
      catch (cleanupFailure: Throwable) {
        val originalFailure = testFailure
        if (originalFailure != null) {
          originalFailure.addSuppressed(cleanupFailure)
        }
        else {
          throw cleanupFailure
        }
      }
    }
  }

  private fun posixFileSystem(): EelFileSystemPosixApi {
    val fs = eelHolder.eel.fs
    assumeTrue(fs is EelFileSystemPosixApi, "The test is meaningful only for POSIX file systems")
    return fs as EelFileSystemPosixApi
  }
}

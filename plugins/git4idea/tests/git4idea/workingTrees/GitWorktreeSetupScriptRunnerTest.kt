// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees

import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import git4idea.test.GitSingleRepoContext
import git4idea.test.gitSingleRepoContextFixture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@TestApplication
internal class GitWorktreeSetupScriptRunnerTest {
  private val contextFixture = gitSingleRepoContextFixture()
  private val context: GitSingleRepoContext get() = contextFixture.get()

  @Test
  fun `test runs the script once with the worktree directory as the working directory and sole argument`(): Unit = with(context) {
    val worktreeDir = testNioRoot.resolve("worktree")
    Files.createDirectories(worktreeDir)
    val outputFile = testNioRoot.resolve("setup-script-output.txt")
    val script = writeExecutableScript(
      posixContent = """
        #!/bin/sh
        pwd > "$outputFile"
        echo "$1" >> "$outputFile"
      """.trimIndent(),
      windowsContent = """
        @echo off
        cd > "$outputFile"
        echo %1>>"$outputFile"
      """.trimIndent(),
    )

    runBlocking {
      GitWorktreeSetupScriptRunner.runSetupScript(project, script, worktreeDir)
    }

    val lines = Files.readAllLines(outputFile)
    assertThat(lines[0]).isEqualTo(worktreeDir.toRealPath().toString())
    assertThat(lines[1]).isEqualTo(worktreeDir.toString())
  }

  @Test
  fun `test does not throw when the script does not exist`(): Unit = with(context) {
    val worktreeDir = testNioRoot.resolve("worktree")
    Files.createDirectories(worktreeDir)

    runBlocking {
      GitWorktreeSetupScriptRunner.runSetupScript(project, testNioRoot.resolve("no-such-script"), worktreeDir)
    }
  }

  @Test
  fun `test cancelling the coroutine kills the running setup script instead of waiting for it`(): Unit = with(context) {
    val worktreeDir = testNioRoot.resolve("worktree")
    Files.createDirectories(worktreeDir)
    val markerFile = testNioRoot.resolve("script-finished.marker")
    val script = writeExecutableScript(
      posixContent = """
        #!/bin/sh
        sleep 30
        touch "$markerFile"
      """.trimIndent(),
      windowsContent = """
        @echo off
        ping -n 31 127.0.0.1 >nul
        echo. > "$markerFile"
      """.trimIndent(),
    )

    timeoutRunBlocking(timeout = 10.seconds) {
      val job = launch { GitWorktreeSetupScriptRunner.runSetupScript(project, script, worktreeDir) }
      delay(500)
      job.cancel()
      job.join()
    }

    assertThat(markerFile).doesNotExist()
  }

  private fun writeExecutableScript(posixContent: String, windowsContent: String): Path {
    val suffix = if (SystemInfo.isWindows) ".cmd" else ".sh"
    val content = if (SystemInfo.isWindows) windowsContent else posixContent
    return ExecUtil.createTempExecutableScript("setup-script", suffix, content).toPath()
  }
}

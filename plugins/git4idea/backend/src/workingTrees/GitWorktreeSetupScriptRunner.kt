// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.workingTrees

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.ExecuteProcessException
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.eel.provider.utils.awaitProcessResult
import com.intellij.platform.eel.spawnProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path

/**
 * Runs the optional setup script a user picks in the New Worktree dialog, after the worktree and its copied
 * project configuration are ready, but before the new project window opens.
 */
internal object GitWorktreeSetupScriptRunner {
  private val LOG = logger<GitWorktreeSetupScriptRunner>()
  private const val SETUP_SCRIPT_TIMEOUT_MS = 5 * 60 * 1000L

  /**
   * Runs [scriptPath] once, with [worktreeDir] as both the working directory and the sole command-line
   * argument. A failure, including a timeout, only logs the output; it never removes the worktree or blocks
   * the caller from opening the new project. The caller shows a failure notification.
   *
   * Returns `true` only when the script starts and exits with code 0 before the timeout.
   */
  suspend fun runSetupScript(project: Project, scriptPath: Path, worktreeDir: Path): Boolean = withContext(Dispatchers.IO) {
    val process = try {
      val eelApi = project.getEelDescriptor().toEelApi()
      val eelWorktreeDir = worktreeDir.asEelPath()
      eelApi.exec.spawnProcess(scriptPath.asEelPath().toString())
        .args(eelWorktreeDir.toString())
        .workingDirectory(eelWorktreeDir)
        .eelIt()
    }
    catch (e: ExecuteProcessException) {
      LOG.warn("Failed to start the setup script $scriptPath", e)
      return@withContext false
    }
    try {
      val result = withTimeoutOrNull(SETUP_SCRIPT_TIMEOUT_MS) { process.awaitProcessResult() }
      if (result == null) {
        process.kill()
        LOG.warn("The setup script $scriptPath timed out.")
        false
      }
      else {
        LOG.info("Setup script $scriptPath finished with exit code ${result.exitCode}.")
        result.exitCode == 0
      }
    }
    catch (e: CancellationException) {
      withContext(NonCancellable) {
        process.kill()
      }
      throw e
    }
  }
}

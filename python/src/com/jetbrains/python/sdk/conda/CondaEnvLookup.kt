// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.conda

import com.intellij.python.sdk.common.PyEnvRef
import com.intellij.python.community.impl.conda.CondaPyTool
import com.intellij.python.sdk.backend.evolution.envNotFound
import com.intellij.python.sdk.backend.evolution.toolMissing
import com.intellij.python.sdk.backend.resolveExecutable
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.sdk.add.v2.PathHolder
import com.jetbrains.python.sdk.add.v2.FileSystem
import com.jetbrains.python.sdk.conda.execution.CondaExecutor
import com.jetbrains.python.sdk.flavors.conda.PyCondaEnv

/**
 * The conda env on the machine of [fileSystem] at [envRef], which is the readable name of its identity. conda reports
 * the envs, and its answer is cached, so a second lookup costs no process.
 */
internal suspend fun <P : PathHolder> condaEnvOf(fileSystem: FileSystem<P>, envRef: PyEnvRef): PyResult<PyCondaEnv> {
  val conda = CondaPyTool.getInstance()
  val condaExecutable = fileSystem.resolveExecutable(conda) ?: return toolMissing(conda)
  val envs = PyCondaEnv.getEnvs(fileSystem.getBinaryToExec(condaExecutable)).getOr { return it }
  return envs.firstOrNull { it.envIdentity.userReadableName == envRef.value }?.let { PyResult.success(it) } ?: envNotFound(envRef)
}

/** The Python binary of the conda env at [envRef] on the machine of [fileSystem], as conda reports it. */
internal suspend fun <P : PathHolder> condaPythonBinaryOf(fileSystem: FileSystem<P>, envRef: PyEnvRef): PyResult<P> {
  val env = condaEnvOf(fileSystem, envRef).getOr { return it }
  val condaExecutable = fileSystem.parsePath(env.fullCondaPathOnTarget).getOr { return it }
  val interpreterPath = CondaExecutor.runPythonInCondaEnv(fileSystem.getBinaryToExec(condaExecutable), env.envIdentity, "-c", "import sys; print(sys.executable)")
    .getOr { return it }
  return fileSystem.parsePath(interpreterPath.trim())
}

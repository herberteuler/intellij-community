// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.conda

import com.intellij.python.sdk.backend.resolveExecutable
import com.jetbrains.python.sdk.flavors.conda.PyCondaEnv
import com.jetbrains.python.sdk.flavors.conda.PyCondaEnvIdentity
import com.jetbrains.python.sdk.PySdkDataProvider
import com.jetbrains.python.sdk.add.v2.FileSystem
import com.intellij.python.sdk.common.PyEnvRef
import com.intellij.python.community.impl.conda.CondaPyTool
import com.intellij.python.pytools.common.FusId
import com.jetbrains.python.project.PyProject
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.add.v2.PathHolder

/** The SDK data of a conda env, named or `-p`-created. */
internal class CondaSdkDataProvider : PySdkDataProvider {
  override val manager: FusId get() = CondaPyTool.getInstance().fusId

  override suspend fun <P : PathHolder> sdkDataOf(
    pyProject: PyProject,
    envRef: PyEnvRef,
    pythonBinary: P,
    fileSystem: FileSystem<P>,
  ): PythonSdkAdditionalData {
    // The env ref is the name of a named env, or the path of an unnamed one. A conda env name has no path separator.
    val home = fileSystem.resolvePythonHome(pythonBinary)
    val envPath = home.toStringForExecution()
    val isPath = envRef.value.any { it == '/' || it == '\\' }
    val identity = if (isPath) PyCondaEnvIdentity.UnnamedEnv(envPath, isBase = isCondaRoot(fileSystem, home))
    else PyCondaEnvIdentity.NamedEnv(envRef.value, envPath)
    // conda is resolved again when it runs, so a path that does not resolve here is not stored.
    val condaPath = fileSystem.resolveExecutable(CondaPyTool.getInstance())?.toStringForExecution().orEmpty()
    return condaSdkDataOf(PyCondaEnv(identity, condaPath), pyProject.baseDir)
  }

  /**
   * Whether [home] is the root of a conda installation, which is its base env. Only the root has `condabin` and `envs`,
   * the same rule as `CondaEnvironment.isBase`. The base env is always unnamed in conda's list, so only an unnamed env
   * can be it.
   */
  private suspend fun <P : PathHolder> isCondaRoot(fileSystem: FileSystem<P>, home: P): Boolean =
    fileSystem.directoryExists(fileSystem.resolveChild(home, "condabin")) || fileSystem.directoryExists(fileSystem.resolveChild(home, "envs"))
}

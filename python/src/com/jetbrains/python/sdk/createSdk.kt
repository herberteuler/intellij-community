// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk

import com.intellij.python.sdk.backend.interpreterRefOf
import com.intellij.python.sdk.backend.registerTarget
import com.intellij.python.sdk.common.PyInterpreterRef
import java.nio.file.Path
import com.intellij.execution.target.FullPathOnTarget
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.python.sdk.backend.PythonInterpreter
import com.intellij.python.sdk.backend.PythonInterpreterRegistry
import com.intellij.python.sdk.backend.getSdkAPI
import com.intellij.python.sdk.backend.pythonInterpreterAsync
import com.jetbrains.python.sdk.legacy.PythonSdkUtil
import com.jetbrains.python.PyBundle
import com.jetbrains.python.PythonBinary
import com.jetbrains.python.Result
import com.jetbrains.python.errorProcessing.MessageError
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.sdk.add.v2.EelFileSystem
import com.jetbrains.python.sdk.add.v2.FileSystem
import com.jetbrains.python.sdk.add.v2.PathHolder
import com.jetbrains.python.sdk.flavors.CPythonSdkFlavor
import com.jetbrains.python.sdk.flavors.PyFlavorAndData
import com.jetbrains.python.sdk.flavors.PyFlavorData
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor
import com.jetbrains.python.target.PyTargetAwareAdditionalData
import com.jetbrains.python.target.ui.TargetPanelExtension
import com.jetbrains.python.project.PyProject
import com.jetbrains.python.project.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus

// Those are tools to create SDK
// As PyCharm developer, do not call `addSdk` directly: use these tools only.


/**
 * Request to create a sdk either eel or target-based.
 * Once created, call [createSdk]
 */
internal sealed interface SdkCreationRequest<P, D : SdkAdditionalData> {
  val path: P
  val data: D

  data class EelSdk(
    override val path: PythonBinary,
    override val data: PythonSdkAdditionalData,
  ) : SdkCreationRequest<PythonBinary, PythonSdkAdditionalData>

  data class TargetSdk(
    override val path: FullPathOnTarget,
    override val data: PyTargetAwareAdditionalData,
  ) : SdkCreationRequest<FullPathOnTarget, PyTargetAwareAdditionalData>
}

/**
 * Advanced options, do not change them unless you know what you are doing.
 *
 * [setupPaths] means "to calculate various SDK paths", call SDK updater and so on.
 *
 * [persist] `false` creates an SDK outside the SDK table. Only the old test venv fixture uses it. Remove it when that
 * fixture is gone, because [PythonInterpreterRegistry] adds every SDK to the table.
 */
@ApiStatus.Internal
data class SdkCreationAdvancedOpts(
  internal val persist: Boolean = true,
  val setupPaths: Boolean = true,
) {
  companion object {
    val DEFAULT: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts()
  }
}

/**
 * Kinda low-level API to create SDK for [pyProject]. Use [com.jetbrains.python.sdk.add.v2.FileSystem.setupSdk] if
 * possible.
 */
@ApiStatus.Internal
suspend fun createSdk(
  pyProject: PyProject,
  pythonBinaryPath: PathHolder.Eel,
  sdkAdditionalData: PythonSdkAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): PyResult<PythonInterpreter> =
  createSdkImpl(pyProject, SdkCreationRequest.EelSdk(pythonBinaryPath.path, sdkAdditionalData), suggestedSdkName, advancedOpts)

/**
 * Kinda low-level API to create SDK for [pyProject]. Use [com.jetbrains.python.sdk.add.v2.FileSystem.setupSdk] if
 * possible.
 */
@ApiStatus.Internal
suspend fun createSdk(
  pyProject: PyProject,
  pythonBinaryPath: PathHolder.Target,
  sdkAdditionalData: PyTargetAwareAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): PyResult<PythonInterpreter> =
  createSdkImpl(pyProject, SdkCreationRequest.TargetSdk(pythonBinaryPath.pathString, sdkAdditionalData), suggestedSdkName, advancedOpts)

/**
 * [createSdk] for a caller that has no [PyProject] yet. Prefer the [PyProject] overload. See
 * [PythonInterpreterRegistry.addPythonInterpreterWithoutPyProject].
 */
@ApiStatus.Internal
@ApiStatus.Obsolete
suspend fun createSdk(
  project: Project,
  pythonBinaryPath: PathHolder.Eel,
  sdkAdditionalData: PythonSdkAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): PyResult<PythonInterpreter> =
  createSdkImpl(project, SdkCreationRequest.EelSdk(pythonBinaryPath.path, sdkAdditionalData), suggestedSdkName, advancedOpts)

/**
 * [createSdk] for a caller that has no [PyProject] yet. Prefer the [PyProject] overload. See
 * [PythonInterpreterRegistry.addPythonInterpreterWithoutPyProject].
 */
@ApiStatus.Internal
@ApiStatus.Obsolete
suspend fun createSdk(
  project: Project,
  pythonBinaryPath: PathHolder.Target,
  sdkAdditionalData: PyTargetAwareAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): PyResult<PythonInterpreter> =
  createSdkImpl(project, SdkCreationRequest.TargetSdk(pythonBinaryPath.pathString, sdkAdditionalData), suggestedSdkName, advancedOpts)

/**
 * [createSdk] for the [PyProject] of [moduleOrProject], or an interpreter without a [PyProject] when it has none.
 */
@ApiStatus.Internal
suspend fun createSdk(
  moduleOrProject: ModuleOrProject,
  pythonBinaryPath: PathHolder.Eel,
  sdkAdditionalData: PythonSdkAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): PyResult<PythonInterpreter> {
  val pyProject = moduleOrProject.findPyProject()
  return if (pyProject != null) createSdk(pyProject, pythonBinaryPath, sdkAdditionalData, suggestedSdkName, advancedOpts)
  else createSdk(moduleOrProject.project, pythonBinaryPath, sdkAdditionalData, suggestedSdkName, advancedOpts)
}

/**
 * [createSdk] for the [PyProject] of [moduleOrProject], or an interpreter without a [PyProject] when it has none.
 */
@ApiStatus.Internal
suspend fun createSdk(
  moduleOrProject: ModuleOrProject,
  pythonBinaryPath: PathHolder.Target,
  sdkAdditionalData: PyTargetAwareAdditionalData,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): PyResult<PythonInterpreter> {
  val pyProject = moduleOrProject.findPyProject()
  return if (pyProject != null) createSdk(pyProject, pythonBinaryPath, sdkAdditionalData, suggestedSdkName, advancedOpts)
  else createSdk(moduleOrProject.project, pythonBinaryPath, sdkAdditionalData, suggestedSdkName, advancedOpts)
}


/**
 * Please use [com.jetbrains.python.sdk.add.v2.FileSystem.setupSdk] instead
 */
internal suspend fun SdkCreationRequest<*, *>.createSdk(
  moduleOrProject: ModuleOrProject,
  suggestedSdkName: String? = null,
  advancedOpts: SdkCreationAdvancedOpts = SdkCreationAdvancedOpts.DEFAULT,
): PyResult<PythonInterpreter> {
  val pyProject = moduleOrProject.findPyProject()
  return if (pyProject != null) createSdkImpl(pyProject, this, suggestedSdkName, advancedOpts)
  else createSdkImpl(moduleOrProject.project, this, suggestedSdkName, advancedOpts)
}


/**
 * Use this API only if you do not know SDK type in advance (in most cases you do, please prefer [createSdk]).
 * This function creates and persists SDL
 */
@ApiStatus.Internal
suspend fun createLocalSdkGuessingTypeByPath(
  homePath: PythonBinary,
  moduleOrProject: ModuleOrProject,
  suggestedSdkName: String? = null,
): PyResult<PythonInterpreter> =
  createSdkGuessingTypeByPath(PathHolder.Eel(homePath),
                              EelFileSystem(homePath.getEelDescriptor().toEelApi()),
                              moduleOrProject,
                              null,
                              suggestedSdkName)


/**
 * Use this API only if you do not know SDK type in advance (in most cases you do, please prefer [createSdk])
 */
internal suspend fun <P : PathHolder> createSdkGuessingTypeByPath(
  homePath: P,
  fileSystem: FileSystem<P>,
  moduleOrProject: ModuleOrProject,
  targetPanelExtension: TargetPanelExtension?,
  suggestedSdkName: String? = null,
): PyResult<PythonInterpreter> {
  val flavorAndData = fileSystem.flavorAndDataOf(homePath)

  val workingDirectory = moduleOrProject.workingDirectory
                         ?: return PyResult.localizedError(PyBundle.message("python.sdk.project.working.directory.not.found"))

  val newPythonInterpreter = fileSystem.setupSdk(
    moduleOrProject = moduleOrProject,
    pythonBinaryPath = homePath,
    sdkAdditionalData = PythonSdkAdditionalData(
      flavorAndData,
      workingDirectory,
    ),
    targetPanelExtension = targetPanelExtension,
    suggestedSdkName = suggestedSdkName
  ).getOr { return it }

  moduleOrProject.project.excludeInnerVirtualEnv(newPythonInterpreter.getSdkAPI())

  return PyResult.success(newPythonInterpreter)
}

/**
 * An interpreter is added by its ref: the provider whose node owns [SdkCreationRequest.data] names the environment,
 * and then builds the SDK data itself, see [PythonInterpreterRegistry.addPythonInterpreter]. So the data and the
 * name the caller built are not stored. A target is registered in the project first, so the ref can name it.
 */
private suspend fun createSdkImpl(
  pyProject: PyProject,
  request: SdkCreationRequest<*, *>,
  suggestedSdkName: String?,
  advancedOpts: SdkCreationAdvancedOpts,
): PyResult<PythonInterpreter> {
  if (!advancedOpts.persist) {
    val homePath = request.homePath().getOr { return it }
    return Result.success(createSdkOutsideTable(homePath, request.data, suggestedSdkName, advancedOpts))
  }
  val ref = when (request) {
    is SdkCreationRequest.EelSdk -> interpreterRefOf(PathHolder.Eel(request.path), request.data, PyInterpreterRef.Mode.Native)
    is SdkCreationRequest.TargetSdk -> {
      val target = request.data.targetEnvironmentConfiguration
                   ?: return PyResult.localizedError(PyBundle.message("python.sdk.python.executable.not.found", request.path))
      registerTarget(pyProject.project, target)
      interpreterRefOf(PathHolder.Target(request.path), request.data, PyInterpreterRef.Mode.Target(target.uuid))
    }
  }
  return PythonInterpreterRegistry.getInstance(pyProject.project).addPythonInterpreter(pyProject, ref)
}

private suspend fun createSdkImpl(
  project: Project,
  request: SdkCreationRequest<*, *>,
  suggestedSdkName: String?,
  advancedOpts: SdkCreationAdvancedOpts,
): PyResult<PythonInterpreter> {
  val homePath = request.homePath().getOr { return it }
  if (!advancedOpts.persist) return Result.success(createSdkOutsideTable(homePath, request.data, suggestedSdkName, advancedOpts))
  val interpreter = PythonInterpreterRegistry.getInstance(project)
    .addPythonInterpreterWithoutPyProject(homePath, request.pythonData, suggestedSdkName, advancedOpts.setupPaths)
  return Result.success(interpreter)
}

/** The data of the SDK. Both kinds of request carry [PythonSdkAdditionalData]. */
private val SdkCreationRequest<*, *>.pythonData: PythonSdkAdditionalData
  get() = when (this) {
    is SdkCreationRequest.EelSdk -> data
    is SdkCreationRequest.TargetSdk -> data
  }

/** The home path of the SDK: the VFS path of a local binary, or the path on the target. */
private suspend fun SdkCreationRequest<*, *>.homePath(): Result<String, MessageError> {
  val homePath = when (val request = this) {
    is SdkCreationRequest.EelSdk -> {
      val pythonBinaryVirtualFile = withContext(Dispatchers.IO) {
        VirtualFileManager.getInstance().refreshAndFindFileByNioPath(request.path)
      } ?: return PyResult.localizedError(PyBundle.message("python.sdk.python.executable.not.found", request.path))
      pythonBinaryVirtualFile.path
    }
    is SdkCreationRequest.TargetSdk -> request.path
  }
  return Result.success(homePath)
}

/** The SDK of [SdkCreationAdvancedOpts.persist] `false`: it is not in the SDK table, so the registry does not know it. */
private suspend fun createSdkOutsideTable(
  homePath: String,
  data: SdkAdditionalData,
  suggestedSdkName: String?,
  advancedOpts: SdkCreationAdvancedOpts,
): PythonInterpreter {
  val sdkType = PythonSdkType.getInstance()
  @Suppress("SETUP_SDK_DIRECTLY") // The registry creates every other SDK.
  val sdk = SdkConfigurationUtil.createSdk(PythonSdkUtil.getAllSdks(), homePath, sdkType, data, suggestedSdkName)
  if (advancedOpts.setupPaths) sdkType.setupSdkPaths(sdk)
  return sdk.pythonInterpreterAsync()
}

/**
 * The flavor of the local interpreter at [pythonBinary], guessed from its path. Only a flavor without data can be
 * guessed: a conda env, for example, needs the path to conda.
 */
internal suspend fun guessLocalFlavorAndData(pythonBinary: Path): PyFlavorAndData<PyFlavorData.Empty, *> = withContext(Dispatchers.IO) {
  val detectedFlavor = PythonSdkFlavor.tryDetectFlavorByLocalPath(pythonBinary)
  val flavor = if (detectedFlavor != null && detectedFlavor.flavorDataClass.isInstance(PyFlavorData.Empty)) {
    @Suppress("UNCHECKED_CAST") // Checked a line above
    detectedFlavor as CPythonSdkFlavor<PyFlavorData.Empty>
  }
  else {
    PythonSdkFlavor.UnknownFlavor.INSTANCE
  }
  PyFlavorAndData(PyFlavorData.Empty, flavor)
}

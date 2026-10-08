// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.venv.sdk.evolution

import com.intellij.python.sdk.common.PyInterpreterRef
import com.intellij.python.sdk.backend.evolution.ownedEnvDirOf
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.python.community.common.tools.ToolId
import com.intellij.python.community.services.systemPython.createVenvFromSystemPython
import com.intellij.python.pytools.backend.PyTool
import com.intellij.python.sdk.backend.PySdkBundle
import com.intellij.python.sdk.backend.PythonInterpreter
import com.intellij.python.sdk.backend.evolution.DiscoveredVenv
import com.intellij.python.sdk.backend.evolution.EvoRecreateSpec
import com.intellij.python.sdk.backend.evolution.EvoToolContext
import com.intellij.python.sdk.backend.evolution.PyEvoEnvironmentProvider
import com.intellij.python.sdk.backend.evolution.envExistsError
import com.intellij.python.sdk.backend.evolution.firstFreeVenvDir
import com.intellij.python.sdk.backend.evolution.listEntryNames
import com.intellij.python.sdk.backend.evolution.resolveNewVenvDir
import com.intellij.python.sdk.backend.evolution.toInProjectAndOtherSections
import com.intellij.python.sdk.common.EvoRowAction
import com.intellij.python.sdk.common.evolution.EvoAddNewDto
import com.intellij.python.sdk.common.evolution.EvoLeafDto
import com.intellij.python.sdk.common.evolution.EvoLoadResultDto
import com.intellij.python.sdk.common.evolution.EvoRecreateDto
import com.intellij.python.sdk.common.evolution.EvoSectionDto
import com.intellij.python.venv.PipPyTool
import com.intellij.python.venv.createVenv
import com.intellij.python.venv.common.icons.PythonVenvCommonIcons
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.sdk.ModuleOrProject
import com.jetbrains.python.sdk.add.v2.PathHolder
import com.jetbrains.python.sdk.configuration.VENV_TOOL_ID
import com.jetbrains.python.sdk.createSdkGuessingTypeByPath
import com.jetbrains.python.sdk.evolution.deleteEnvDir
import java.nio.file.Path
import javax.swing.Icon
import kotlin.io.path.exists
import kotlin.io.path.pathString

/**
 * Contributes the generic "pip" (virtualenv) node — every virtualenv the discovery found. Always available, since a
 * virtualenv needs no tool beyond a Python: there is no pip *tool* to detect. [PipPyTool] is still the manager of its
 * environments, so their keys name `pip`.
 *
 * It lives here rather than in `intellij.python.venv` because creating a venv from a system Python
 * ([createVenvFromSystemPython]) sits *above* that module by design — `services.systemPython` depends on it — so a
 * provider there could not reach its own creation logic without a cycle.
 */
internal class VenvEvoEnvironmentProvider : PyEvoEnvironmentProvider {
  override val tool: PyTool get() = PipPyTool.getInstance()

  override val toolId: ToolId get() = VENV_TOOL_ID

  override val label: String get() = PySdkBundle.message("evolution.node.label.pip")
  override val icon: Icon get() = PythonVenvCommonIcons.VirtualEnv

  /** A virtualenv needs only a Python, so this node is always there. */
  override suspend fun isAvailable(context: EvoToolContext): Boolean = true

  /**
   * Every discovered virtualenv, one made by another tool included.
   *
   * This node used to hide a uv-made environment, which left a project whose only environment came from uv with an empty
   * pip node. It is listed instead, drawn with the icon of the tool that made it and disabled there, so the environment
   * is visible where the user looks for it and is still adopted on the node of the tool that manages it.
   *
   * The node claims no mark of its own: it creates its environments with the standard library's `venv`, which writes
   * nothing into `pyvenv.cfg` to say so. An environment naming no tool therefore reads as this node's own.
   */
  override suspend fun loadSections(context: EvoToolContext, discovered: List<DiscoveredVenv>): EvoLoadResultDto {
    return EvoLoadResultDto.Ok(discovered.toInProjectAndOtherSections(
      owner = this,
      pyProject = context.workspace.pyProject,
      icon = icon,
      label = PySdkBundle.message("evolution.section.in.project"),
    ))
  }

  override val stepDescription: String get() = PySdkBundle.message("evolution.node.step.pip")

  /** The system Pythons a not-yet-created environment could be built on — see `UvEvoEnvironmentProvider.decorate`. */
  override suspend fun decorate(context: EvoToolContext, result: EvoLoadResultDto): EvoLoadResultDto {
    if (result !is EvoLoadResultDto.Ok) return result
    val options = context.systemPythonOptions().takeIf { it.isNotEmpty() } ?: return result
    return result.copy(sections = result.sections.map { section ->
      section.copy(leaves = section.leaves.map { leaf ->
        if (leaf.action is EvoRowAction.CreateEnv) leaf.copy(createVersions = options) else leaf
      })
    })
  }

  /** Creates a virtualenv from the system Python named by `token`, then types its SDK by path. */
  override suspend fun createSdkForNewEnv(context: EvoToolContext, ref: EvoRowAction.CreateEnv): PyResult<PythonInterpreter> {
    val venvDir = context.resolveNewVenvDir(ref)
    if (venvDir.exists()) return envExistsError(venvDir.fileName.toString())
    return createVenvIn(context, venvDir, ref.token)
  }

  /**
   * Every virtualenv on this node may be rebuilt, on any system Python the machine has.
   *
   * The list is the one [addNewEnvSpec] offers, since a rebuild picks its base the same way a create does. No packages
   * choice: pip has no lock file to read one from, and a `requirements.txt` lying beside the project is a guess rather
   * than a record of what the environment held.
   *
   * This node keeps no record of what it made — a plain virtualenv names no tool — so its only way to tell an
   * environment it may destroy from one it merely found is where the environment sits. Hence [ownedEnvDirOf]: an
   * interpreter outside the project belongs to something else, whatever put it there.
   */
  override suspend fun recreateSpecFor(context: EvoToolContext, leaf: EvoLeafDto): EvoRecreateDto? {
    ownedEnvDirOf(context, leaf.action) ?: return null
    val options = context.systemPythonOptions().takeIf { it.isNotEmpty() } ?: return null
    return EvoRecreateDto(options = options, canSyncPackages = false)
  }

  /**
   * Deletes the environment and builds another in its place, since `venv` has no command that replaces one.
   *
   * The delete comes first and its failure ends this: building over a directory that refused to go would leave the two
   * environments mixed together in one folder.
   */
  override suspend fun recreateEnv(context: EvoToolContext, ref: PyInterpreterRef, spec: EvoRecreateSpec): PyResult<PythonInterpreter> {
    val venvDir = envDirectory(context.workspace.pyProject, ref.envRef, context.fileSystem)
                  ?: return PyResult.localizedError(PySdkBundle.message("evolution.error.env.not.found", ref.envRef))
    deleteEnvDir(venvDir).getOr { return it }
    return createVenvIn(context, venvDir, spec.baseToken)
  }

  /**
   * Creates a virtualenv in [venvDir] from the interpreter at [baseToken], then types its SDK by path.
   *
   * The interpreter is used as the path it is. It used to be looked up in the machine-wide interpreter scan first, only
   * for that scan's answer to be unwrapped back to the same path — `createVenvFromSystemPython` reads nothing else off
   * it — and an interpreter the scan did not list could then not build an environment at all, however the user had come
   * by it. `createVenv` validates the interpreter itself, so nothing was checked there either.
   */
  private suspend fun createVenvIn(context: EvoToolContext, venvDir: Path, baseToken: String): PyResult<PythonInterpreter> {
    val basePython = baseToken.toNioPathOrNull()
                     ?: return PyResult.localizedError(PySdkBundle.message("evolution.error.base.python.not.found", baseToken))
    val venvPython = createVenv(basePython, venvDir).getOr { return it }
    return createSdkGuessingTypeByPath(
      PathHolder.Eel(venvPython),
      context.fileSystem,
      ModuleOrProject.ModuleAndProject(context.pyProject.pyProject),
      null,
    )
  }

  /**
   * The env folder is created inside the section's containing directory, named by the first free `.venv{X}` there and
   * editable — the same shape uv offers, since both create a folder rather than a named environment.
   *
   * Taken names are *every* existing entry in that directory, not only virtualenvs: any file or folder of the same name
   * would block creating the env there.
   */
  override suspend fun addNewEnvSpec(context: EvoToolContext, section: EvoSectionDto): EvoAddNewDto? {
    val options = context.systemPythonOptions().takeIf { it.isNotEmpty() } ?: return null
    val container = section.addNewFolderPath?.let { Path.of(it) } ?: context.workspace.baseDir
    val taken = listEntryNames(container)
    return EvoAddNewDto(
      name = firstFreeVenvDir(container).fileName.toString(),
      path = container.pathString,
      options = options,
      nameEditable = true,
      takenNames = taken,
    )
  }
}

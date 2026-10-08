package com.intellij.python.hatch.sdk.evolution

import kotlin.io.path.isRegularFile
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.intellij.python.sdk.backend.evolution.envNotFound
import com.intellij.python.hatch.PythonVirtualEnvironment
import com.intellij.python.hatch.HATCH_TOML
import com.intellij.python.pyproject.PY_PROJECT_TOML
import com.intellij.python.community.execService.UploadConfig
import com.intellij.python.sdk.common.PyEnvRef
import com.jetbrains.python.project.PyProject
import com.intellij.python.sdk.common.PyInterpreterRef
import com.intellij.python.sdk.backend.evolution.interpreterRefOf
import com.intellij.python.sdk.backend.evolution.PyEvoEnvironmentProvider
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.python.community.common.tools.ToolId
import com.intellij.python.hatch.HatchPyTool
import com.intellij.python.hatch.HatchService
import com.intellij.python.hatch.HatchVirtualEnvironment
import com.intellij.python.hatch.PyHatchBundle
import com.intellij.python.hatch.cli.HatchEnv
import com.intellij.python.hatch.getHatchService
import com.intellij.python.hatch.impl.HATCH_TOOL_ID
import com.intellij.python.hatch.common.icons.PythonHatchCommonIcons
import com.intellij.python.pytools.backend.PyTool
import com.intellij.python.sdk.backend.evolution.DiscoveredVenv
import com.intellij.python.sdk.backend.evolution.EvoRecreateSpec
import com.intellij.python.sdk.backend.evolution.EvoToolContext
import com.intellij.python.sdk.backend.evolution.NO_VERSION
import com.intellij.python.sdk.backend.evolution.evoCreateEnvLeaf
import com.intellij.python.sdk.backend.evolution.evoEnvLeaf
import com.intellij.python.sdk.backend.evolution.evoWarning
import com.intellij.python.sdk.common.evolution.EvoLeafDto
import com.intellij.python.sdk.common.evolution.EvoLoadResultDto
import com.intellij.python.sdk.common.evolution.EvoRecreateDto
import com.intellij.python.sdk.common.evolution.EvoSectionDto
import com.intellij.python.sdk.common.EvoRowAction
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.getOrNull
import com.jetbrains.python.hatch.sdk.createSdk
import com.jetbrains.python.sdk.add.v2.FileSystem
import com.jetbrains.python.sdk.add.v2.PathHolder
import com.intellij.python.sdk.backend.PySdkBundle
import com.intellij.python.sdk.backend.resolvePythonBinary
import java.nio.file.Path
import com.intellij.python.sdk.backend.PythonInterpreter


/** How long hatch's environment listing stands before hatch is asked again — see `listEnvironments`. */
private const val ENVS_TTL_MS: Long = 30_000

internal class HatchEvoEnvironmentProvider : PyEvoEnvironmentProvider {
  override val tool: PyTool get() = HatchPyTool.getInstance()
  override val label: String get() = PySdkBundle.message("evolution.node.label.hatch")
  override val icon get() = PythonHatchCommonIcons.Logo
  override val toolId: ToolId get() = HATCH_TOOL_ID

  override val stepDescription: String get() = PySdkBundle.message("evolution.node.step.hatch")

  override suspend fun loadSections(context: EvoToolContext, discovered: List<DiscoveredVenv>): EvoLoadResultDto {
    val hatchService = context.pyProject.pyProject.residesOnModule.getHatchService(context.fileSystem).getOrNull()
                       ?: return evoWarning(PyHatchBundle.message("evolution.hatch.executable.is.not.found"))
    val environments = hatchService.listEnvironments().takeIf { it.isNotEmpty() } ?: return EvoLoadResultDto.Ok(emptyList())
    val leaves = environments.map { env ->
      val binary = env.pythonVirtualEnvironment?.pythonHomePath?.path?.resolvePythonBinary()
      // Materialized env → select it; a declared-but-not-created env → create it on click (token = env name).
      // A not-created env carries its declared `python` option, which [decorate] turns into the version picker.
      if (binary != null) evoEnvLeaf(title = env.hatchEnvironment.name, pythonBinary = binary, envRef = PyEnvRef(env.hatchEnvironment.name))
      else evoCreateEnvLeaf(title = env.hatchEnvironment.name, token = env.hatchEnvironment.name, icon = icon,
                            name = env.hatchEnvironment.pythonSpec?.versionSpecifiers)
    }
    return EvoLoadResultDto.Ok(listOf(EvoSectionDto(label = null, leaves = leaves)))
  }

  /**
   * The interpreter of the declared env at [envRef], which is its name, or a failure when hatch has not created it.
   * hatch runs on the machine of [fileSystem]; on a remote one the hatch files of the project are uploaded first.
   */
  override suspend fun <P : PathHolder> pythonBinaryOf(pyProject: PyProject, envRef: PyEnvRef, fileSystem: FileSystem<P>): PyResult<P> {
    val baseDir = pyProject.baseDir
    val uploadConfig = if (fileSystem.isLocal) null
    else withContext(Dispatchers.IO) { UploadConfig(relativePaths = listOf(PY_PROJECT_TOML, HATCH_TOML).filter { baseDir.resolve(it).isRegularFile() }) }
    val hatchService = baseDir.getHatchService(fileSystem, uploadBeforeExecution = uploadConfig).getOr { return it }
    val env = hatchService.findVirtualEnvironments().getOr { return it }.firstOrNull { it.hatchEnvironment.name == envRef.value }
              ?: return envNotFound(envRef)
    val home = when (val venv = env.pythonVirtualEnvironment) {
      is PythonVirtualEnvironment.Existing -> venv.pythonHomePath
      is PythonVirtualEnvironment.NotExisting, null -> return envNotFound(envRef)
    }
    return withContext(Dispatchers.IO) { fileSystem.resolvePythonBinary(home) }?.let { PyResult.success(it) } ?: envNotFound(envRef)
  }

  /**
   * Materializes a declared-but-not-created hatch env, then builds its SDK.
   *
   * Two shapes reach here, told apart by whether `folder` is set — the version picker [decorate] attaches puts the env
   * name in `folder` and the chosen base Python in `token`, while a row with no picker carries only the env name in
   * `token` and falls back to whichever system Python is found first.
   */
  override suspend fun createSdkForNewEnv(context: EvoToolContext, ref: EvoRowAction.CreateEnv): PyResult<PythonInterpreter> {
    val envName = ref.folder ?: ref.token
    val hatchService = context.pyProject.pyProject.residesOnModule.getHatchService(context.fileSystem).getOr { return it }
    val hatchEnv = hatchService.findVirtualEnvironments().getOr { return it }
                     .firstOrNull { it.hatchEnvironment.name == envName }?.hatchEnvironment
                   ?: return PyResult.localizedError(PySdkBundle.message("evolution.error.env.not.found", envName))
    val basePython = if (ref.folder != null) {
      // The picker put a base interpreter path in the token; an unparseable one is a broken round-trip, not user input.
      ref.token.toNioPathOrNull()
      ?: return PyResult.localizedError(PySdkBundle.message("evolution.error.base.python.not.found", ref.token))
    }
    else {
      // No base was picked for this row, so the best of the ones the widget itself offers stands in. Asked through the
      // context rather than of the interpreter scan directly: the core decides where that list comes from, and where uv
      // is installed it is uv's — so the fallback is one of the interpreters the user was being shown.
      context.systemPythonOptions().firstOrNull { !it.installable }?.token?.toNioPathOrNull()
      ?: return PyResult.localizedError(PySdkBundle.message("evolution.error.base.python.not.found", ""))
    }
    val venv = hatchService.createVirtualEnvironment(PathHolder.Eel(basePython), envName).getOr { return it }
    // The listing predates this environment.
    forgetEnvironments()
    return HatchVirtualEnvironment(hatchEnv, venv).createSdk(context.workspace.moduleOrProject, hatchService.getWorkingDirectoryPath(), context.fileSystem, null)
  }

  /**
   * A materialized hatch env may be rebuilt on any base Python its own declaration admits.
   *
   * Asked per row rather than per node, because the answer really is per row: each declared env carries its own
   * `python` specifier, which constrains its bases and no other env's. An existing-env leaf does not carry that
   * specifier — only a not-yet-created one does, in its ref — so the env is looked up again here, behind the request's
   * cache so one node load makes one hatch call.
   */
  override suspend fun recreateSpecFor(context: EvoToolContext, leaf: EvoLeafDto): EvoRecreateDto? {
    val env = envFor(context, interpreterRefOf(leaf.action)?.envRef?.value ?: return null) ?: return null
    val options = context.systemPythonOptions(env.hatchEnvironment.pythonSpec?.versionSpecifiers).takeIf { it.isNotEmpty() }
                  ?: return null
    return EvoRecreateDto(options = options, canSyncPackages = true)
  }

  /**
   * Removes the env through hatch and lets hatch create it again, then fills it when asked.
   *
   * Hatch keeps its own record of an env beside the directory, so the directory alone is not the environment — this
   * goes through `hatch env remove` rather than deleting the folder.
   *
   * [HatchEnv.RemoveResult.CantRemoveActiveEnvironment] is the one outcome where nothing was destroyed, so it ends this
   * with a message instead of building over an environment that is still there. The other two non-removals mean the env
   * was already gone, which is exactly the state the create step wants.
   */
  override suspend fun recreateEnv(context: EvoToolContext, ref: PyInterpreterRef, spec: EvoRecreateSpec): PyResult<PythonInterpreter> {
    val hatchService = context.pyProject.pyProject.residesOnModule.getHatchService(context.fileSystem).getOr { return it }
    val env = envFor(context, ref.envRef.value)
              ?: return PyResult.localizedError(PySdkBundle.message("evolution.error.env.not.found", ref.envRef))
    val envName = env.hatchEnvironment.name
    when (hatchService.removeVirtualEnvironment(envName).getOr { return it }) {
      HatchEnv.RemoveResult.CantRemoveActiveEnvironment ->
        return PyResult.localizedError(PySdkBundle.message("evolution.error.hatch.env.active", envName))
      HatchEnv.RemoveResult.Removed, HatchEnv.RemoveResult.NotExists, HatchEnv.RemoveResult.NotDefinedInConfig -> Unit
    }
    val basePython = spec.baseToken.toNioPathOrNull()
                     ?: return PyResult.localizedError(PySdkBundle.message("evolution.error.base.python.not.found", spec.baseToken))
    val venv = hatchService.createVirtualEnvironment(PathHolder.Eel(basePython), envName).getOr { return it }
    forgetEnvironments()
    if (spec.syncPackages) hatchService.syncDependencies(envName).getOr { return it }
    return HatchVirtualEnvironment(env.hatchEnvironment, venv)
      .createSdk(context.workspace.moduleOrProject, hatchService.getWorkingDirectoryPath(), context.fileSystem, null)
  }

  /** The declared env named [envName], or null when none is. */
  private suspend fun envFor(context: EvoToolContext, envName: String): HatchVirtualEnvironment<PathHolder.Eel>? =
    envFor(context.pyProject.pyProject, context.fileSystem, envName)

  private suspend fun envFor(pyProject: PyProject, fileSystem: FileSystem<PathHolder.Eel>, envName: String): HatchVirtualEnvironment<PathHolder.Eel>? {
    val hatchService = pyProject.residesOnModule.getHatchService(fileSystem).getOrNull() ?: return null
    return hatchService.listEnvironments().firstOrNull { it.hatchEnvironment.name == envName }
  }

  /**
   * What hatch last reported for this working directory, asking it again only when nothing recent is held.
   *
   * Asking is expensive out of proportion to the answer: `hatch env show --json` names the environments, and hatch is
   * then run once more *per environment* to find where each one lives. A project declaring ten environments is eleven
   * processes for one listing.
   *
   * A single node load asked twice over — [loadSections] builds the rows from one listing while [envFor] takes another
   * for the same environments — and the "Recreate Environment" action asks again through a context of its own. All of
   * them now share one answer.
   *
   * [ENVS_TTL_MS] is short: an environment created outside the IDE should appear on the next opening of the widget,
   * not minutes later. Creating one through this provider clears it outright.
   */
  private suspend fun HatchService<PathHolder.Eel>.listEnvironments(): List<HatchVirtualEnvironment<PathHolder.Eel>> =
    envsLock.withLock {
      val key = getWorkingDirectoryPath()
      envsCache[key]?.takeIf { System.currentTimeMillis() - it.takenAt < ENVS_TTL_MS }?.let { return it.envs }
      val envs = findVirtualEnvironments().getOrNull().orEmpty()
      envs.also { envsCache[key] = CachedEnvs(it, System.currentTimeMillis()) }
    }

  /** Drops what hatch reported, so the next listing asks it again. */
  private suspend fun forgetEnvironments() {
    envsLock.withLock { envsCache.clear() }
  }

  private val envsLock = Mutex()
  private val envsCache = mutableMapOf<Path, CachedEnvs>()

  private class CachedEnvs(val envs: List<HatchVirtualEnvironment<PathHolder.Eel>>, val takenAt: Long)

  /**
   * Turns each declared-but-not-created env into a Python-version picker, so the user chooses the base Python instead of
   * silently getting whichever one is found first.
   *
   * Such a row has no interpreter to probe, so it also gets an explicit "n/a" in the version column the materialized
   * envs fill — which is what makes the two kinds of row distinguishable at a glance.
   */
  override suspend fun decorate(context: EvoToolContext, result: EvoLoadResultDto): EvoLoadResultDto {
    if (result !is EvoLoadResultDto.Ok) return result
    return result.copy(sections = result.sections.map { section ->
      section.copy(leaves = section.leaves.map { leaf -> leaf.withBasePythonPicker(context) })
    })
  }

  /**
   * This row with its base-Python picker attached, or unchanged when it creates no environment.
   *
   * An env's `python` option says which interpreters can build it, so the picker offers those alone. A matrix env is the
   * common case: `test.py3.11` declares `3.11`, and every other version on the machine would be the wrong answer. A
   * range such as `>=3.8` keeps every version it admits.
   *
   * The constraint is the env's own, so it replaces the project's `requires-python` and the 3.8 floor of the general
   * list rather than joining them — an env that asks for 2.7 offers 2.7.
   *
   * An env that declares no version keeps the full list, as before. See
   * [com.intellij.python.hatch.cli.HatchPythonSpec.versionSpecifiers] for the options that constrain no version.
   */
  private suspend fun EvoLeafDto.withBasePythonPicker(context: EvoToolContext): EvoLeafDto {
    val create = action as? EvoRowAction.CreateEnv ?: return this
    // `name` holds the env's declared version specifier — this provider put it there in loadSections.
    val options = context.systemPythonOptions(create.name)
    // Declared in pyproject.toml but nothing on the machine to build it from: creating it would fail, so the row says
    // so up front instead of looking creatable.
    if (options.isEmpty()) {
      return copy(unavailable = PySdkBundle.message("evolution.error.no.base.python"), secondaryText = secondaryText ?: NO_VERSION)
    }
    // No placeholder: the row now names the Python it would be built on, and "n/a" would hide it.
    return copy(createVersions = options)
  }
}

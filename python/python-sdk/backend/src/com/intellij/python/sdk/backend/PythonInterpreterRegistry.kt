// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.sdk.backend

import com.jetbrains.python.errorProcessing.PyResult
import kotlinx.coroutines.flow.drop
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.Disposable
import com.intellij.python.sdk.common.PyInterpreterRef
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.jetbrains.python.sdk.PythonSdkAdditionalData
import com.jetbrains.python.sdk.legacy.PythonSdkUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.projectRoots.impl.SdkConfigurationUtil
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import com.jetbrains.python.PyNames
import com.jetbrains.python.PythonBinary
import com.jetbrains.python.sdk.PyRemoteSdkAdditionalDataMarker
import com.jetbrains.python.sdk.pythonBinaryPath
import kotlinx.coroutines.flow.distinctUntilChanged
import com.jetbrains.python.project.PyProject
import com.jetbrains.python.project.project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.nio.file.Path
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

/**
 * The interpreters of each [PyProject] of this project, and the one way to add or remove a Python SDK.
 *
 * Every function takes the [PyProject] that the interpreter belongs to. There are no shared interpreters. An
 * interpreter is found by its [PyInterpreterRef].
 *
 * For now the storage is the SDK table of the application. An SDK belongs to the [PyProject] whose base directory is its
 * associated path. An SDK with no associated path belongs to the [PyProject] whose base directory is its working
 * directory. Any other SDK belongs to no [PyProject], so this registry does not show it.
 */
@Service(Service.Level.PROJECT)
@ApiStatus.Internal
class PythonInterpreterRegistry(@Suppress("UNUSED_PARAMETER") project: Project, private val scope: CoroutineScope) {
  /**
   * One computed set. It compares by reference, so each recompute emits. [PythonInterpreter] compares by its SDK alone,
   * so the set after a rename equals the set before it.
   */
  private class Computed(val interpreters: Set<PythonInterpreter>)

  private val state = MutableStateFlow<Computed?>(null)

  init {
    // A change made outside this registry, for example by the platform Settings, reaches the set through this listener.
    scope.launch {
      sdkTableChanges()
        .onStart { emit(Unit) }
        .conflate()
        .collect { state.value = Computed(compute()) }
    }
  }

  /** Every Python SDK of the table, each time the table changes. The first value comes after the first computation. */
  private val tableFlow: Flow<Set<PythonInterpreter>> = state.filterNotNull().map { it.interpreters }

  /** The interpreters of [pyProject]. Waits for the first computation. */
  suspend fun interpreters(pyProject: PyProject): Set<PythonInterpreter> = interpretersFlow(pyProject).first()

  /** [interpreters] each time they change. The first value comes after the first computation. */
  fun interpretersFlow(pyProject: PyProject): Flow<Set<PythonInterpreter>> =
    tableFlow.map { it.belongingTo(pyProject) }.distinctUntilChanged()

  /** The interpreter of [pyProject] that [ref] names, or `null` when [pyProject] has none. Waits for the first computation. */
  private suspend fun findPythonInterpreter(pyProject: PyProject, ref: PyInterpreterRef): PythonInterpreter? =
    interpreters(pyProject).firstOrNull { it.ref == ref }

  /**
   * Adds the interpreter that [ref] names to [pyProject], and returns it. The only way a [PyProject] gets an
   * interpreter.
   *
   * [pyProject] keeps an interpreter it already has. Otherwise the file system of [ref] gives the SDK home path
   * and the SDK data, see [com.jetbrains.python.sdk.add.v2.FileSystem.sdkHomeAndData]. The new SDK is associated with the base directory of
   * [pyProject], so it belongs to [pyProject]. A local interpreter that already has an SDK of [pyProject], or an SDK of
   * no [PyProject], keeps it. That SDK takes the new data and keeps its name.
   *
   * It also sets up the SDK paths. When that fails, it removes the SDK it added.
   */
  suspend fun addPythonInterpreter(pyProject: PyProject, ref: PyInterpreterRef): PyResult<PythonInterpreter> {
    findPythonInterpreter(pyProject, ref)?.let { return PyResult.success(it) }
    val fileSystem = ref.fileSystem(pyProject.project)
                     ?: return PyResult.localizedError(PySdkBundle.message("evolution.error.env.not.found", ref.toString()))
    val (homePath, sdkData) = fileSystem.sdkHomeAndData(pyProject, ref).getOr { return it }
    sdkData.associatedModulePath = pyProject.baseDir.toString()
    return PyResult.success(addInterpreter(homePath, sdkData, suggestedName = null, setupPaths = true) { it == null || it == pyProject.baseDir })
  }

  /**
   * Adds an SDK that the caller built itself, for a flow that has no [PyProject] yet: the welcome screen, the misc
   * project, the new project wizard, an interpreter from an environment variable, and Bazel. The SDK is not associated. It belongs to
   * the [PyProject] whose base directory is the working directory of [data], when that [PyProject] appears.
   */
  @ApiStatus.Obsolete
  suspend fun addPythonInterpreterWithoutPyProject(
    homePath: String,
    data: PythonSdkAdditionalData,
    suggestedName: String? = null,
    setupPaths: Boolean = true,
  ): PythonInterpreter {
    val owner = data.workingDirectory.takeIf { data.hasValidWorkingDirectory() }
    return addInterpreter(homePath, data, suggestedName, setupPaths) { it == null || it == owner }
  }

  /** Adds an interpreter. A local SDK whose owner directory passes [canAdopt] is reused. See [ownerDirectory]. */
  private suspend fun addInterpreter(
    homePath: String,
    data: PythonSdkAdditionalData,
    suggestedName: String?,
    setupPaths: Boolean,
    canAdopt: (Path?) -> Boolean,
  ): PythonInterpreter {
    val sdkType = SdkType.findByName(PyNames.PYTHON_SDK_ID_NAME) ?: error("The Python SDK type is not registered")
    val existingSdks = PythonSdkUtil.getAllSdks()
    if (data !is PyRemoteSdkAdditionalDataMarker) {
      val adoptable = existingSdks.filter { canAdopt(it.ownerDirectory()) }
      findSdkToAdopt(Path.of(homePath), adoptable) { suggestedName ?: sdkType.suggestSdkName(null, homePath) }?.let { sdk ->
        edtWriteAction {
          val modificator = sdk.sdkModificator
          modificator.sdkAdditionalData = data
          modificator.commitChanges()
        }
        return awaitInterpreter(sdk)
      }
    }

    @Suppress("SETUP_SDK_DIRECTLY") // The registry is the only place that creates a Python SDK.
    val sdk = SdkConfigurationUtil.createSdk(existingSdks, homePath, sdkType, data, suggestedName)
    val interpreter = addSdk(sdk)
    if (setupPaths) {
      try {
        sdkType.setupSdkPaths(sdk)
      }
      catch (e: Throwable) {
        // Do not leave a broken SDK in the table.
        withContext(NonCancellable) { removeSdk(sdk) }
        throw e
      }
    }
    return interpreter
  }

  /**
   * Adds a prebuilt mock [sdk] to [pyProject] for a test. A mock SDK has no real interpreter, so [addPythonInterpreter]
   * cannot create it. It associates [sdk] with [pyProject]. Remove it with [removePythonInterpreter].
   */
  @TestOnly
  suspend fun addMockPythonInterpreter(pyProject: PyProject, sdk: Sdk): PythonInterpreter {
    (sdk.sdkAdditionalData as? PythonSdkAdditionalData)?.associatedModulePath = pyProject.baseDir.toString()
    return addSdk(sdk)
  }

  /**
   * Adds a prebuilt mock [sdk] for a test that has no [PyProject]. The SDK is not associated. Remove it with
   * [removePythonInterpreterWithoutPyProject].
   */
  @TestOnly
  suspend fun addMockPythonInterpreterWithoutPyProject(sdk: Sdk): PythonInterpreter = addSdk(sdk)

  /** Removes an [interpreter] that [addPythonInterpreterWithoutPyProject] added. Returns when the registry no longer holds it. */
  @ApiStatus.Obsolete
  suspend fun removePythonInterpreterWithoutPyProject(interpreter: PythonInterpreter) {
    removeSdk(interpreter.sdk)
  }

  /** Removes [interpreter] from [pyProject]. Returns when the interpreters of [pyProject] no longer hold it. */
  suspend fun removePythonInterpreter(@Suppress("UNUSED_PARAMETER") pyProject: PyProject, interpreter: PythonInterpreter) {
    removeSdk(interpreter.sdk)
  }

  /**
   * Adds [sdk] to the SDK table and returns its interpreter. The table event recomputes the set, and this function
   * returns when the set holds the interpreter, so a caller that reads the set next finds it.
   *
   * The name check and the add are one write action, so two parallel calls cannot add two SDKs with one name.
   */
  private suspend fun addSdk(sdk: Sdk): PythonInterpreter {
    edtWriteAction {
      makeSureNameIsUnique(sdk)
      ProjectJdkTable.getInstance().addJdk(sdk)
    }
    return awaitInterpreter(sdk)
  }

  /** The interpreter of [sdk], when the set holds it. */
  private suspend fun awaitInterpreter(sdk: Sdk): PythonInterpreter =
    tableFlow.mapNotNull { set -> set.firstOrNull { it.isFor(sdk) } }.first()

  /** Removes [sdk] from the SDK table. Returns when the set no longer holds its interpreter. */
  private suspend fun removeSdk(sdk: Sdk) {
    edtWriteAction { ProjectJdkTable.getInstance().removeJdk(sdk) }
    tableFlow.first { set -> set.none { it.isFor(sdk) } }
  }

  /**
   * Every Python SDK that has a ref. A broken SDK, one without a home path or a target configuration, has none: no
   * [PyProject] can address it, so it is not an interpreter. The Settings page that lists the SDK table still shows it.
   */
  private suspend fun compute(): Set<PythonInterpreter> =
    PythonSdkUtil.getAllSdks()
      .filter { it.sdkAdditionalData is PythonSdkAdditionalData && interpreterRefOf(it) != null }
      .mapTo(mutableSetOf()) { it.pythonInterpreterAsync() }

  /** An event for each change of the SDK table. The table is small, so every change starts a recompute. */
  private fun sdkTableChanges(): Flow<Unit> = callbackFlow {
    val connection = ApplicationManager.getApplication().messageBus.connect(this)
    connection.subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
      override fun jdkAdded(jdk: Sdk) = trySend(Unit).let { }

      override fun jdkRemoved(jdk: Sdk) = trySend(Unit).let { }

      override fun jdkNameChanged(jdk: Sdk, previousName: String) = trySend(Unit).let { }
    })
    awaitClose { connection.disconnect() }
  }

  /**
   * The interpreter of [sdk], whichever [PyProject] it belongs to. Waits for the first computation.
   *
   * A bridge for a caller that the platform hands an [Sdk] and no [PyProject], such as an editor or a run
   * configuration. Move it to the interpreter of its [PyProject].
   */
  @ApiStatus.Obsolete
  suspend fun interpreterOf(sdk: Sdk): PythonInterpreter? = tableFlow.first().firstOrNull { it.isFor(sdk) }

  /** [interpreterOf] without the wait. `null` also before the first computation. */
  @ApiStatus.Obsolete
  fun interpreterOfIfReady(sdk: Sdk): PythonInterpreter? = state.value?.interpreters?.firstOrNull { it.isFor(sdk) }

  /**
   * The interpreter whose SDK has the name [sdkName] that a module stores, or `null`. Waits for the first computation.
   * For the model that reads the module storage. Remove it when a module stores a [PyInterpreterRef].
   */
  @ApiStatus.Obsolete
  suspend fun findByModuleSdkName(sdkName: String): PythonInterpreter? = tableFlow.first().firstOrNull { it.sdk.name == sdkName }

  /** The SDK names of the table, each time they change. See [findByModuleSdkName]. */
  @get:ApiStatus.Obsolete
  val moduleSdkNamesFlow: Flow<Set<String>>
    get() = tableFlow.map { set -> set.mapTo(mutableSetOf()) { it.sdk.name } }.distinctUntilChanged()

  /**
   * Calls [listener] on the EDT each time the interpreters of [pyProject] change, until [parentDisposable] is disposed.
   * It does not call it for the current set. For a Java caller that cannot collect [interpretersFlow].
   */
  fun addChangeListener(pyProject: PyProject, parentDisposable: Disposable, listener: Runnable) {
    val job = scope.launch(Dispatchers.EDT) {
      interpretersFlow(pyProject).drop(1).collect { listener.run() }
    }
    Disposer.register(parentDisposable) { job.cancel() }
  }

  /**
   * Calls [listener] on the EDT each time an interpreter of any [PyProject] is added or removed, until
   * [parentDisposable] is disposed. For a Java form that lists the interpreters of a module it can change.
   */
  fun addTableChangeListener(parentDisposable: Disposable, listener: Runnable) {
    val job = scope.launch(Dispatchers.EDT) {
      tableFlow.drop(1).collect { listener.run() }
    }
    Disposer.register(parentDisposable) { job.cancel() }
  }

  companion object {
    fun getInstance(project: Project): PythonInterpreterRegistry = project.service()
  }
}

/** The interpreters that belong to [pyProject]. See [PythonInterpreterRegistry]. */
private fun Set<PythonInterpreter>.belongingTo(pyProject: PyProject): Set<PythonInterpreter> {
  val baseDir = pyProject.baseDir
  return filterTo(mutableSetOf()) { it.sdk.ownerDirectory() == baseDir }
}

/** The base directory of the [PyProject] this SDK belongs to, see [projectDirOf]. */
private fun Sdk.ownerDirectory(): Path? = (sdkAdditionalData as? PythonSdkAdditionalData)?.let { projectDirOf(it) }

/** See [PythonInterpreterRegistry.interpreterOf]. Move the caller to the interpreter of its `PyProject`. */
@ApiStatus.Internal
@ApiStatus.Obsolete
suspend fun Project.findPythonInterpreter(sdk: Sdk): PythonInterpreter? =
  PythonInterpreterRegistry.getInstance(this).interpreterOf(sdk)

/** See [PythonInterpreterRegistry.interpreterOfIfReady]. Move the caller to the interpreter of its `PyProject`. */
@ApiStatus.Internal
@ApiStatus.Obsolete
fun Project.findPythonInterpreterIfReady(sdk: Sdk): PythonInterpreter? =
  PythonInterpreterRegistry.getInstance(this).interpreterOfIfReady(sdk)

/**
 * The usual SDK that already stands for the interpreter at [pythonBinaryPath], or `null` when none does. A usual SDK is
 * one whose data carries no [PyRemoteSdkAdditionalDataMarker].
 *
 * The IDE keeps one usual SDK for each interpreter, not one for each interpreter and tool. A `.venv` that poetry
 * created, and uv then adopted, is one environment. A second SDK for it reads as a second interpreter in every list the
 * IDE shows.
 *
 * Older builds also keyed on the tool, so one path can carry several SDKs already. The SDK named [preferredName] wins,
 * because that is the name a new SDK gets here. If no SDK has that name, the first one wins, so the answer is stable.
 * [preferredName] is read only when there is more than one candidate, because it reads the file system.
 */
private fun findSdkToAdopt(pythonBinaryPath: PythonBinary, existingSdks: List<Sdk>, preferredName: () -> String): Sdk? {
  // Compared as paths, not as strings, because `c:\windows` and `c:/Windows` name one file.
  val candidates = existingSdks.filter {
    it.sdkAdditionalData !is PyRemoteSdkAdditionalDataMarker && it.pythonBinaryPath().successOrNull == pythonBinaryPath
  }
  if (candidates.size < 2) return candidates.firstOrNull()
  val name = preferredName()
  return candidates.firstOrNull { it.name == name } ?: candidates.first()
}

@RequiresWriteLock(generateAssertion = false /* IJPL-115548 */)
private fun makeSureNameIsUnique(sdk: Sdk) {
  val name = sdk.name
  var i = 1
  while (ProjectJdkTable.getInstance().findJdk(sdk.name) != null) {
    val m = sdk.sdkModificator
    m.name = "$name@$i"
    i += 1
    m.commitChanges()
  }
}


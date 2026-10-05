// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.junit5Tests.framework.env

import com.intellij.openapi.project.Project
import com.intellij.python.sdk.backend.PythonInterpreterProjectRegistry
import com.intellij.python.test.env.common.PredefinedPyEnvironments
import com.intellij.python.test.env.core.PyEnvironment
import com.intellij.python.test.env.core.PyEnvironmentSpec
import com.intellij.python.test.env.junit5.RunOnEnvironmentsExtension
import com.intellij.python.test.env.junit5.getOrCreatePyEnvironmentFactory
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.TestFixtureInitializer
import com.intellij.testFramework.junit5.fixture.testFixture

/**
 * Creates an interpreter fixture using tags from [@PyEnvTestCase][com.intellij.python.junit5Tests.framework.env.PyEnvTestCase] annotation.
 * Requires test class to be annotated with @PyEnvTestCase. The interpreter belongs to this project.
 *
 * @throws IllegalStateException if @PyEnvTestCase annotation is not found on the test class
 */
fun TestFixture<Project>.pyInterpreterFixture(): TestFixture<PyInterpreterFixture<PyEnvironment>> = testFixture { context ->
  val project = this@pyInterpreterFixture.init()
  initializedTestFixture(project, RunOnEnvironmentsExtension.getPythonEnvironment(context.extensionContext))
}

/**
 * Creates an interpreter (if you only need a python path, use [com.intellij.python.junit5Tests.framework.env.PythonBinaryPath]
 * or [com.intellij.python.community.junit5Tests.framework.conda.CondaEnv]). The interpreter belongs to this project.
 */
fun TestFixture<Project>.pyInterpreterFixture(
  env: PredefinedPyEnvironments,
): TestFixture<PyInterpreterFixture<PyEnvironment>> = pyInterpreterFixture(env.spec)

fun TestFixture<Project>.pyInterpreterFixture(
  envSpec: PyEnvironmentSpec<*>,
): TestFixture<PyInterpreterFixture<PyEnvironment>> = testFixture { context ->
  val project = this@pyInterpreterFixture.init()
  val factory = getOrCreatePyEnvironmentFactory(context.extensionContext)
  initializedTestFixture(project, factory.createEnvironment(envSpec))
}

private suspend fun TestFixtureInitializer.R<PyInterpreterFixture<PyEnvironment>>.initializedTestFixture(
  project: Project,
  env: PyEnvironment,
): TestFixtureInitializer.InitializedTestFixture<PyInterpreterFixture<PyEnvironment>> {
  val interpreter = env.prepareSdk(project)
  return initialized(PyInterpreterFixture(interpreter, env, project)) {
    PythonInterpreterProjectRegistry.getInstance(project).removePythonInterpreter(interpreter)
    env.close()
  }
}

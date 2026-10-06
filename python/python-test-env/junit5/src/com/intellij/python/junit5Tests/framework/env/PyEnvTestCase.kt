// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.junit5Tests.framework.env

import com.intellij.python.test.env.common.PredefinedPyEnvironments
import com.intellij.python.test.env.junit5.EnvTestPythonProviderExtension
import com.intellij.python.test.env.junit5.PythonBinaryPathExtension
import com.intellij.python.test.env.junit5.PythonFactoryExtension
import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Python env test case that supports [PythonBinaryPath] parameter injection. For an SDK, use [pyInterpreterFixture].
 * 
 * @param envs values from [com.intellij.python.test.env.common.PredefinedPyEnvironments] for environments where to run test.
 *             If empty, defaults to Python 3.12.
 * 
 * Example:
 * ```kotlin
@PyEnvTestCase
class PyEnvTestExample {
  @Test
  fun test(@PythonBinaryPath binary: PythonBinary) { ... }
}

@PyEnvTestCase(env = PredefinedPyEnvironments.CONDA)
class PyCondaTest {
  @Test
  fun test(@PythonBinaryPath binary: PythonBinary) { ... }
}
 * ```
 */
@TestApplication
@Target(AnnotationTarget.CLASS)
@ExtendWith(
  PythonBinaryPathExtension::class,
  PythonFactoryExtension::class,
  EnvTestPythonProviderExtension::class
)
annotation class PyEnvTestCase(val env : PredefinedPyEnvironments = PredefinedPyEnvironments.VENV_3_12)
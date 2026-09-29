// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.externalSystem.service.execution

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.util.environment.Environment
import com.intellij.openapi.externalSystem.util.environment.TestEnvironment
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ui.configuration.SdkTestCase
import com.intellij.openapi.roots.ui.configuration.UnknownSdkResolver
import com.intellij.openapi.util.Disposer
import com.intellij.platform.externalSystem.testFramework.service.execution.ExternalSystemTestUnknownSdkResolver
import com.intellij.platform.externalSystem.testFramework.service.execution.TestUnknownSdkResolver
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.roots.ui.configuration.TestSdkGenerator
import com.intellij.testFramework.roots.ui.configuration.TestSdkType

abstract class ExternalSystemJdkUtilTestCase : SdkTestCase() {

  val environment get() = Environment.getInstance() as TestEnvironment
  val jdkProvider get() = ExternalSystemJdkProvider.getInstance() as TestJdkProvider

  override fun setUp() {
    super.setUp()

    val application = ApplicationManager.getApplication()
    application.replaceService(Environment::class.java, TestEnvironment(), testRootDisposable)
    application.replaceService(ExternalSystemJdkProvider::class.java, TestJdkProvider(), testRootDisposable)

    ExtensionTestUtil.maskExtensions(UnknownSdkResolver.EP_NAME, listOf(ExternalSystemTestUnknownSdkResolver), testRootDisposable)

    environment.variables(ExternalSystemJdkUtil.JAVA_HOME to null)

    ExternalSystemTestUnknownSdkResolver.unknownSdkFixMode = TestUnknownSdkResolver.TestUnknownSdkFixMode.TEST_LOCAL_FIX
  }

  class TestJdkProvider : ExternalSystemJdkProvider, Disposable {
    private val lazyInternalJdk by lazy { TestSdkGenerator.createNextSdk() }

    override fun getJavaSdkType() = TestSdkType

    override fun getInternalJdk(): Sdk = lazyInternalJdk

    override fun createJdk(jdkName: String?, homePath: String): Sdk {
      val sdk = TestSdkGenerator.findTestSdk(homePath)!!
      Disposer.register(this, Disposable { removeSdk(sdk) })
      return sdk
    }

    override fun dispose() {}
  }
}
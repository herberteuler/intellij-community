// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.roots.ui.configuration

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownload
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.use
import com.intellij.testFramework.LightPlatformTestCase

abstract class SdkTestCase : LightPlatformTestCase() {

  override fun setUp() {
    super.setUp()

    TestSdkGenerator.reset()
    SdkType.EP_NAME.point.registerExtension(TestSdkType, testRootDisposable)
    SdkType.EP_NAME.point.registerExtension(DependentTestSdkType, testRootDisposable)
    SdkDownload.EP_NAME.point.registerExtension(TestSdkDownloader, testRootDisposable)
  }

  fun createAndRegisterSdk(isProjectSdk: Boolean = false): Sdk {
    val sdk = TestSdkGenerator.createNextSdk()
    registerSdk(sdk, isProjectSdk)
    return sdk
  }

  fun createAndRegisterDependentSdk(isProjectSdk: Boolean = false): Sdk {
    val parentSdk = TestSdkGenerator.createNextSdk()
    registerSdk(parentSdk)

    val sdk = TestSdkGenerator.createNextDependentSdk(parentSdk)
    registerSdk(sdk, isProjectSdk)
    return sdk
  }

  private fun registerSdk(sdk: Sdk, isProjectSdk: Boolean = false) {
    registerSdk(sdk, testRootDisposable)
    if (isProjectSdk) {
      setProjectSdk(project, sdk, testRootDisposable)
    }
  }

  fun <R> withProjectSdk(sdk: Sdk, action: () -> R): R {
    return withProjectSdk(project, sdk, action)
  }

  internal val Sdk.parent: Sdk
    get() {
      if (sdkType != DependentTestSdkType) error("Unexpected state")
      val parentSdkName = (sdkAdditionalData as DependentTestSdkAdditionalData).patentSdkName
      return ProjectJdkTable.getInstance().findJdk(parentSdkName)!!
    }

  companion object {

    inline fun <R> assertUnexpectedSdksRegistration(action: () -> R): R {
      return assertNewlyRegisteredSdks({ null }, action = action)
    }

    inline fun <R> assertNewlyRegisteredSdks(getExpectedNewSdk: () -> Sdk?, isAssertSdkName: Boolean = true, action: () -> R): R {
      val projectSdkTable = ProjectJdkTable.getInstance()
      val beforeSdks = projectSdkTable.allJdks.toSet()

      val result = runCatching(action)

      val afterSdks = projectSdkTable.allJdks.toSet()
      val newSdks = afterSdks - beforeSdks
      removeSdks(*newSdks.toTypedArray())

      result.onSuccess {
        val expectedNewSdk = getExpectedNewSdk()
        if (expectedNewSdk != null) {
          assertTrue("Expected registration of $expectedNewSdk but found $newSdks", newSdks.size == 1)
          val newSdk = newSdks.single()
          assertSdk(expectedNewSdk, newSdk, isAssertSdkName)
        }
        else {
          assertTrue("Unexpected sdk registration $newSdks", newSdks.isEmpty())
        }
      }

      return result.getOrThrow()
    }

    fun assertSdk(expected: Sdk?, actual: Sdk?, isAssertSdkName: Boolean = true) {
      if (expected != null && actual != null) {
        if (isAssertSdkName) {
          assertEquals(expected.name, actual.name)
        }
        assertEquals(expected.sdkType, actual.sdkType)
        assertEquals(expected, TestSdkGenerator.findTestSdk(actual))
      }
      else {
        assertEquals(expected, actual)
      }
    }

    fun registerSdk(sdk: Sdk, parentDisposable: Disposable) {
      WriteAction.runAndWait<Throwable> {
        val jdkTable = ProjectJdkTable.getInstance()
        jdkTable.addJdk(sdk, parentDisposable)
      }
    }

    fun registerSdks(vararg sdks: Sdk, parentDisposable: Disposable) {
      sdks.forEach { registerSdk(it, parentDisposable) }
    }

    fun removeSdk(sdk: Sdk) {
      WriteAction.runAndWait<Throwable> {
        val jdkTable = ProjectJdkTable.getInstance()
        jdkTable.removeJdk(sdk)
      }
    }

    fun removeSdks(vararg sdks: Sdk) {
      sdks.forEach(::removeSdk)
    }

    fun setProjectSdk(project: Project, sdk: Sdk?, parentDisposable: Disposable) {
      val rootManager = ProjectRootManager.getInstance(project)
      val projectSdk = rootManager.projectSdk
      WriteAction.runAndWait<Throwable> {
        rootManager.projectSdk = sdk
      }
      Disposer.register(parentDisposable, Disposable {
        WriteAction.runAndWait<Throwable> {
          rootManager.projectSdk = projectSdk
        }
      })
    }

    inline fun <R> withProjectSdk(project: Project, sdk: Sdk, action: () -> R): R {
      return Disposer.newDisposable().use { disposable ->
        setProjectSdk(project, sdk, parentDisposable = disposable)
        action()
      }
    }

    inline fun <R> withRegisteredSdks(vararg sdks: Sdk, action: () -> R): R {
      return Disposer.newDisposable().use { disposable ->
        registerSdks(*sdks, parentDisposable = disposable)
        action()
      }
    }
  }
}
// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testFramework.roots.ui.configuration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.util.io.toCanonicalPath
import com.intellij.util.system.OS
import com.intellij.util.system.OS.CURRENT
import java.io.File
import java.nio.file.Path
import java.util.Properties

object TestSdkGenerator {
  private var createdSdkCounter = 0
  private lateinit var createdSdks: MutableMap<String, Sdk>

  fun getAllTestSdks() = createdSdks.values

  fun findTestSdk(sdk: Sdk): Sdk? = findTestSdk(sdk.homePath!!)

  fun findTestSdk(homePath: String): Sdk? = createdSdks[FileUtil.toSystemDependentName(homePath)]

  fun getCurrentSdk() = createdSdks.values.last()

  fun reserveNextSdk(versionString: String = "11"): SdkInfo {
    val name = "test $versionString (${createdSdkCounter++})"
    val homePath = FileUtil.toCanonicalPath(FileUtil.join(FileUtil.getTempDirectory(), "jdk-$name"))
    return SdkInfo(name, versionString, homePath)
  }

  fun createTestSdk(sdkInfo: SdkInfo): Sdk {
    val sdk = ProjectJdkTable.getInstance().createSdk(sdkInfo.name, TestSdkType)
    val sdkModificator = sdk.sdkModificator
    sdkModificator.homePath = sdkInfo.homePath
    sdkModificator.versionString = sdkInfo.versionString

    val application = ApplicationManager.getApplication()
    val runnable = { sdkModificator.commitChanges() }
    if (application.isDispatchThread) {
      application.runWriteAction(runnable)
    }
    else {
      application.invokeAndWait { application.runWriteAction(runnable) }
    }
    createdSdks[FileUtil.toSystemDependentName(sdkInfo.homePath)] = sdk
    return sdk
  }

  fun createNextSdk(versionString: String = "11"): Sdk {
    val sdkInfo = reserveNextSdk(versionString)
    generateJdkStructure(sdkInfo)
    return createTestSdk(sdkInfo)
  }

  fun createNextDependentSdk(parentSdk: Sdk): Sdk {
    val name = "dependent-test-name (${createdSdkCounter++})"
    val versionString = "11"
    val homePath = Path.of(FileUtilRt.getTempDirectory(), "jdk-$name").toCanonicalPath()

    val sdk = ProjectJdkTable.getInstance().createSdk(name, DependentTestSdkType)
    val sdkModificator = sdk.sdkModificator
    sdkModificator.homePath = homePath
    sdkModificator.versionString = versionString
    sdkModificator.sdkAdditionalData = DependentTestSdkAdditionalData(parentSdk.name)
    ApplicationManager.getApplication().runWriteAction { sdkModificator.commitChanges() }
    createdSdks[homePath] = sdk
    return sdk
  }

  fun generateJdkStructure(sdkInfo: SdkInfo) {
    val homePath = sdkInfo.homePath
    createFile("$homePath/release")
    createFile("$homePath/jre/lib/rt.jar")
    if (CURRENT == OS.Windows) {
      createFile("$homePath/bin/javac.exe")
      createFile("$homePath/bin/java.exe")
    }
    else {
      createFile("$homePath/bin/javac")
      createFile("$homePath/bin/java")
    }
    val properties = Properties()
    properties.setProperty("JAVA_FULL_VERSION", sdkInfo.versionString)
    File("$homePath/release").outputStream().use {
      properties.store(it, null)
    }
  }

  private fun createFile(path: String) {
    val file = File(path)
    file.parentFile.mkdirs()
    file.createNewFile()
  }

  fun reset() {
    createdSdkCounter = 0
    createdSdks = LinkedHashMap()
  }

  data class SdkInfo(val name: String, val versionString: String, val homePath: String)
}

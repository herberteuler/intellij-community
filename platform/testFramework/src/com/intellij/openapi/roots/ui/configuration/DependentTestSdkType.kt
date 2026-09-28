// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.roots.ui.configuration

import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.impl.DependentSdkType
import org.jdom.Element
import java.io.File
import java.nio.file.Path

object DependentTestSdkType : DependentSdkType("dependent-test-type"), TestSdkType {
  private fun getParentPath(sdk: Sdk, relativePath: String): String? {
    if (sdk.sdkType != DependentTestSdkType) return null
    val additionalData = sdk.sdkAdditionalData
    val parentSdkName = (additionalData as DependentTestSdkAdditionalData).patentSdkName
    return ProjectJdkTable.getInstance().findJdk(parentSdkName)?.homePath?.let { File(it, relativePath).path }
  }

  override fun getPresentableName(): String = name
  override fun isValidSdkHome(path: String): Boolean = true
  override fun suggestSdkName(currentSdkName: String?, sdkHome: String): String = "dependent-sdk-name"
  override fun suggestHomePath(path: Path): String? = null
  override fun createAdditionalDataConfigurable(sdkModel: SdkModel, sdkModificator: SdkModificator): AdditionalDataConfigurable? = null
  override fun getBinPath(sdk: Sdk) = getParentPath(sdk, "bin")
  override fun getToolsPath(sdk: Sdk) = getParentPath(sdk, "lib/tools.jar")
  override fun getVMExecutablePath(sdk: Sdk) = getParentPath(sdk, "bin/java")

  override fun getUnsatisfiedDependencyMessage() = "Unsatisfied dependency message"
  override fun isValidDependency(sdk: Sdk) = sdk is TestSdkType
  override fun getDependencyType() = TestSdkType

  override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) {
    additional.setAttribute("patentSdkName", (additionalData as DependentTestSdkAdditionalData).patentSdkName)
  }

  override fun loadAdditionalData(additional: Element): SdkAdditionalData {
    return DependentTestSdkAdditionalData(additional.getAttributeValue("patentSdkName") ?: "")
  }
}
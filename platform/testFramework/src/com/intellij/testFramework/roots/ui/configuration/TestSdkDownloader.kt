// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testFramework.roots.ui.configuration

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkTypeId
import com.intellij.openapi.projectRoots.impl.jdkDownloader.JdkItem
import com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownload
import com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownloadTask
import java.util.function.Consumer
import java.util.function.Predicate
import javax.swing.JComponent

object TestSdkDownloader : SdkDownload {
  override fun supportsDownload(sdkTypeId: SdkTypeId) = sdkTypeId == TestSdkType

  override fun showDownloadUI(
      sdkTypeId: SdkTypeId,
      sdkModel: SdkModel,
      parentComponent: JComponent,
      selectedSdk: Sdk?,
      sdkCreatedCallback: Consumer<in SdkDownloadTask>
  ) {
    val sdk = TestSdkGenerator.createNextSdk()
    sdkCreatedCallback.accept(object : SdkDownloadTask {
      override fun doDownload(indicator: ProgressIndicator) {}
      override fun getPlannedVersion() = sdk.versionString!!
      override fun getSuggestedSdkName() = sdk.name
      override fun getPlannedHomeDir() = sdk.homePath!!
    })
  }

  override fun pickSdk(sdkTypeId: SdkTypeId,
                       sdkModel: SdkModel,
                       parentComponent: JComponent,
                       selectedSdk: Sdk?,
                       sdkFilter: Predicate<JdkItem>?
  ): SdkDownloadTask? = null
}
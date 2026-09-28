// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.roots.ui.configuration

import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.roots.ui.configuration.TestSdkDownloader
import com.intellij.openapi.roots.ui.configuration.TestSdkGenerator
import com.intellij.openapi.roots.ui.configuration.TestSdkType
import com.intellij.openapi.roots.ui.configuration.projectRoot.SdkDownload
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.extensionPointFixture
import com.intellij.testFramework.junit5.fixture.testFixture

fun testSdkFixture(): TestFixture<TestSdkGenerator> = testFixture {
  extensionPointFixture(SdkType.EP_NAME) { TestSdkType }.init()
  extensionPointFixture(SdkDownload.EP_NAME) { TestSdkDownloader }.init()
  TestSdkGenerator.reset()
  initialized(TestSdkGenerator) {
    TestSdkGenerator.reset()
  }
}

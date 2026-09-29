// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.externalSystem.testFramework.service.execution

import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkProvider

object ExternalSystemTestUnknownSdkResolver : TestUnknownSdkResolver(
  javaSdkType = ExternalSystemJdkProvider.getInstance().javaSdkType
)
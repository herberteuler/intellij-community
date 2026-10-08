// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.community.common.promotion

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
interface PyFrameworkPromoProvider {
  companion object {
    val EP_NAME: ExtensionPointName<PyFrameworkPromoProvider> = ExtensionPointName.create("Pythonid.frameworkPromoProvider")

    fun getInstance(): PyFrameworkPromoProvider? = EP_NAME.extensionList.firstOrNull()
  }

  fun isTrialAvailable(): Boolean

  fun onNpwTemplateAction()

  fun onRunConfigAction()
}

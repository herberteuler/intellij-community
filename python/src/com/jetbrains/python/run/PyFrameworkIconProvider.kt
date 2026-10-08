// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.run

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

@ApiStatus.Internal
interface PyFrameworkIconProvider {
  val configurationTypeId: String
  val icon: Icon

  companion object {
    val EP_NAME: ExtensionPointName<PyFrameworkIconProvider> = ExtensionPointName.create("Pythonid.frameworkIconProvider")

    fun getIcon(configurationTypeId: String): Icon? =
      EP_NAME.extensionList.firstOrNull { it.configurationTypeId == configurationTypeId }?.icon
  }
}

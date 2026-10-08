// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.community.common.promotion

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls

/**
 * Supplies footer text for the "Add Interpreter" action group.
 */
@ApiStatus.Internal
interface PyAddInterpreterPopupPromo {
  companion object {
    val EP_NAME: ExtensionPointName<PyAddInterpreterPopupPromo> = ExtensionPointName("Pythonid.addInterpreterPopupPromo")
  }

  /**
   * The footer for the popup, or null when no promo applies.
   */
  fun getPopupFooterText(): @Nls String?
}

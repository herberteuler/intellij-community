// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins.newui

import com.intellij.openapi.application.ApplicationManager
import org.jetbrains.annotations.ApiStatus

/**
 * Provides trial promotion actions for plugin subscription banners.
 */
@ApiStatus.Internal
interface PluginSubscriptionPromo {
  companion object {
    fun getInstance(): PluginSubscriptionPromo? = ApplicationManager.getApplication().getService(PluginSubscriptionPromo::class.java)
  }

  fun isTrialAvailable(): Boolean

  fun startTrial()
}

// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ide.productMode

import com.intellij.openapi.components.service
import com.intellij.platform.productMode.ProductMode
import org.jetbrains.annotations.ApiStatus

/**
 * Provides access to the mode the current IDE instance is running in.
 */
interface IdeProductMode {
  companion object {
    @JvmStatic
    fun getInstance(): IdeProductMode = service()

    /**
     * Returns `true` if the IDE instance is running in a backend mode and serves as a remote development host.
     */
    @JvmStatic
    val isBackend: Boolean
      get() = getInstance().currentMode == ProductMode.BACKEND

    /**
     * Returns `true` if this process is running in a frontend mode (JetBrains Client).
     * It may be connected to either a remote development host or CodeWithMe session.
     * Use [com.intellij.platform.frontend.split.FrontendProcessInfo] to determine the actual connection type.
     */
    @JvmStatic
    val isFrontend: Boolean
      get() = getInstance().currentMode.isFrontendProcess

    /**
     * Returns `true` if this process is running in a monolithic mode (a regular IDE instance) or will upgrade to the monolithic mode (monolith Light Mode).
     */
    @JvmStatic
    val isMonolith: Boolean
      get() = getInstance().currentMode.isMonolithProcess

    /**
     * Returns `true` in [ProductMode.LIGHT_REMOTE], [ProductMode.LIGHT_WITH_RD_CONNECTION] and [ProductMode.LIGHT_MONOLITH].
     * It becomes `false` once the process fully advances to the smart mode.
     */
    @JvmStatic
    val isLight: Boolean
      get() = getInstance().currentMode.isLight

    /**
     * Returns `true` in the standalone JetBrains Light product before its upgrade, that is in [ProductMode.LIGHT_MONOLITH].
     * It becomes `false` once the process advances to [ProductMode.MONOLITH].
     */
    @JvmStatic
    val isUnifiedIde: Boolean
      get() = getInstance().currentMode == ProductMode.LIGHT_MONOLITH
  }

  /**
   * Returns the mode the current IDE instance is running in.
   */
  @get:ApiStatus.Experimental
  val currentMode: ProductMode
}
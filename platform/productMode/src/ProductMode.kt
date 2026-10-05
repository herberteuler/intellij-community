// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.productMode

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NonNls

/**
 * Describes a mode in which a product may be started.
 *
 * The mode of the current process is held by `CurrentProductMode`, and a process may move between
 * modes without a restart. Use `com.intellij.platform.ide.productMode.IdeProductMode` to read it
 * from product code.
 *
 * TODO: reuse inside `com.intellij.idea.AppMode`?
 */
@ApiStatus.Experimental
enum class ProductMode(val id: @NonNls String) {
  /**
   * Indicates that this process performs all necessary tasks to provide smart features itself.
   * This is the default mode for all IDEs.
   */
  MONOLITH("monolith"),

  /**
   * Indicates that this process is running in a frontend mode (JetBrains Client).
   * It doesn't perform heavy tasks like code analysis and takes necessary information from a separate backend process.
   */
  FRONTEND("frontend"),

  /**
   * Indicates that this process is running in a backend mode and serves as a remote development host.
   * It doesn't show the UI to the user directly, a separate process is responsible for this.
   */
  BACKEND("backend"),

  /**
   * Indicates that this process is the JetBrains Client light session: a minimal self-sufficient frontend.
   * It gains a backend through [LIGHT_WITH_RD_CONNECTION].
   */
  @ApiStatus.Internal
  LIGHT_REMOTE("light_remote"),

  /**
   * Indicates that this process is running in a light mode with an established rd connection.
   * This is a temporary mode which appears during the transition from [LIGHT_REMOTE] to [FRONTEND].
   */
  @ApiStatus.Internal
  LIGHT_WITH_RD_CONNECTION("light_with_rd_connection"),

  /**
   * Indicates that this process is the standalone JetBrains Light product before it gains the full
   * IDE features. It has no backend process and no remote API. It moves to [MONOLITH] without a restart.
   */
  @ApiStatus.Internal
  LIGHT_MONOLITH("light_monolith"),

  /**
   * Indicates that this process is running in a language server mode.
   * This is a variant of [BACKEND] mode, but without the remote development connection.
   */
  @ApiStatus.Internal
  LANGUAGE_SERVER("language_server"),
  ;

  /**
   * Returns `true` in [LIGHT_REMOTE], [LIGHT_WITH_RD_CONNECTION] and [LIGHT_MONOLITH].
   * It turns `false` once the process advances out of the light mode.
   */
  @get:ApiStatus.Internal
  val isLight: Boolean
    get() = this == LIGHT_REMOTE || this == LIGHT_WITH_RD_CONNECTION || this == LIGHT_MONOLITH

  /**
   * Returns `true` when this process shows the UI to the user and takes smart features from a backend process.
   * It covers [FRONTEND], [LIGHT_REMOTE] and [LIGHT_WITH_RD_CONNECTION].
   * It is `false` in [MONOLITH] and in [LIGHT_MONOLITH]: a standalone light process shows the UI, but it is not a frontend.
   */
  @get:ApiStatus.Internal
  val isFrontendProcess: Boolean
    get() = this == FRONTEND || this == LIGHT_REMOTE || this == LIGHT_WITH_RD_CONNECTION

  /**
   * Returns `true` when this process serves itself and no other process takes part.
   * It covers [MONOLITH], and [LIGHT_MONOLITH], which becomes [MONOLITH] without a restart.
   * It does not say that the backend modules are loaded. Compare with [MONOLITH] for that.
   */
  @get:ApiStatus.Internal
  val isMonolithProcess: Boolean
    get() = this == MONOLITH || this == LIGHT_MONOLITH

  /**
   * Returns `true` in [LIGHT_REMOTE] and [LIGHT_MONOLITH]: a light process with no remote API to await.
   * It is `false` in [LIGHT_WITH_RD_CONNECTION], which already holds the connection.
   */
  @get:ApiStatus.Internal
  val isLightWithoutRemoteApi: Boolean
    get() = this == LIGHT_REMOTE || this == LIGHT_MONOLITH

  @ApiStatus.Internal
  companion object {
    @JvmStatic
    fun findById(id: @NonNls String): ProductMode? = entries.firstOrNull { it.id == id }
  }
}

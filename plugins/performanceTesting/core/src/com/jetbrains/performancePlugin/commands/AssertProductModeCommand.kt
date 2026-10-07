// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.performancePlugin.commands

import com.intellij.openapi.ui.playback.PlaybackContext
import com.intellij.openapi.ui.playback.commands.PlaybackCommandCoroutineAdapter
import com.intellij.platform.ide.productMode.IdeProductMode

/**
 * `%assertProductMode <id>` fails the script when the product mode of the process is not the one with that `ProductMode.id`.
 * The ids are `monolith`, `frontend`, `backend`, `light_remote`, `light_with_rd_connection`, `light_monolith`, and `language_server`.
 * A process may move between modes without a restart, so a script asserts the mode at the point that matters.
 */
class AssertProductModeCommand(text: String, line: Int) : PlaybackCommandCoroutineAdapter(text, line) {
  companion object {
    const val PREFIX: String = CMD_PREFIX + "assertProductMode"
  }

  override suspend fun doExecute(context: PlaybackContext) {
    val expected = extractCommandArgument(PREFIX).trim()
    val actual = IdeProductMode.getInstance().currentMode.id
    check(actual == expected) { "The product mode is '$actual', expected '$expected'" }
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.actionSystem.impl

import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.keymap.KeymapUtil
import org.jetbrains.annotations.ApiStatus
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JOptionPane

@ApiStatus.Internal
object ActionInputEvents {
  /**
   * Creates an input event for the action with [actionId].
   * The event is a key press of the first keyboard shortcut of the action in the active keymap.
   * If the action has no keyboard shortcut, the event is a mouse press.
   */
  @JvmStatic
  fun create(actionId: String): InputEvent {
    val keyStroke = KeymapUtil.getActiveKeymapShortcuts(actionId).shortcuts
      .firstNotNullOfOrNull { (it as? KeyboardShortcut)?.firstKeyStroke }
    if (keyStroke == null) {
      return MouseEvent(JOptionPane.getRootFrame(), MouseEvent.MOUSE_PRESSED, 0, 0, 0, 0, 1, false, MouseEvent.BUTTON1)
    }
    return KeyEvent(JOptionPane.getRootFrame(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(), keyStroke.modifiers,
                    keyStroke.keyCode, keyStroke.keyChar, KeyEvent.KEY_LOCATION_STANDARD)
  }
}

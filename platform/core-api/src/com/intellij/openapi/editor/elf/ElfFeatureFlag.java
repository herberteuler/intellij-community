// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.elf;

/**
 * Global feature flag for editor lock-free typing
 * <a href="https://youtrack.jetbrains.com/issue/IJPL-54">IJPL-54</a>.
 * @see Elf
 */
public final class ElfFeatureFlag {
  /**
   * This flag only tells whether elf is enabled. It is not a contextual
   * check for the current execution. Use {@link Elf#isInElfScope()} when code needs
   * to know whether it is running inside lock-free typing, and
   * {@link Elf#isUnsupportedOperationGuardActive()} before calling operations that are
   * not supported yet during lock-free typing.
   * <p>
   * Always returns {@code false}. The elf document is not available.
   */
  public static boolean isEnabled() {
    return false;
  }
}

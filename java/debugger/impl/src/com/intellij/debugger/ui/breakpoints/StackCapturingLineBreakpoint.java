// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.ui.breakpoints;

import com.intellij.debugger.engine.DebugProcessImpl;

/**
 * @deprecated Async stack capture through breakpoints is no longer supported.
 */
@Deprecated
@SuppressWarnings({"unused", "NonFinalUtilityClass"})
public class StackCapturingLineBreakpoint {
  public static void deleteAll(DebugProcessImpl debugProcess) {
  }

  public static void createAll(DebugProcessImpl debugProcess) {
  }
}

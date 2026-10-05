// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresEdt
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.TestOnly

/**
 * Lets a plugin finish its PSI changes before [TestDaemonCodeAnalyzerImpl] starts highlighting, which forbids any PSI change.
 */
@TestOnly
@ApiStatus.Internal
interface TestDaemonStartWaiter {
  /**
   * Checks if the plugin is still going to change PSI of [project]; called repeatedly while EDT events are dispatched.
   */
  @RequiresEdt
  fun isBusy(project: Project): Boolean

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<TestDaemonStartWaiter> = ExtensionPointName("com.intellij.daemon.testDaemonStartWaiter")
  }
}

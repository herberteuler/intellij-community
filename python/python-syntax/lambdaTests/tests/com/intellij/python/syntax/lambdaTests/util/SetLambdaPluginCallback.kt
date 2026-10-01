// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.syntax.lambdaTests.util

import com.intellij.lambda.testFramework.utils.LambdaTestPluginHolder
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Registers this module's test plugin so its content module is installed into both IDEs of a split run.
 * Without it the frontend has no `intellij.lambda.testFramework` classes and every `runInFrontend` fails with
 * `NoClassDefFoundError`.
 *
 * `setupAllModesPlugin` derives the wrapper module `intellij.python.syntax.lambdaTests.plugin` and the
 * plugin directory `python-syntax-lambdaTests-plugin` from the id below.
 */
class SetLambdaPluginCallback : BeforeAllCallback, AfterAllCallback {
  override fun beforeAll(context: ExtensionContext) {
    LambdaTestPluginHolder.setupAllModesPlugin("intellij.python.syntax.lambdaTests")
  }

  override fun afterAll(context: ExtensionContext) {
    LambdaTestPluginHolder.cleanUpAdditionalLambdaPlugin()
  }
}

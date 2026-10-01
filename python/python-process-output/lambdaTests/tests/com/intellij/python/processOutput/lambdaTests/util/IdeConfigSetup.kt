// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.processOutput.lambdaTests.util

import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.project.NoProject
import com.intellij.ide.starter.project.TestCaseTemplate
import com.intellij.lambda.testFramework.starter.IdeStartConfig
import com.intellij.tools.ide.starter.product.pycharm.PyCharm
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Launches PyCharm instead of the framework's default IDEA Ultimate. PyCharm Professional rather than
 * Community: a split run needs an IDE that can act as the remote-dev backend.
 *
 * `NoProject` because the process-output topic is application-level — nothing here needs an open project.
 * The `key` is the identity the started IDE is reused under, so suites that can share one IDE share this
 * string.
 */
class IdeConfigSetup : BeforeAllCallback {
  override fun beforeAll(context: ExtensionContext) {
    IdeStartConfig.current = IdeStartConfig(
      key = "python-process-output-lambda",
      testCase = (object : TestCaseTemplate(IdeInfo.PyCharm) {}).withProject(NoProject),
      configureTestContext = {
        applyVMOptionsPatch {
          // Off because the frontend's dev build cannot load the model: `PythonCore` requires the content
          // module `intellij.libraries.mlapi.catboost`, whose `ModelComponentsProvider` lives in
          // `intellij.libraries.mlapi.core`, and a content module is private to its own plugin — adding the LLM
          // plugin, which declares core, does not put it on PythonCore's class path. Left on, the frontend
          // aborts startup in `ImportsRankingModelService` before any test code runs. Import ranking has
          // nothing to do with the process-output topic under test here.
          //
          // The whole option list is passed, not the bare word: `RegistryValue.selectedOption` parses
          // `[a|b|c*]` and returns null for anything else, and the reader of this key does `requireNotNull`.
          addSystemProperty(IMPORT_RANKING_ML_KEY, "[IN_EXPERIMENT|ENABLED|DISABLED*]")
        }
      },
    )
  }
}

private const val IMPORT_RANKING_ML_KEY: String = "quickfix.ranking.ml"

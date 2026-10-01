// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.syntax.lambdaTests.project

import com.intellij.ide.starter.project.ProjectInfoSpec
import com.intellij.ide.starter.project.ReusableLocalProjectInfo
import com.intellij.ide.starter.utils.JarUtils
import java.nio.file.Files

/**
 * A minimal project containing a single `src/main.py`. The claim under test is per-`VirtualFile`
 * (`PythonLanguageLevelPusher.persistAttribute` keys its value by `VirtualFile`, and so does
 * `PyLanguageFacade.getEffectiveLanguageLevel`), so the test needs a real file that both the backend and the
 * split frontend resolve to the same path — unlike `intellij.lambda.testFramework`'s own `TestAppProject`/
 * `HelloWorldProject`, which only ever carry Java files. Packaged inside this suite's own resources rather than
 * the shared framework module, since nothing else needs a Python file here.
 */
object PythonLangLevelSyncProject : ProjectInfoSpec by ReusableLocalProjectInfo(
  projectDir = JarUtils.extractResource(
    "projects/pythonLangLevelSync",
    Files.createTempDirectory("python-lang-level-sync-lambda-"),
  )
)

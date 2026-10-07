// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.analysisignore

import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts [AnalysisIgnoreService] with the project. The first update of the service gives each project root its default entity, also in a
 * session without a scan of the files.
 */
internal class AnalysisIgnoreStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    project.serviceAsync<AnalysisIgnoreService>()
  }
}

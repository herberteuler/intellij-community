// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic

import com.intellij.diagnostic.report.MemoryReportReason
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path

/**
 * Captures and analyzes heap dumps.
 * The module `intellij.platform.ide.hprof` registers the implementation as an application service.
 * Without the module, [getInstanceOrNull] returns `null`, and the callers skip the heap dump analysis.
 */
@ApiStatus.Internal
interface HeapDumpAnalyzer {
  companion object {
    @JvmStatic
    fun getInstanceOrNull(): HeapDumpAnalyzer? = ApplicationManager.getApplication().getService(HeapDumpAnalyzer::class.java)
  }

  /**
   * Captures a heap dump and schedules its analysis for the next start.
   *
   * @param silent `true` to analyze without a notification to the user
   */
  fun captureHeapDumpForAnalysis(reason: MemoryReportReason, silent: Boolean)

  /**
   * Analyzes the strong references to the class loaders of the plugin in the heap dump.
   *
   * @return the report text, or an empty string if no strong reference keeps a class loader of the plugin
   */
  fun analyzeClassLoaderReferences(hprofPath: Path, pluginId: PluginId): String
}

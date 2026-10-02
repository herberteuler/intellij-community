// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic.hprof.action

import com.intellij.diagnostic.HeapDumpAnalyzer
import com.intellij.diagnostic.hprof.action.HeapDumpSnapshotRunnable.AnalysisOption
import com.intellij.diagnostic.hprof.analysis.AnalyzeClassloaderReferencesGraph
import com.intellij.diagnostic.hprof.analysis.HProfAnalysis
import com.intellij.diagnostic.report.MemoryReportReason
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption

internal class HeapDumpAnalyzerImpl : HeapDumpAnalyzer {
  override fun captureHeapDumpForAnalysis(reason: MemoryReportReason, silent: Boolean) {
    val analysisOption = if (silent) AnalysisOption.SCHEDULE_ON_NEXT_START_SILENT else AnalysisOption.SCHEDULE_ON_NEXT_START
    HeapDumpSnapshotRunnable(reason, analysisOption).run()
  }

  override fun analyzeClassLoaderReferences(hprofPath: Path, pluginId: PluginId): String {
    FileChannel.open(hprofPath, StandardOpenOption.READ).use { channel ->
      val analysis = HProfAnalysis(channel, SystemTempFilenameSupplier()) { analysisContext, listProvider, progressIndicator ->
        AnalyzeClassloaderReferencesGraph(analysisContext, listProvider, pluginId.idString).analyze(progressIndicator).mainReport.toString()
      }
      analysis.onlyStrongReferences = true
      analysis.includeClassesAsRoots = false
      analysis.setIncludeMetaInfo(false)
      return analysis.analyze(ProgressManager.getGlobalProgressIndicator() ?: EmptyProgressIndicator())
    }
  }
}

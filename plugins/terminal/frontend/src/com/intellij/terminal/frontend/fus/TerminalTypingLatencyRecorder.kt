// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.terminal.frontend.fus

import com.intellij.internal.statistic.collectors.fus.LatencyRecord
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.platform.diagnostic.telemetry.Scope
import com.intellij.platform.diagnostic.telemetry.TelemetryManager
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.util.messages.MessageBusConnection
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.VisibleForTesting

@ApiStatus.Internal
@Service(Service.Level.APP)
class TerminalTypingLatencyRecorder : TerminalTypingLatencyListener {
  companion object {
    const val SPAN_NAME: String = "terminal_typing"
    private val TRACER = TelemetryManager.getInstance().getTracer(Scope("terminal", null))
  }

  private val lock = Any()
  private var latencyRecord: LatencyRecord? = null
  private var connection: MessageBusConnection? = null

  override fun recordTypingLatency(terminalView: TerminalView, latencyMs: Long) {
    synchronized(lock) {
      latencyRecord?.update(latencyMs.toInt())
    }
  }

  @VisibleForTesting
  fun startRecording() {
    connection?.disconnect()
    synchronized(lock) {
      latencyRecord = LatencyRecord()
    }
    connection = ApplicationManager.getApplication().messageBus.connect().also {
      it.subscribe(TerminalTypingLatencyListener.TOPIC, this)
    }
  }

  @VisibleForTesting
  fun stopRecording() {
    connection?.disconnect()
    connection = null
    val localLatencyRecord = synchronized(lock) {
      latencyRecord.also { latencyRecord = null }
    } ?: return
    val samples = localLatencyRecord.samples.size
    if (samples == 0) return
    TRACER.spanBuilder(SPAN_NAME).startSpan()
      .setAttribute("latency#samples", samples.toLong())
      .setAttribute("latency#max", localLatencyRecord.maxLatency.toLong())
      .setAttribute("latency#p90", localLatencyRecord.percentile(90).toLong())
      .setAttribute("latency#mean_value", localLatencyRecord.averageLatency)
      .end()
  }

}

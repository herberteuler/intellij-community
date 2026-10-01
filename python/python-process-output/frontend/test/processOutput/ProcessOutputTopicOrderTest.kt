// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.processOutput

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.python.processOutput.common.FrontendTopicService
import com.intellij.python.processOutput.common.OutputKindDto
import com.intellij.python.processOutput.common.OutputLineDto
import com.intellij.python.processOutput.common.ProcessId
import com.intellij.python.processOutput.common.ProcessOutputEventDto
import com.intellij.python.processOutput.common.ProcessOutputTopic
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * PY-89071: lines could arrive at [FrontendTopicService.events] out of the order they were sent.
 * The listener used to forward each event by launching a fresh coroutine onto the service's own
 * scope (`service.coroutineScope.launch { eventsInternal.emit(event) }`); coroutine launch order
 * is not execution order, so two launches racing on a multi-threaded dispatcher could emit in
 * either order. The fix replaced that with a single ordered hop: `eventsChannel.trySend(event)`
 * into an unlimited channel, re-emitted by one coroutine via `shareIn`.
 *
 * This drives the real registered listener through [ProcessOutputTopic], the same public entry
 * point `LoggingInputStream` uses in production, and never references the internal listener class
 * directly - no fake listener, no spawned process. Red on the revert only under real contention:
 * the race is in scheduling many launched coroutines, so a single send is always "in order" and
 * this needs enough concurrent sends to make the old scheduling race visible; there is no
 * deterministic formulation of it.
 */
@TestApplication
class ProcessOutputTopicOrderTest {
  @Test
  fun `lines sent through the real topic arrive at FrontendTopicService in send order`(): Unit = timeoutRunBlocking(60.seconds) {
    val service = ApplicationManager.getApplication().service<FrontendTopicService>()
    val tag = "PY-89071-${UUID.randomUUID()}-"
    val lineCount = 1000
    val processId = ProcessId(Int.MAX_VALUE)

    // Subscribe before sending: the shared flow replays nothing, so a collector started after the
    // first send would simply miss it rather than observe it late.
    val received = async(start = CoroutineStart.UNDISPATCHED) {
      service.events
        .filterIsInstance<ProcessOutputEventDto.NewOutputLine>()
        .filter { it.outputLine.text.startsWith(tag) }
        .take(lineCount)
        .toList()
    }

    repeat(lineCount) { i ->
      ProcessOutputTopic.sendNewOutputLineEvent(processId, OutputLineDto(OutputKindDto.OUT, "$tag$i"))
    }

    val indexesInArrivalOrder = received.await().map { it.outputLine.text.removePrefix(tag).toInt() }
    assertEquals((0 until lineCount).toList(), indexesInArrivalOrder, "events must arrive in the order they were sent")
  }
}

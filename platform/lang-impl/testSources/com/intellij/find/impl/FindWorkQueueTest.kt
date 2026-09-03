// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.find.impl

import com.intellij.find.impl.CandidateFilter.Source
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.DelicateCoroutinesApi
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Collections.synchronizedList
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration.Companion.seconds

/**
 * Pins [FindWorkQueue]: the close rule of a phase, the stop, how a failure leaves the queue, and that a claim persists on its
 * [WorkItem] across a read-action restart.
 */
@TestApplication
class FindWorkQueueTest {
  // close rule

  @Test
  fun `the channel closes when the producer is done after the last item finished`() {
    val phase = FindWorkQueue.Phase()
    assertThat(phase.offer(item("a"))).isTrue()
    phase.itemFinished()
    assertThat(isClosed(phase)).describedAs("closed before the producer is done").isFalse()

    phase.producerDone()

    assertThat(isClosed(phase)).isTrue()
  }

  @Test
  fun `the channel closes when the last item finished after the producer is done`() {
    val phase = FindWorkQueue.Phase()
    assertThat(phase.offer(item("a"))).isTrue()
    phase.producerDone()
    assertThat(isClosed(phase)).describedAs("closed with a pending item").isFalse()

    phase.itemFinished()

    assertThat(isClosed(phase)).isTrue()
  }

  @Test
  fun `an item offered by an in-flight item keeps the channel open`() {
    val phase = FindWorkQueue.Phase()
    assertThat(phase.offer(item("parent"))).isTrue()
    phase.producerDone()
    assertThat(phase.offer(item("child"))).isTrue()

    phase.itemFinished()
    assertThat(isClosed(phase)).describedAs("closed with the child pending").isFalse()
    phase.itemFinished()

    assertThat(isClosed(phase)).isTrue()
  }

  @Test
  fun `a second close and a close after a cancel are no-ops`() {
    val idle = FindWorkQueue.Phase()
    idle.producerDone()
    idle.producerDone()
    assertThat(isClosed(idle)).isTrue()
    assertThat(idle.offer(item("late"))).describedAs("offer after the close").isFalse()

    val cancelled = FindWorkQueue.Phase()
    assertThat(cancelled.offer(item("a"))).isTrue()
    cancelled.channel.cancel()
    cancelled.itemFinished()
    cancelled.producerDone()
    assertThat(isClosed(cancelled)).isTrue()
  }

  // stop

  @Test
  fun `a stop drops the queued items and lets the in-flight item finish`(): Unit = timeoutRunBlocking(30.seconds) {
    val events = synchronizedList(ArrayList<String>())
    val inFlightStarted = CountDownLatch(1)
    lateinit var queue: FindWorkQueue
    queue = FindWorkQueue(ProgressIndicatorBase(), 2) { item ->
      when (val payload = (item as WorkItem.Walk).payload) {
        "in-flight" -> {
          events.add("in-flight started")
          inFlightStarted.countDown()
          val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
          while (!queue.isStopped && System.nanoTime() < deadline) {
            ProgressManager.checkCanceled() // a stop does not cancel the in-flight item
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
          }
          events.add("in-flight finished")
          true
        }
        "stop" -> {
          check(inFlightStarted.await(20, TimeUnit.SECONDS)) { "the in-flight item did not start" }
          events.add("stop")
          false
        }
        else -> {
          events.add("queued $payload")
          true
        }
      }
    }

    val completed = queue.phase {
      for (payload in listOf("in-flight", "stop", "q1", "q2")) {
        queue.offer(item(payload))
      }
    }

    assertThat(completed).isFalse()
    assertThat(queue.isStopped).isTrue()
    assertThat(events.toList()).containsExactly("in-flight started", "stop", "in-flight finished")
    assertThat(queue.offer(item("after the stop"))).isFalse()
    assertThat(queue.phase { error("a phase after the stop ran") }).isFalse()
  }

  // failures

  @Test
  fun `a plain CancellationException of an item fails the search and keeps its type`() {
    val thrown = CancellationException("plain")
    assertThat(workerFailure(thrown)).isSameAs(thrown)
  }

  @Test
  fun `an Error of an item fails the search and keeps its type`() {
    val thrown = AssertionError("assert")
    assertThat(workerFailure(thrown)).isSameAs(thrown)
  }

  @Test
  fun `a plain CancellationException of the producer fails the search and keeps its type`() {
    val thrown = CancellationException("searcher")
    val queue = FindWorkQueue(ProgressIndicatorBase(), 2) { true }

    val abort = timeoutRunBlocking {
      try {
        queue.run { queue.phase { throw thrown } }
        null
      }
      catch (e: FindAbort) {
        e
      }
    }

    assertThat(abort?.unwrap()).isSameAs(thrown)
  }

  @Test
  fun `unwrap keeps the suppressed failures, unwrapped`() {
    val first = IllegalStateException("first")
    val second = AssertionError("second")
    val abort = FindAbort(first)
    abort.addSuppressed(FindAbort(second))

    assertThat(FindAbort(abort).unwrap()).isSameAs(first)
    assertThat(first.suppressed).containsExactly(second)
  }

  // claims

  @Test
  fun `a claim persists across a write-action restart, so the checks run once and the scan twice`(): Unit = timeoutRunBlocking(30.seconds) {
    val coverageChecks = AtomicInteger()
    val filter = CandidateFilter({ true }, { false }, { true }, { true }, { coverageChecks.incrementAndGet(); false }, true, { null })
    val file = LightVirtualFile("a.txt", "text")
    val item = WorkItem.Candidate(file, Source.WALK)
    val runs = AtomicInteger()
    val queue = FindWorkQueue(ProgressIndicatorBase(), 1) { workItem ->
      val toScan = filter.admitOnWorker(workItem as WorkItem.Candidate)
      if (runs.incrementAndGet() == 1) {
        // a pending write action cancels this read action, which runs the item again after the write
        ApplicationManager.getApplication().executeOnPooledThread { WriteAction.runAndWait<RuntimeException> {} }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
          ProgressManager.checkCanceled()
          LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
        error("the write action did not cancel the read action")
      }
      toScan === file
    }

    val completed = queue.phase { queue.offer(item) }

    assertThat(completed).isTrue()
    assertThat(runs.get()).describedAs("runs of the item").isEqualTo(2)
    assertThat(coverageChecks.get()).describedAs("coverage checks").isEqualTo(1)
    assertThat(item.claimed).isSameAs(file)
  }

  private fun workerFailure(thrown: Throwable): Throwable? {
    val queue = FindWorkQueue(ProgressIndicatorBase(), 2) { throw thrown }
    val abort = timeoutRunBlocking {
      try {
        queue.run { queue.phase { queue.offer(item("failing")) } }
        null
      }
      catch (e: FindAbort) {
        e
      }
    }
    return abort?.unwrap()
  }

  private fun item(payload: String): WorkItem = WorkItem.Walk(payload)

  @OptIn(DelicateCoroutinesApi::class)
  private fun isClosed(phase: FindWorkQueue.Phase): Boolean = phase.channel.isClosedForSend
}

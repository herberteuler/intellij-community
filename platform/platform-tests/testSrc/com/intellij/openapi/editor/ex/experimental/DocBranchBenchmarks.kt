// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.ex.experimental

import com.intellij.tools.ide.metrics.benchmark.Benchmark

/**
 * Times [passes] runs of [pass] with the platform benchmark framework, as the subtest [name] of the
 * calling test.
 *
 * The framework runs [WARMUP_ITERATIONS] warm-up iterations, then [ATTEMPTS] measured attempts,
 * with a collection after each one. It prints the collected metrics and publishes them for IJ Perf.
 * Compare the `attempt.min.ms` metric: a single timing is worthless on a busy machine, and the
 * minimum is the attempt that the machine disturbed least.
 *
 * The framework reports whole milliseconds. So a pass that takes a millisecond or less must repeat,
 * and one attempt runs all [passes] of them. The subtest name then states the pass count, and a
 * published time divided by it is the time of one pass. [pass] returns a checksum, and the checksum
 * goes to a volatile field, so the JIT cannot drop the work.
 *
 * [setup] runs once before every attempt, and the framework does not time it. A scenario builds
 * its start value there, so that an attempt times only the work that follows.
 */
internal fun benchmarkSubtest(
  name: String,
  passes: Int = 1,
  setup: () -> Unit = {},
  pass: () -> Int,
) {
  val subtest = if (passes == 1) name else "$name, $passes passes"
  Benchmark.newBenchmark(subtest) {
    var checksum = 0
    repeat(passes) {
      checksum += pass()
    }
    sink = checksum
  }.setup { setup() }
   .warmupIterations(WARMUP_ITERATIONS)
   .attempts(ATTEMPTS)
   .startAsSubtest(subtest)
}

/**
 * The heap in use after three collection requests. A collection is a request and not a command, so
 * this is a hint of the retained size, and not a measurement of it.
 */
internal fun heapInUse(): Long {
  val runtime = Runtime.getRuntime()
  repeat(3) {
    runtime.gc()
  }
  return runtime.totalMemory() - runtime.freeMemory()
}

private const val WARMUP_ITERATIONS = 3
private const val ATTEMPTS = 5

@Volatile
private var sink: Int = 0

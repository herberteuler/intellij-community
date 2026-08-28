// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor.impl

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.editor.experimental.DocOp
import com.intellij.openapi.editor.experimental.DocText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * A benchmark, not a regression test: it runs [DocTextDiff] over a set of realistic document changes
 * and prints the cost of each one. The "Performance" name keeps it out of the functional runs.
 *
 * Each scenario reports four numbers.
 * - The time, as the best of [TIMED_RUNS] after [WARMUP_RUNS] warm-up runs.
 * - The throughput over the base document, so the numbers compare across the sizes.
 * - The op count. Every op becomes an [com.intellij.openapi.editor.experimental.Event] that the
 *   graph keeps forever, so this is the per-event memory.
 * - The unit count, which is the number of characters that the ops insert or delete. A unit is what
 *   a merge walks and what the graph stores per character, so this is the number that decides
 *   whether a script is cheap or ruinous.
 *
 * Time alone hides the interesting failures. A script that runs in 1 ms and emits two million units
 * is far worse for the event graph than one that runs in 300 ms and emits half a million.
 *
 * Every scenario also applies its script back to the base and checks the text, so the benchmark
 * doubles as a correctness run over inputs that the functional tests never reach.
 */
class DocTextDiffPerformanceTest {

  @Test
  fun `the diff over a realistic set of document changes`() {
    val unit = Files.readString(hugeTextPath())
    for (copies in COPIES) {
      val text = buildString { repeat(copies) { append(unit) } }
      val base = DocText.createText(text)
      val label = if (copies == 1) "the source file" else "the source file $copies times over"
      println("=== $label: ${base.length()} chars, ${base.lineCount()} lines ===")
      printHeader()
      for (scenario in scenarios(base, text)) {
        if (copies > 1 && !scenario.scalesUp) {
          continue
        }
        run(scenario.name, base, DocText.createText(scenario.target()))
      }
      println()
    }
  }

  /**
   * A minified document holds the whole text on one line, so the growth to whole lines gives up and
   * the character region stands on its own. The interesting question is whether the cost tracks the
   * edit or the document.
   */
  @Test
  fun `the diff over one huge line`() {
    val oneLine = Files.readString(hugeTextPath()).replace('\n', ' ')
    val base = DocText.createText(oneLine)
    println("=== one line of ${base.length()} chars ===")
    printHeader()
    run("one edit in the middle", base, DocText.createText(edited(oneLine, 1)))
    run("20 edits", base, DocText.createText(edited(oneLine, 20)))
    run("500 edits", base, DocText.createText(edited(oneLine, 500)))
    println()
  }

  private fun scenarios(base: DocText, text: String): List<Scenario> = listOf(
    // The reload cases. A document comes back from disk with a few changes.
    Scenario("no change") { text },
    Scenario("append one line") { "$text  // appended\n" },
    Scenario("delete the first 200 lines") { text.substring(base.lineStartOffset(200)) },
    Scenario("one edit inside one line") { text.replaceFirst("myScrollingModel", "theScrollingModel") },
    Scenario("20 new lines, scattered") { withNewLines(base, 20) },
    Scenario("500 new lines, scattered") { withNewLines(base, 500) },

    // The tool cases. Something rewrote the file.
    Scenario("rename an identifier everywhere") { text.replace("myScrollingModel", "theScrollingModel") },
    Scenario("reindent every line") { text.replace("    ", "\t") },

    // The cases that punish the algorithm. They stay at the smallest size on purpose.
    Scenario("convert every line to CRLF", scalesUp = false) { text.replace("\n", "\r\n") },
    Scenario("reverse the line order", scalesUp = false) { text.split("\n").asReversed().joinToString("\n") },
    Scenario("replace the whole text", scalesUp = false) { unrelatedText(text.length) },
  )

  private fun run(name: String, base: DocText, target: DocText) {
    repeat(WARMUP_RUNS) { DocTextDiff.diff(base, target) }
    var best = Long.MAX_VALUE
    repeat(TIMED_RUNS) {
      val start = System.nanoTime()
      DocTextDiff.diff(base, target)
      best = minOf(best, System.nanoTime() - start)
    }
    val ops = DocTextDiff.diff(base, target)

    val applyStart = System.nanoTime()
    var applied = base
    for (op in ops) {
      applied = applied.applyOp(op)
    }
    val applyMillis = (System.nanoTime() - applyStart) / 1_000_000.0
    assertEquals(target.string(), applied.string()) { "the script does not rebuild the target of \"$name\"" }

    val millis = best / 1_000_000.0
    val throughput = if (millis == 0.0) 0.0 else base.length() / millis / 1000.0
    val units = units(ops)
    val share = 100.0 * units / maxOf(base.length(), 1)
    println(
      "  %-32s%9.1f ms%7.0f MB/s%9d ops%11d units%8.1f%%%9.1f ms".format(
        name, millis, throughput, ops.size, units, share, applyMillis
      )
    )
  }

  private fun printHeader() {
    println(
      "  %-32s%12s%12s%13s%17s%9s%12s".format("scenario", "diff", "throughput", "ops", "units", "of doc", "apply")
    )
  }

  /** The number of characters that [ops] insert or delete. One character is one unit of the graph. */
  private fun units(ops: List<DocOp>): Int {
    var count = 0
    for (op in ops) {
      count += when (op) {
        is DocOp.Insert -> op.fragment().length
        is DocOp.Delete -> op.length()
      }
    }
    return count
  }

  /** [base] with [count] new lines, spread evenly over the document. */
  private fun withNewLines(base: DocText, count: Int): String {
    var target = base
    val step = maxOf(base.lineCount() / (count + 1), 1)
    // Back to front, so every offset indexes the base.
    for (index in count downTo 1) {
      target = target.applyOp(DocOp.ins(base.lineStartOffset(index * step), "    // edit $index\n"))
    }
    return target.string()
  }

  /** [text] with [count] tokens inserted, spread evenly. The text needs no line feed. */
  private fun edited(text: String, count: Int): String {
    val step = text.length / (count + 1)
    val builder = StringBuilder(text)
    for (index in count downTo 1) {
      builder.insert(index * step, " /*$index*/ ")
    }
    return builder.toString()
  }

  /** A text of about [length] characters that shares no line with the source. */
  private fun unrelatedText(length: Int): String {
    val builder = StringBuilder(length + 32)
    var line = 0
    while (builder.length < length) {
      builder.append("an unrelated line of content, number ").append(line++).append('\n')
    }
    return builder.toString()
  }

  private fun hugeTextPath(): Path {
    return Path.of(PathManager.getCommunityHomePath(), "platform/platform-tests/testData/editor/docBranch/EditorImpl.java.txt")
  }

  private class Scenario(val name: String, val scalesUp: Boolean = true, val target: () -> String)

  companion object {
    /** How many times the source file repeats. The repeat makes every line a duplicate, which the
     * comparison finds harder than a real file of the same size. */
    private val COPIES = intArrayOf(1, 10)

    private const val WARMUP_RUNS = 2
    private const val TIMED_RUNS = 5
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random

/**
 * A benchmark, not a regression test: it emulates a realistic collaborative editing
 * history over a huge file and prints the phase timings. The "Performance" name keeps
 * it out of the functional runs.
 *
 * The history imitates real users. Each user action is one of:
 * - type char by char: every keystroke is its own insert op (and its own run);
 * - autocomplete: one word lands as one insert op;
 * - copy-paste: a medium fragment of the current text lands as one insert op;
 * - move text: one delete op plus one insert op of the same fragment;
 * - rename a variable: a delete-insert pair per occurrence, back to front.
 *
 * The concurrency varies: the sessions run with 1, 2, 3, 4, and 5 users editing at
 * once. Every user forks from the current base, edits, and merges back. The first
 * merge of a session is a fast-forward; every later one resolves real concurrency
 * with a partial replay from the common ancestor over one lazily-split placeholder.
 * Its remaining per-merge costs are two O(graph size) arrays and the linear item
 * scans over the region. The size ladder makes what remains visible.
 */
class DocBranchPerformanceTest {

  @Test
  fun `a realistic collaborative history over EditorImpl`() {
    val fullText = Files.readString(hugeTextPath())
    println("the source text: ${fullText.length} chars")
    for (size in SIZES) {
      val text = if (size == 0 || size >= fullText.length) fullText else fullText.substring(0, size)
      runScenario(text)
    }
  }

  /**
   * The base case: one user edits the huge file alone, with no forks and no merges.
   * The batches report the throughput as the history grows, so a per-op cost that
   * scales with the history length shows up as growing batch times. At the end, the
   * recorded ops replay apply-only against a fresh branch and against a plain
   * [DocText]: their difference is the price of the history tracking per op.
   */
  @Test
  fun `a single user edits EditorImpl`() {
    val text = Files.readString(hugeTextPath())
    println("the source text: ${text.length} chars")
    val random = Random(20260827)
    val recorded = ArrayList<DocOp>()
    val user = User(DocBranch.createBranch(text, agent("user")))
    user.recorder = recorded

    for (batch in 0 until SINGLE_USER_BATCHES) {
      val start = System.nanoTime()
      var ops = 0
      repeat(SINGLE_USER_ACTIONS_PER_BATCH) {
        ops += performAction(user, random)
      }
      val graph = user.branch.graph()
      println("  batch $batch: $ops ops in ${sinceMs(start)} ms (history: ${graph.size()} units, ${graph.runCount()} runs)")
    }
    println("  total: ${recorded.size} ops, ${user.branch.text().length()} chars")

    // The same ops, apply-only, on a fresh branch and on a plain text.
    var fresh: DocBranch = DocBranch.createBranch(text, agent("user"))
    val freshStart = System.nanoTime()
    for (op in recorded) {
      fresh = fresh.applyOp(op)
    }
    val freshMs = sinceMs(freshStart)
    var plain = DocText.createText(text)
    val plainStart = System.nanoTime()
    for (op in recorded) {
      plain = plain.applyOp(op)
    }
    val plainMs = sinceMs(plainStart)
    println("  apply-only, ${recorded.size} ops: DocBranch $freshMs ms, plain DocText $plainMs ms")

    // The history-tracking branch and the plain text agree on every op.
    assertEquals(plain.string(), fresh.string())
    assertEquals(plain.string(), user.branch.string())
  }

  private fun runScenario(text: String) {
    println("=== the scenario over ${text.length} chars ===")
    val random = Random(20260827)
    val createStart = System.nanoTime()
    var base = DocBranch.createBranch(text, agent("base"))
    println("  create the base branch: ${sinceMs(createStart)} ms")

    for ((session, level) in CONCURRENCY_LEVELS.withIndex()) {
      // `level` users fork from the current base and edit concurrently.
      val users = ArrayList<User>()
      for (i in 0 until level) {
        users.add(User(base.fork(agent("user$i"))))
      }
      val editStart = System.nanoTime()
      var ops = 0
      for (user in users) {
        repeat(ACTIONS_PER_USER) {
          ops += performAction(user, random)
        }
      }
      println("  session $session: $level users, $ops ops in ${sinceMs(editStart)} ms")
      for ((i, user) in users.withIndex()) {
        base = timedMerge("    merge user$i", base, user.branch)
      }
    }

    println("  final: ${base.text().length()} chars, ${base.graph().size()} units, ${base.graph().runCount()} runs")
    assertTrue(base.text().length() > 0)
  }

  private fun timedMerge(label: String, base: DocBranch, other: DocBranch): DocBranch {
    val start = System.nanoTime()
    val merged = base.merge(other)
    val kind = if (merged.text() === other.text() || merged === base) "fast-forward" else "replay"
    println("$label: ${sinceMs(start)} ms ($kind, ${merged.graph().size()} units)")
    return merged
  }

  // ---------------------------------------------------------------------------- the user model

  private class User(var branch: DocBranch) {
    var caret = 0
    var recorder: ArrayList<DocOp>? = null

    fun apply(op: DocOp) {
      branch = branch.applyOp(op)
      recorder?.add(op)
    }
  }

  /** Performs one random user action and returns the op count it produced. */
  private fun performAction(user: User, random: Random): Int {
    return when (random.nextInt(89)) {
      in 0..39 -> typeChars(user, random)
      in 40..64 -> autocompleteWord(user, random)
      in 65..79 -> copyPasteFragment(user, random)
      in 80..89 -> moveFragment(user, random)
      else -> renameVariable(user, random) // TODO: renameVariable is too slow
    }
  }

  private fun moveCaret(user: User, random: Random) {
    user.caret = random.nextInt(user.branch.text().length() + 1)
  }

  private fun typeChars(user: User, random: Random): Int {
    moveCaret(user, random)
    val count = 5 + random.nextInt(25)
    repeat(count) {
      user.apply(insertOp(user.caret, TYPED[random.nextInt(TYPED.length)].toString()))
      user.caret++
    }
    return count
  }

  private fun autocompleteWord(user: User, random: Random): Int {
    moveCaret(user, random)
    val word = COMPLETIONS[random.nextInt(COMPLETIONS.size)]
    user.apply(insertOp(user.caret, word))
    user.caret += word.length
    return 1
  }

  private fun copyPasteFragment(user: User, random: Random): Int {
    val length = user.branch.text().length()
    if (length < 200) {
      return 0
    }
    val fragmentLength = minOf(100 + random.nextInt(301), length / 2)
    val from = random.nextInt(length - fragmentLength + 1)
    val fragment = user.branch.text().string(TextRange(from, from + fragmentLength))
    moveCaret(user, random)
    user.apply(insertOp(user.caret, fragment))
    user.caret += fragment.length
    return 1
  }

  private fun moveFragment(user: User, random: Random): Int {
    val length = user.branch.text().length()
    if (length < 100) {
      return 0
    }
    val fragmentLength = minOf(30 + random.nextInt(121), length / 2)
    val from = random.nextInt(length - fragmentLength + 1)
    val fragment = user.branch.text().string(TextRange(from, from + fragmentLength))
    user.apply(deleteOp(from, fragmentLength))
    val to = random.nextInt(user.branch.text().length() + 1)
    user.apply(insertOp(to, fragment))
    user.caret = to + fragment.length
    return 2
  }

  private fun renameVariable(user: User, random: Random): Int {
    val text = user.branch.string()
    val word = pickIdentifier(text, random) ?: return 0
    val newName = word + "Renamed"
    val occurrences = ArrayList<Int>()
    var at = text.indexOf(word)
    while (at >= 0 && occurrences.size < MAX_RENAME_OCCURRENCES) {
      occurrences.add(at)
      at = text.indexOf(word, at + word.length)
    }
    // Replace back to front, so the earlier offsets stay valid.
    for (i in occurrences.indices.reversed()) {
      val start = occurrences[i]
      user.apply(deleteOp(start, word.length))
      user.apply(insertOp(start, newName))
    }
    return occurrences.size * 2
  }

  private fun pickIdentifier(text: String, random: Random): String? {
    repeat(10) {
      val at = random.nextInt(text.length)
      if (text[at].isLetter()) {
        var start = at
        while (start > 0 && text[start - 1].isJavaIdentifierPart()) {
          start--
        }
        var end = at + 1
        while (end < text.length && text[end].isJavaIdentifierPart()) {
          end++
        }
        val word = text.substring(start, end)
        if (word.length in 6..30 && word[0].isLetter()) {
          return word
        }
      }
    }
    return null
  }

  // -------------------------------------------------------------------------------- utilities

  private fun sinceMs(startNanos: Long): Long {
    return (System.nanoTime() - startNanos) / 1_000_000
  }

  private fun hugeTextPath(): Path {
    return Path.of(PathManager.getCommunityHomePath(), "platform/platform-tests/testData/editor/docBranch/EditorImpl.java.txt")
  }

  companion object {
    /** The document sizes to run; 0 means the whole file. */
    private val SIZES = intArrayOf(25_000, 100_000, 0)

    /** The number of users that edit at once, session by session. */
    private val CONCURRENCY_LEVELS = intArrayOf(1, 2, 3, 4, 5)

    private const val ACTIONS_PER_USER = 6
    private const val SINGLE_USER_BATCHES = 1000
    private const val SINGLE_USER_ACTIONS_PER_BATCH = 200
    private const val MAX_RENAME_OCCURRENCES = 20
    private const val TYPED = "abcdefghijklmnopqrstuvwxyz    ();.{}\n"
    private val COMPLETIONS = arrayOf(
      "getDocument()",
      "invokeLater",
      "myScrollingModel",
      "CaretVisualAttributes",
      "repaintCaretRegion(caret)",
      "EditorImpl",
      "isReleased",
      "assertIsDispatchThread()",
    )
  }
}

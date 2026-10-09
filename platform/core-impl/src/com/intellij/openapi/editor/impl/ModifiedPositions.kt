// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl

import java.util.concurrent.ThreadLocalRandom

/**
 * An immutable sequence of positions, each clean or modified.
 *
 * Runs of one kind lie in a persistent treap, and two neighbouring runs always differ in kind. So
 * [splice], [fill] and [hasModified] cost `O(log runs)`, and an older value stays valid.
 */
internal class ModifiedPositions private constructor(private val root: Run) {

  /**
   * The number of positions.
   */
  fun size(): Int {
    return root.size
  }

  /**
   * The number of runs. Two neighbouring runs always differ in kind.
   */
  fun runCount(): Int {
    return root.count
  }

  /**
   * The positions with the positions from [start] to [end] replaced by [count] modified positions.
   */
  fun splice(start: Int, end: Int, count: Int): ModifiedPositions {
    return replace(start, end, count, modified = true)
  }

  /**
   * The positions with every position from [from] to [to] set to [modified].
   */
  fun fill(from: Int, to: Int, modified: Boolean): ModifiedPositions {
    return replace(from, to, to - from + 1, modified)
  }

  /**
   * Whether a position from [from] to [to] is modified.
   */
  fun hasModified(from: Int, to: Int): Boolean {
    return hasModified(root, from, to)
  }

  private fun replace(from: Int, to: Int, count: Int, modified: Boolean): ModifiedPositions {
    checkReplace(from, to, count)
    val (head, rest) = split(root, from)
    var before = head
    var after = split(rest, to - from + 1).second
    var length = count
    val lastBefore = lastRun(before)
    if (lastBefore != null && lastBefore.modified == modified) {
      before = split(before, sizeOf(before) - lastBefore.length).first
      length += lastBefore.length
    }
    val firstAfter = firstRun(after)
    if (firstAfter != null && firstAfter.modified == modified) {
      after = split(after, firstAfter.length).second
      length += firstAfter.length
    }
    val middle = Run.leaf(modified, length)
    return ModifiedPositions(checkNotNull(merge(merge(before, middle), after)))
  }

  private fun checkReplace(from: Int, to: Int, count: Int) {
    require(from in 0..to && to < size()) {
      "Wrong range $from..$to; size: ${size()}"
    }
    require(count > 0) {
      "Wrong count: $count"
    }
  }

  /**
   * One run of positions of one kind, and the root of the runs around it.
   */
  private class Run(
    val left: Run?,
    val right: Run?,
    val priority: Int,
    val modified: Boolean,
    val length: Int,
  ) {
    val size: Int = sizeOf(left) + length + sizeOf(right)
    val count: Int = countOf(left) + 1 + countOf(right)
    val hasModified: Boolean = modified || left?.hasModified == true || right?.hasModified == true

    fun withLeft(newLeft: Run?): Run {
      return Run(newLeft, right, priority, modified, length)
    }

    fun withRight(newRight: Run?): Run {
      return Run(left, newRight, priority, modified, length)
    }

    companion object {
      fun leaf(modified: Boolean, length: Int): Run {
        val priority = ThreadLocalRandom.current().nextInt()
        return Run(null, null, priority, modified, length)
      }
    }
  }

  companion object {
    /**
     * [size] clean positions.
     */
    fun clean(size: Int): ModifiedPositions {
      require(size > 0) {
        "Wrong size: $size"
      }
      return ModifiedPositions(Run.leaf(false, size))
    }

    private fun sizeOf(run: Run?): Int {
      return run?.size ?: 0
    }

    private fun countOf(run: Run?): Int {
      return run?.count ?: 0
    }

    /**
     * The first [count] positions of [run], and the rest.
     */
    private fun split(run: Run?, count: Int): Pair<Run?, Run?> {
      if (run == null) {
        return Pair(null, null)
      }
      val leftSize = sizeOf(run.left)
      if (count <= leftSize) {
        val (first, rest) = split(run.left, count)
        return Pair(first, run.withLeft(rest))
      }
      val runEnd = leftSize + run.length
      if (count >= runEnd) {
        val (first, rest) = split(run.right, count - runEnd)
        return Pair(run.withRight(first), rest)
      }
      val firstLength = count - leftSize
      val first = merge(run.left, Run.leaf(run.modified, firstLength))
      val rest = merge(Run.leaf(run.modified, run.length - firstLength), run.right)
      return Pair(first, rest)
    }

    /**
     * The positions of [first] followed by the positions of [second].
     */
    private fun merge(first: Run?, second: Run?): Run? {
      if (first == null) {
        return second
      }
      if (second == null) {
        return first
      }
      if (first.priority > second.priority) {
        return first.withRight(merge(first.right, second))
      }
      return second.withLeft(merge(first, second.left))
    }

    private fun firstRun(run: Run?): Run? {
      var first = run ?: return null
      while (true) {
        first = first.left ?: return first
      }
    }

    private fun lastRun(run: Run?): Run? {
      var last = run ?: return null
      while (true) {
        last = last.right ?: return last
      }
    }

    private fun hasModified(run: Run?, from: Int, to: Int): Boolean {
      if (run == null || !run.hasModified || to < 0 || from >= run.size) {
        return false
      }
      if (from <= 0 && to >= run.size - 1) {
        return true
      }
      if (hasModified(run.left, from, to)) {
        return true
      }
      val runStart = sizeOf(run.left)
      val runEnd = runStart + run.length
      if (run.modified && from < runEnd && to >= runStart) {
        return true
      }
      return hasModified(run.right, from - runEnd, to - runEnd)
    }
  }
}

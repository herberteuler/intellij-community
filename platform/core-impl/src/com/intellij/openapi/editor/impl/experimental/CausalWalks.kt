// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import java.util.BitSet
import java.util.Collections
import java.util.PriorityQueue

/*
 * The walks over the causal graph: ports of `diff` and `findConflicting` from the causal-graph
 * library of the reference implementation, plus the paper's `Events(V)`. Each walk reads the graph
 * through its queries and changes nothing, so the walks live beside the value and not inside it.
 *
 * `diff` and `findConflicting` take the greatest queued lv first, and consume the run that holds it
 * down to the run start in one step. So they cost one step per run they cross, and not one per unit.
 */

/** The side a walk flag names: the version a, the version b, or both. */
private const val FLAG_A = 0
private const val FLAG_B = 1
private const val FLAG_SHARED = 2

/** The paper's `Events(V)`: [version] and all its ancestors, as a set of lvs. */
internal fun EventGraphImpl.eventsOf(version: VersionImpl): BitSet {
  val seen = BitSet(size())
  val stack = ArrayDeque<Int>()
  for (lv in version.lvs) {
    stack.addLast(lv)
  }
  while (stack.isNotEmpty()) {
    val lv = stack.removeLast()
    if (seen.get(lv)) {
      continue
    }
    // The units before lv in the same run are its chain of ancestors: mark them at once.
    val run = runAt(lv)
    seen.set(run.lvStart, lv + 1)
    for (parent in run.runParents()) {
      if (!seen.get(parent)) {
        stack.addLast(parent)
      }
    }
  }
  return seen
}

/**
 * The lvs only in the history of [a] and only in the history of [b], as ascending ranges.
 * A port of `diff` from the reference implementation's causal-graph library.
 *
 * The walk takes the greatest queued lv, and then consumes the run that holds it down to the run
 * start in one step. A queued lv inside that run is an ancestor of the first one, so the step takes
 * it too. A change of the flag there cuts the run in two.
 */
internal fun EventGraphImpl.diff(a: Frontier, b: Frontier): VersionDiff {
  val flags = HashMap<Int, Int>()
  val queue = PriorityQueue<Int>(11, Collections.reverseOrder())
  var numShared = 0

  fun enqueue(lv: LV, flag: Int) {
    val current = flags[lv]
    if (current == null) {
      queue.add(lv)
      flags[lv] = flag
      if (flag == FLAG_SHARED) {
        numShared++
      }
    } else if (flag != current && current != FLAG_SHARED) {
      flags[lv] = FLAG_SHARED
      numShared++
    }
  }

  for (lv in a) {
    enqueue(lv, FLAG_A)
  }
  for (lv in b) {
    enqueue(lv, FLAG_B)
  }

  val aOnly = LvRanges.DescendingBuilder()
  val bOnly = LvRanges.DescendingBuilder()

  fun mark(start: LV, last: LV, flag: Int) {
    when (flag) {
      FLAG_A -> aOnly.add(start, last + 1)
      FLAG_B -> bOnly.add(start, last + 1)
    }
  }

  while (queue.size > numShared) {
    var lv = queue.poll()
    var flag = flagOf(flags, lv)
    if (flag == FLAG_SHARED) {
      numShared--
    }
    val run = runAt(lv)
    while (queue.isNotEmpty() && queue.peek() >= run.lvStart) {
      val inner = queue.poll()
      val innerFlag = flagOf(flags, inner)
      if (innerFlag == FLAG_SHARED) {
        numShared--
      }
      if (innerFlag != flag) {
        // Above the inner lv only the old flag reaches the units. From it down both sides do.
        mark(inner + 1, lv, flag)
        lv = inner
        flag = FLAG_SHARED
      }
    }
    mark(run.lvStart, lv, flag)
    for (parent in run.runParents()) {
      enqueue(parent, flag)
    }
  }
  return VersionDiff(aOnly.build(), bOnly.build())
}

/**
 * Finds the common ancestor of the versions [a] and [b], and splits the region above it in two.
 * [ConflictRegion.conflictRanges] holds the units in the history of [a], or of both.
 * [ConflictRegion.newRanges] holds the units only in the history of [b].
 *
 * A port of `findConflicting` from the reference implementation's causal-graph library. See
 * [walkToAncestor] for the walk.
 */
internal fun EventGraphImpl.findConflicting(a: Frontier, b: Frontier): ConflictRegion {
  val conflictRanges = LvRanges.DescendingBuilder()
  val newRanges = LvRanges.DescendingBuilder()
  val commonAncestor = walkToAncestor(a, b) { start, end, flag ->
    if (flag == FLAG_B) {
      newRanges.add(start, end)
    } else {
      conflictRanges.add(start, end)
    }
  }
  return ConflictRegion(VersionImpl(commonAncestor), conflictRanges.build(), newRanges.build())
}

/**
 * The walk of [findConflicting]: a max-first walk over version points. It passes every unit above
 * the common ancestor to [visit] with its flag, and returns the ancestor.
 *
 * Paths merge when they name the same version, and the walk stops when one point survives, which is
 * the ancestor. Like [diff], a step consumes the run of its head down to the run start. It cuts the
 * run where another queued point lands in it.
 */
private fun EventGraphImpl.walkToAncestor(
  a: Frontier,
  b: Frontier,
  visit: (start: LV, end: LV, flag: Int) -> Unit,
): Frontier {
  val queue = PriorityQueue(11, POINT_MAX_FIRST)
  queue.add(Point(descending(a), FLAG_A))
  queue.add(Point(descending(b), FLAG_B))
  while (true) {
    val point = queue.poll()
    var flag = point.flag
    if (point.v.isEmpty()) {
      // The walk reached the root: there is no common history below this point.
      return IntArray(0)
    }
    // Merge the queued points that name the same version.
    while (queue.isNotEmpty() && queue.peek().v.contentEquals(point.v)) {
      if (queue.poll().flag != flag) {
        flag = FLAG_SHARED
      }
    }
    if (queue.isEmpty()) {
      return ascending(point.v)
    }
    // Shatter a merger point; the head is processed below.
    for (i in 1 until point.v.size) {
      queue.add(Point(intArrayOf(point.v[i]), flag))
    }
    val run = runAt(point.v[0])
    // The units [run.lvStart, end) of the run are not visited yet.
    var end = point.v[0] + 1
    while (true) {
      if (queue.isEmpty()) {
        // The last unit not visited is the sole survivor: the ancestor.
        return intArrayOf(end - 1)
      }
      val next = queue.peek()
      if (next.v.isEmpty() || next.v[0] < run.lvStart) {
        // No other point lands in this run: visit the rest, and go on at its parents.
        visit(run.lvStart, end, flag)
        queue.add(Point(descending(run.runParents()), flag))
        break
      }
      // Another point lands inside this run, so it is an ancestor of the units above it.
      queue.poll()
      val landing = next.v[0]
      if (landing + 1 < end) {
        // The units above the landing point keep the flag so far. The landing point stays unvisited.
        visit(landing + 1, end, flag)
        end = landing + 1
      }
      if (next.flag != flag) {
        flag = FLAG_SHARED
      }
      // Shatter a merger point that lands here. Its head is this run, and its other heads queue.
      for (i in 1 until next.v.size) {
        queue.add(Point(intArrayOf(next.v[i]), next.flag))
      }
    }
  }
}

/** The flag of [lv], which the walk queued with one. */
private fun flagOf(flags: Map<Int, Int>, lv: LV): Int {
  val flag = flags[lv]
  require(flag != null) {
    "The lv $lv is in the walk queue, but it has no flag"
  }
  return flag
}

/** A version under the walk of [findConflicting]: the lvs sorted descending, plus the flag. */
private class Point(val v: Frontier, val flag: Int) {
  override fun toString(): String {
    return "Point(${v.listedForMessage()}, ${flagName(flag)})"
  }
}

/** The side a walk flag names, as a word. */
private fun flagName(flag: Int): String {
  return when (flag) {
    FLAG_A -> "a"
    FLAG_B -> "b"
    else -> "shared"
  }
}

/** Orders the walk points of [findConflicting]: the greatest version first. */
private val POINT_MAX_FIRST = Comparator<Point> { p1, p2 ->
  val a = p1.v
  val b = p2.v
  val common = minOf(a.size, b.size)
  for (i in 0 until common) {
    if (a[i] != b[i]) {
      return@Comparator b[i] - a[i]
    }
  }
  if (a.size != b.size) {
    return@Comparator b.size - a.size
  }
  p2.flag - p1.flag
}

private fun descending(lvs: Frontier): Frontier {
  return lvs.sortedArrayDescending()
}

private fun ascending(lvs: Frontier): Frontier {
  return lvs.sortedArray()
}

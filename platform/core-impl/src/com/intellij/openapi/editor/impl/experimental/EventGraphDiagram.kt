// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Event

/**
 * Draws an [EventGraphImpl] as a box diagram. One box is one run, and a line between two
 * boxes says that the lower run follows the upper one.
 *
 * ```
 * ┌───────────────┐
 * │ insert "abc"  │
 * │ offset 0      │
 * │ lv 0..2       │
 * │ agent "user1" │
 * └───────┬───────┘
 *         │
 *         ├──────────────────┐
 *         │                  │
 * ┌───────┴───────┐  ┌───────┴───────┐
 * ```
 *
 * A box holds one field per line, so a box stays narrow and several branches fit side by
 * side. A branch keeps one column for as long as it runs, and the column stays reserved
 * while the branch waits for its next run, so a `│` never changes owner.
 *
 * One row of boxes holds every run at one depth, where the depth of a run is one more than
 * the deepest of its parents. So two boxes in one row are always concurrent: an ancestor has
 * a smaller depth than its descendant, and it therefore lands in an earlier row.
 *
 * Two things the diagram cannot show. A parent unit can sit inside a run, and a line reaches
 * the box of the run and not the unit. A run also keeps at least one parent in the row above
 * it, because its depth follows its deepest parent, but an older parent gets its line drawn
 * from its own column and not from its own box.
 *
 * The diagram is a debugging aid and it reaches the log, so it is bounded. A long history
 * loses its MIDDLE and not its end: the diagram draws the first [HEAD_RUNS] runs, then the
 * count of what it dropped, then the newest runs. So it always shows where the document
 * started and what happened last, which is what a failure asks about. A box costs six lines,
 * so see [MAX_RUNS] and [MAX_RUNS_PER_ROW].
 *
 * The runs below the count line hold parents that the diagram dropped. A `┴` there joins the
 * count line and not a box, and the depth of such a run starts again at the count line.
 */
internal object EventGraphDiagram {

  /** The greatest number of runs that one diagram draws. A box costs six lines. */
  private const val MAX_RUNS = 40

  /** The number of runs that a trimmed diagram keeps at the start of the history. */
  private const val HEAD_RUNS = 2

  /** The greatest number of concurrent runs that one row draws. */
  private const val MAX_RUNS_PER_ROW = 6

  /** The spaces between two boxes that stand side by side. */
  private const val GAP = 2

  private const val NO_COLUMN = -1

  fun render(graph: EventGraphImpl): String {
    val count = graph.runCount()
    val text = StringBuilder()
    text.append("EventGraph(units=").append(graph.size())
      .append(", runs=").append(count)
      .append(", version=").append(graph.versionImpl())
      .append(')')
    if (count <= MAX_RUNS) {
      if (count > 0) {
        Diagram(graph, (0 until count).toList()).appendTo(text)
      }
      return text.toString()
    }
    // The two segments are laid out on their own, because the runs between them are gone and
    // no column can carry a line across the gap.
    Diagram(graph, (0 until HEAD_RUNS).toList()).appendTo(text)
    text.append("\n... ").append(count - MAX_RUNS).append(" more runs ...")
    Diagram(graph, (count - (MAX_RUNS - HEAD_RUNS) until count).toList()).appendTo(text)
    return text.toString()
  }

  /** The lines of one box, one field per line. */
  private fun body(run: StoredRun): List<String> {
    val event = run.event
    val head = when (event) {
      is Event.Insert -> "insert ${event.fragment().quotedForMessage()}"
      is Event.Delete -> "delete ${event.length()} ${if (event.length() == 1) "char" else "chars"}"
    }
    return listOf(head, "offset ${event.offset()}", "lv ${lvs(run)}", "agent \"${event.agent()}\"")
  }

  /** The lv range of [run]: one lv for a run of one unit, and `first..last` for a longer one. */
  private fun lvs(run: StoredRun): String {
    val last = run.lvEnd() - 1
    return if (run.lvStart == last) "${run.lvStart}" else "${run.lvStart}..$last"
  }

  /**
   * The [runs] placed in rows and columns, and the box art for them.
   *
   * A run continues the column of its first parent when it is the first run to do so. Every
   * other run takes a free column, which is what moves a second branch aside. A column stays
   * reserved until the last run that descends from it, so two branches never share one.
   *
   * [runs] holds the run indexes to draw, ascending. Every array below indexes a SLOT in that
   * list and not a run of the graph, so a diagram of a trimmed history stays as small as what
   * it draws. A parent outside [runs] counts for nothing, so a run whose parents are all
   * outside starts at the depth 0.
   */
  private class Diagram(private val graph: EventGraphImpl, private val runs: List<Int>) {
    /** The slot of each drawn run, by its run index. */
    private val slots = HashMap<Int, Int>(runs.size)
    private val depths = IntArray(runs.size)
    private val rows = ArrayList<MutableList<Int>>()
    private val hiddenInRow = ArrayList<Int>()
    private val children = Array(runs.size) { ArrayList<Int>() }
    private val parents = Array(runs.size) { emptyList<Int>() }
    private val columns = IntArray(runs.size) { NO_COLUMN }
    private val bodies = arrayOfNulls<List<String>>(runs.size)
    private val widths: IntArray
    private val middles: IntArray
    private val starts: IntArray
    private val width: Int

    init {
      for (slot in runs.indices) {
        slots[runs[slot]] = slot
      }
      buildRows()
      buildLinks()
      buildColumns()
      widths = widths()
      starts = IntArray(widths.size)
      middles = IntArray(widths.size)
      var offset = 0
      for (column in widths.indices) {
        starts[column] = offset
        middles[column] = offset + widths[column] / 2
        offset += widths[column] + GAP
      }
      width = if (widths.isEmpty()) 0 else starts[widths.size - 1] + widths[widths.size - 1]
    }

    fun appendTo(text: StringBuilder) {
      for (row in rows.indices) {
        if (row > 0) {
          appendConnector(text, row - 1)
        }
        appendRow(text, row)
      }
    }

    // --------------------------------------------------------------------------------- layout

    /** The run of the [slot]. */
    private fun runOf(slot: Int): StoredRun {
      return graph.runByIndex(runs[slot])
    }

    /** The slot of the run that holds the parent unit [parent], or `null` when it is not drawn. */
    private fun parentSlot(parent: LV): Int? {
      return slots[graph.runIndexOf(parent)]
    }

    /** Groups the runs by depth, and drops the ones that [MAX_RUNS_PER_ROW] leaves out. */
    private fun buildRows() {
      for (slot in runs.indices) {
        var depth = 0
        for (parent in runOf(slot).runParents()) {
          val parentSlot = parentSlot(parent) ?: continue
          depth = maxOf(depth, depths[parentSlot] + 1)
        }
        depths[slot] = depth
        while (rows.size <= depth) {
          rows.add(ArrayList())
          hiddenInRow.add(0)
        }
        if (rows[depth].size < MAX_RUNS_PER_ROW) {
          rows[depth].add(slot)
          bodies[slot] = body(runOf(slot))
        } else {
          hiddenInRow[depth] = hiddenInRow[depth] + 1
        }
      }
    }

    /** Links the drawn runs to the drawn parents and children. A dropped run links to none. */
    private fun buildLinks() {
      for (row in rows) {
        for (slot in row) {
          val found = ArrayList<Int>()
          for (parent in runOf(slot).runParents()) {
            val parentSlot = parentSlot(parent) ?: continue
            if (bodies[parentSlot] != null && !found.contains(parentSlot)) {
              found.add(parentSlot)
              children[parentSlot].add(slot)
            }
          }
          parents[slot] = found
        }
      }
    }

    private fun buildColumns() {
      val tips = ArrayList<Int>()
      val reserved = ArrayList<Int>()
      for (row in rows.indices) {
        for (slot in rows[row]) {
          val first = parents[slot].firstOrNull() ?: NO_COLUMN
          val inherited = if (first == NO_COLUMN) NO_COLUMN else columns[first]
          val column = if (inherited != NO_COLUMN && tips[inherited] == first) {
            inherited
          } else {
            freeColumn(reserved, row, tips)
          }
          tips[column] = slot
          columns[slot] = column
          reserved[column] = maxOf(reserved[column], row, deepestChild(slot))
        }
      }
    }

    private fun freeColumn(reserved: MutableList<Int>, row: Int, tips: MutableList<Int>): Int {
      for (column in reserved.indices) {
        if (reserved[column] < row) {
          return column
        }
      }
      reserved.add(row)
      tips.add(NO_COLUMN)
      return reserved.size - 1
    }

    private fun deepestChild(slot: Int): Int {
      var deepest = depths[slot]
      for (child in children[slot]) {
        deepest = maxOf(deepest, depths[child])
      }
      return deepest
    }

    private fun widths(): IntArray {
      var count = 0
      for (slot in runs.indices) {
        count = maxOf(count, columns[slot] + 1)
      }
      val widths = IntArray(count)
      for (slot in runs.indices) {
        val body = bodies[slot] ?: continue
        // A box holds one space of padding, a border, and the widest line of its body.
        val box = body.maxOf { it.length } + 4
        widths[columns[slot]] = maxOf(widths[columns[slot]], box)
      }
      return widths
    }

    // ---------------------------------------------------------------------------------- boxes

    private fun appendRow(text: StringBuilder, row: Int) {
      val height = bodies[rows[row][0]]!!.size + 2
      for (line in 0 until height) {
        val chars = CharArray(width) { ' ' }
        for (column in widths.indices) {
          val slot = rows[row].firstOrNull { columns[it] == column }
          if (slot != null) {
            boxLine(slot, line).toCharArray(chars, starts[column])
          } else if (passesThrough(column, row)) {
            chars[middles[column]] = '│'
          }
        }
        text.append('\n').append(String(chars).trimEnd())
      }
      if (hiddenInRow[row] > 0) {
        text.append("\n... ").append(hiddenInRow[row]).append(" more concurrent runs ...")
      }
    }

    /** The line [line] of the box of the [slot], as wide as its column. */
    private fun boxLine(slot: Int, line: Int): String {
      val body = bodies[slot]!!
      val boxWidth = widths[columns[slot]]
      if (line == 0) {
        return border(boxWidth, '┌', '┐', if (hasParent(slot)) '┴' else '─')
      }
      if (line > body.size) {
        return border(boxWidth, '└', '┘', if (hasChild(slot)) '┬' else '─')
      }
      val inner = body[line - 1]
      return "│ " + inner + " ".repeat(boxWidth - inner.length - 4) + " │"
    }

    /**
     * Whether the run of the [slot] follows another run of the GRAPH, drawn or not.
     *
     * A `┴` therefore always means "this run has a parent". In a trimmed diagram the parent
     * can be one the diagram dropped, and then the join meets the count line.
     */
    private fun hasParent(slot: Int): Boolean {
      return runOf(slot).runParents().isNotEmpty()
    }

    /**
     * Whether the run of the [slot] is followed by another run of the GRAPH, drawn or not.
     *
     * A version names every unit that has no child, so a run whose last unit is absent from
     * the version has one. That test is cheap, and it needs no scan over the runs that come
     * after this one. It misses a child that names a unit inside the run and not its last, so
     * the drawn children answer first.
     */
    private fun hasChild(slot: Int): Boolean {
      return children[slot].isNotEmpty() || !graph.versionImpl().contains(runOf(slot).lvEnd() - 1)
    }

    /** A box border of [boxWidth], with [middle] where a line joins it. */
    private fun border(boxWidth: Int, left: Char, right: Char, middle: Char): String {
      val chars = CharArray(boxWidth) { '─' }
      chars[0] = left
      chars[boxWidth - 1] = right
      chars[boxWidth / 2] = middle
      return String(chars)
    }

    /**
     * Whether a line crosses the row [row] in the column [column] without a box.
     *
     * A branch that makes fewer edits than its neighbour waits for its next run, and the
     * column carries the line down through the rows it has no box in.
     */
    private fun passesThrough(column: Int, row: Int): Boolean {
      for (slot in runs.indices) {
        if (columns[slot] != column || depths[slot] >= row) {
          continue
        }
        for (child in children[slot]) {
          if (depths[child] > row) {
            return true
          }
        }
      }
      return false
    }

    // ----------------------------------------------------------------------------- connectors

    /**
     * Appends the lines between the row [row] and the row below it.
     *
     * A straight run of lines takes one line. A row that opens or closes a branch takes
     * three: the lines that come down, the row that joins the columns, and the lines that go
     * on. The joining row is drawn from the four directions each position connects to, so
     * every corner and tee comes out right whatever the shape.
     */
    private fun appendConnector(text: StringBuilder, row: Int) {
      val below = row + 1
      val up = BooleanArray(widths.size)
      val down = BooleanArray(widths.size)
      val left = BooleanArray(width)
      val right = BooleanArray(width)
      var joined = false
      for (slot in rows[below]) {
        for (parent in parents[slot]) {
          val from = columns[parent]
          val to = columns[slot]
          up[from] = true
          if (from == to) {
            down[to] = true
          } else {
            down[to] = true
            joined = true
            for (x in minOf(middles[from], middles[to]) until maxOf(middles[from], middles[to])) {
              right[x] = true
              left[x + 1] = true
            }
          }
        }
      }
      // A line that only crosses this gap keeps its column on both sides.
      for (column in widths.indices) {
        if (passesThrough(column, below)) {
          up[column] = true
          down[column] = true
        }
      }
      if (!joined) {
        text.append('\n').append(verticals(up, down))
        return
      }
      text.append('\n').append(verticals(up, BooleanArray(widths.size) { true }))
      text.append('\n').append(junction(up, down, left, right))
      text.append('\n').append(verticals(BooleanArray(widths.size) { true }, down))
    }

    /** One line with a `│` in every column that both [up] and [down] name. */
    private fun verticals(up: BooleanArray, down: BooleanArray): String {
      val chars = CharArray(width) { ' ' }
      for (column in widths.indices) {
        if (up[column] && down[column]) {
          chars[middles[column]] = '│'
        }
      }
      return String(chars).trimEnd()
    }

    /** The line that joins the columns, one character per connected direction. */
    private fun junction(up: BooleanArray, down: BooleanArray, left: BooleanArray, right: BooleanArray): String {
      val chars = CharArray(width) { ' ' }
      for (x in 0 until width) {
        val column = widths.indices.firstOrNull { middles[it] == x }
        val mask = (if (column != null && up[column]) 8 else 0) or
                   (if (column != null && down[column]) 4 else 0) or
                   (if (left[x]) 2 else 0) or
                   (if (right[x]) 1 else 0)
        chars[x] = JUNCTIONS[mask]
      }
      return String(chars).trimEnd()
    }

    private companion object {
      /**
       * The character for each set of connected directions, as `up|down|left|right` bits.
       * The index 0 is a gap, and a single direction keeps the line going.
       */
      private val JUNCTIONS = charArrayOf(
        ' ', '─', '─', '─', // ....  ...R  ..L.  ..LR
        '│', '┌', '┐', '┬', // .D..  .D.R  .DL.  .DLR
        '│', '└', '┘', '┴', // U...  U..R  U.L.  U.LR
        '│', '├', '┤', '┼', // UD..  UD.R  UDL.  UDLR
      )
    }
  }
}

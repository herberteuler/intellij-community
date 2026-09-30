// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Tests the state machine of [Item] against the table in its KDoc: every transition the table
 * lists goes to the state it names, and every other transition fails and changes nothing.
 *
 * A state shows through three flags. The flags of the deleted states are equal for every k, so
 * a test tells the deleted states apart by the number of delete retreats that take the item
 * back to D. E1 to E3 stand for every k.
 */
internal class ItemTest {

  @Test
  fun `a new item is inserted in both versions`() {
    val item = item()
    assertEquals(State.A, stateOf(item))
    assertEquals(LENGTH, item.prepareWidth)
    assertEquals(LENGTH, item.effectWidth)
  }

  @Test
  fun `every listed transition goes to the state the table names`() {
    for ((from, transition, to) in LISTED) {
      val item = itemIn(from)
      transition.apply(item)
      assertEquals(to, stateOf(item)) { "$from, $transition" }
      assertEquals(to.deletes, deletesOf(item)) { "$from, $transition: the delete count" }
    }
  }

  /** [State.E3] is only a destination: its delete advance leads past the tested counts. */
  @Test
  fun `every other transition fails and keeps the state`() {
    for (from in State.entries.filter { it != State.E3 }) {
      for (transition in Transition.entries) {
        if (LISTED.any { it.from == from && it.transition == transition }) {
          continue
        }
        val item = itemIn(from)
        assertThrows(IllegalArgumentException::class.java, { transition.apply(item) }) { "$from, $transition" }
        assertEquals(from, stateOf(item)) { "$from, $transition: the state changed" }
        assertEquals(from.deletes, deletesOf(item)) { "$from, $transition: the delete count changed" }
      }
    }
  }

  @Test
  fun `a split gives both pieces the state of the span`() {
    for (from in State.entries) {
      val left = itemIn(from)
      val right = left.splitAfter(1)
      for (piece in listOf(left, right)) {
        assertEquals(from, stateOf(piece)) { "$from: $piece" }
        assertEquals(from.deletes, deletesOf(piece)) { "$from: the delete count of $piece" }
      }
    }
  }

  @Test
  fun `a split of a real span anchors the right piece on the unit before it`() {
    val left = Item(lv = 10, length = LENGTH, originLeft = 4, rightParent = 7)
    val right = left.splitAfter(2)
    assertEquals(10, left.firstUnit)
    assertEquals(11, left.lastUnit)
    assertEquals(4, left.originLeft)
    assertEquals(7, left.rightParent)
    assertEquals(12, right.firstUnit)
    assertEquals(12, right.lastUnit)
    assertEquals(11, right.originLeft)
    assertEquals(NO_UNIT, right.rightParent)
  }

  @Test
  fun `a split of a placeholder keeps the document start as the left origin`() {
    val left = Item(lv = -1 - LENGTH, length = LENGTH, originLeft = NO_UNIT, rightParent = NO_UNIT)
    val right = left.splitAfter(1)
    assertEquals(NO_UNIT, right.originLeft)
    assertEquals(NO_UNIT, right.rightParent)
    assertEquals(-2, right.lastUnit)
  }

  @Test
  fun `a split outside the span is rejected`() {
    val item = item()
    assertThrows(IllegalArgumentException::class.java) { item.splitAfter(0) }
    assertThrows(IllegalArgumentException::class.java) { item.splitAfter(LENGTH) }
    assertEquals(LENGTH, item.length)
  }

  @Test
  fun `an empty span is rejected`() {
    assertThrows(IllegalArgumentException::class.java) { Item(lv = 0, length = 0, originLeft = NO_UNIT, rightParent = NO_UNIT) }
    assertThrows(IllegalArgumentException::class.java) { Item(lv = 0, length = -1, originLeft = NO_UNIT, rightParent = NO_UNIT) }
  }

  /** The raw states are the encoding of the private constants: -1, 0, and a count of deletes. */
  @Test
  fun `a pair outside the states is rejected`() {
    val outside = listOf(
      -2 to 0, // no such prepare state
      0 to -1, // no such effect state
      0 to 2, // no such effect state
      1 to 0, // the prepare version deleted it, but the effect version has it
    )
    for ((prepare, effect) in outside) {
      assertThrows(IllegalArgumentException::class.java, {
        Item(lv = 0, length = LENGTH, originLeft = NO_UNIT, rightParent = NO_UNIT, prepareState = prepare, effectState = effect)
      }) { "prepare $prepare, effect $effect" }
    }
    Item(lv = 0, length = LENGTH, originLeft = NO_UNIT, rightParent = NO_UNIT, prepareState = 3, effectState = 1)
  }

  /** The states of the KDoc table, with the deleted state at three values of k, which is [deletes]. */
  private enum class State(val inPrepare: Boolean, val inEffect: Boolean, val applied: Boolean, val deletes: Int) {
    A(inPrepare = true, inEffect = true, applied = true, deletes = 0),
    B(inPrepare = false, inEffect = true, applied = false, deletes = 0),
    C(inPrepare = false, inEffect = false, applied = false, deletes = 0),
    D(inPrepare = true, inEffect = false, applied = true, deletes = 0),
    E1(inPrepare = false, inEffect = false, applied = true, deletes = 1),
    E2(inPrepare = false, inEffect = false, applied = true, deletes = 2),
    E3(inPrepare = false, inEffect = false, applied = true, deletes = 3),
  }

  private enum class Transition(val apply: (Item) -> Unit) {
    DELETE_HERE({ it.deleteHere() }),
    RETREAT_INSERT({ it.retreat(isDelete = false) }),
    ADVANCE_INSERT({ it.advance(isDelete = false) }),
    RETREAT_DELETE({ it.retreat(isDelete = true) }),
    ADVANCE_DELETE({ it.advance(isDelete = true) }),
  }

  private data class Step(val from: State, val transition: Transition, val to: State)

  /** Builds an item in [state] through listed transitions only, starting from a new item. */
  private fun itemIn(state: State): Item {
    val item = item()
    when (state) {
      State.A -> {}
      State.B -> item.retreat(isDelete = false)
      State.C -> {
        toD(item)
        item.retreat(isDelete = false)
      }
      State.D -> toD(item)
      State.E1 -> item.deleteHere()
      State.E2, State.E3 -> {
        item.deleteHere()
        repeat(state.deletes - 1) {
          item.advance(isDelete = true)
        }
      }
    }
    return item
  }

  private fun toD(item: Item) {
    item.deleteHere()
    item.retreat(isDelete = true)
  }

  private fun stateOf(item: Item): State {
    val flags = State.entries.filter {
      it.inPrepare == item.inPrepare && it.inEffect == item.inEffect && it.applied == item.appliedInPrepare
    }
    val deletes = deletesOf(item)
    val state = flags.singleOrNull { it.deletes == deletes }
    require(state != null) {
      "The item $item matches no state"
    }
    return state
  }

  /**
   * The k of a deleted prepare state: the delete retreats that take [item] back to D. The same
   * number of delete advances then restores the state, so the item ends as it began. 0 when the
   * prepare version has not deleted the item.
   */
  private fun deletesOf(item: Item): Int {
    if (item.inPrepare || !item.appliedInPrepare) {
      return 0
    }
    var deletes = 0
    while (!item.inPrepare) {
      item.retreat(isDelete = true)
      deletes++
    }
    repeat(deletes) {
      item.advance(isDelete = true)
    }
    return deletes
  }

  private fun item(): Item {
    return Item(lv = 0, length = LENGTH, originLeft = NO_UNIT, rightParent = NO_UNIT)
  }

  private companion object {
    const val LENGTH = 3

    val LISTED = listOf(
      Step(State.A, Transition.DELETE_HERE, State.E1),
      Step(State.D, Transition.DELETE_HERE, State.E1),
      Step(State.A, Transition.RETREAT_INSERT, State.B),
      Step(State.D, Transition.RETREAT_INSERT, State.C),
      Step(State.B, Transition.ADVANCE_INSERT, State.A),
      Step(State.C, Transition.ADVANCE_INSERT, State.D),
      Step(State.E1, Transition.RETREAT_DELETE, State.D),
      Step(State.E2, Transition.RETREAT_DELETE, State.E1),
      Step(State.D, Transition.ADVANCE_DELETE, State.E1),
      Step(State.E1, Transition.ADVANCE_DELETE, State.E2),
      Step(State.E3, Transition.RETREAT_DELETE, State.E2),
      Step(State.E2, Transition.ADVANCE_DELETE, State.E3),
    )
  }
}

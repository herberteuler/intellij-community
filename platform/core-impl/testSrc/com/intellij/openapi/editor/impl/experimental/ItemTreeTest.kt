// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Tests [ItemTree] against a flat list of the same items. The random rounds insert, split and
 * change states until the tree is three inner levels deep, and they compare every lookup with a
 * scan of the list.
 */
internal class ItemTreeTest {

  @Test
  fun `an empty tree holds nothing`() {
    val tree = ItemTree()
    assertEquals(0, tree.size())
    assertEquals(0, tree.prepareWidth())
    assertThrows(IllegalArgumentException::class.java) { tree.get(0) }
    assertThrows(IllegalArgumentException::class.java) { tree.cursorBefore(0) }
    val failure = assertThrows(IllegalArgumentException::class.java) { tree.itemCovering(0) }
    assertEquals("No item covers the unit 0", failure.message)
    tree.checkInvariants()
  }

  @Test
  fun `a lookup by prepare position passes the items of no prepare width`() {
    val tree = ItemTree()
    tree.add(0, item(firstUnit = 0, length = 2))
    tree.add(1, item(firstUnit = 2, length = 3))
    tree.add(2, item(firstUnit = 5, length = 1))
    // The middle item is not inserted in the prepare version, but the effect version has it.
    tree.retreat(tree.get(1), isDelete = false)
    assertEquals(3, tree.prepareWidth())
    val cursor = tree.cursorBefore(2)
    assertEquals(2, cursor.itemIndex)
    assertEquals(2, cursor.preparePos)
    assertEquals(5, cursor.effectPos)
    assertEquals(5, tree.get(cursor.itemIndex).firstUnit)
    assertEquals(0, tree.cursorBefore(1).itemIndex)
    tree.checkInvariants()
  }

  @Test
  fun `a lookup by prepare position passes whole leaves of no prepare width`() {
    val tree = ItemTree()
    for (i in 0 until 100) {
      tree.add(i, item(firstUnit = 2 * i, length = 2))
    }
    // The first 70 items leave the prepare version, so two whole leaves have no prepare width.
    for (i in 0 until 70) {
      tree.retreat(tree.get(i), isDelete = false)
    }
    val cursor = tree.cursorBefore(0)
    assertEquals(70, cursor.itemIndex)
    assertEquals(0, cursor.preparePos)
    assertEquals(140, cursor.effectPos)
    assertEquals(140, tree.get(cursor.itemIndex).firstUnit)
    assertEquals(1, tree.depth())
    tree.checkInvariants()
  }

  @Test
  fun `the next applied item lies past whole leaves of unapplied items`() {
    val tree = ItemTree()
    // Appends fill leaves of 32 items: 0 to 31, 32 to 63, 64 to 95, and 96 to 99.
    for (i in 0 until 100) {
      tree.add(i, item(firstUnit = 2 * i, length = 2))
    }
    // The items 10 to 95 leave the prepare version, so the second and the third leaf hold no
    // applied item.
    for (i in 10 until 96) {
      tree.retreat(tree.get(i), isDelete = false)
    }
    assertSame(tree.get(9), tree.firstAppliedFrom(9))
    assertSame(tree.get(96), tree.firstAppliedFrom(10))
    assertSame(tree.get(96), tree.firstAppliedFrom(50))
    for (i in 96 until 100) {
      tree.retreat(tree.get(i), isDelete = false)
    }
    assertNull(tree.firstAppliedFrom(10))
    assertNull(tree.firstAppliedFrom(100))
    tree.checkInvariants()
  }

  @Test
  fun `an item can be in one tree only`() {
    val first = ItemTree()
    val item = item(firstUnit = 0, length = 1)
    first.add(0, item)
    val failure = assertThrows(IllegalArgumentException::class.java) { ItemTree().add(0, item) }
    assertTrue(failure.message.orEmpty().endsWith("is in a tree already"), failure.message)
    // Once the unit index exists, a second item at the same first unit fails, and the tree stays
    // as it was.
    assertSame(item, first.itemCovering(0))
    val twin = item(firstUnit = 0, length = 1)
    assertThrows(IllegalArgumentException::class.java) { first.add(1, twin) }
    assertEquals(1, first.size())
    first.checkInvariants()
  }

  @Test
  fun `the first lookup by unit fails on two items at one first unit`() {
    val tree = ItemTree()
    tree.add(0, item(firstUnit = 0, length = 1))
    tree.add(1, item(firstUnit = 0, length = 1))
    val failure = assertThrows(IllegalArgumentException::class.java) { tree.itemCovering(0) }
    val message = failure.message.orEmpty()
    assertTrue(message.endsWith("start at one unit"), message)
    // The failed build keeps no index, so the next lookup fails the same way.
    assertFalse(tree.hasUnitIndex())
    val again = assertThrows(IllegalArgumentException::class.java) { tree.indexCovering(0) }
    assertEquals(message, again.message)
    assertEquals(2, tree.size())
    val invariants = assertThrows(IllegalArgumentException::class.java) { tree.checkInvariants() }
    assertEquals(message, invariants.message)
  }

  @Test
  fun `the unit index waits for the first lookup by unit, then stays current`() {
    val tree = ItemTree()
    for (i in 0 until 100) {
      tree.add(i, item(firstUnit = 4 * i, length = 4))
    }
    tree.splitAt(10, 2)
    assertFalse(tree.hasUnitIndex())
    assertEquals(11, tree.indexCovering(42))
    assertTrue(tree.hasUnitIndex())
    // Inserts and splits after the build reach the index too.
    tree.add(0, item(firstUnit = 1000, length = 3))
    tree.splitAt(0, 1)
    assertEquals(1, tree.indexCovering(1002))
    assertEquals(13, tree.indexCovering(42))
    tree.checkInvariants()
  }

  @Test
  fun `a unit between two items is covered by none`() {
    val tree = ItemTree()
    tree.add(0, item(firstUnit = 0, length = 2))
    tree.add(1, item(firstUnit = 5, length = 2))
    assertEquals(1, tree.indexCovering(6))
    val failure = assertThrows(IllegalArgumentException::class.java) { tree.indexCovering(3) }
    assertEquals("No item covers the unit 3", failure.message)
  }

  @Test
  fun `a split files the right piece after the item`() {
    val tree = ItemTree()
    tree.add(0, item(firstUnit = 10, length = 4))
    tree.splitAt(0, 1)
    assertEquals(2, tree.size())
    assertEquals(11, tree.get(1).firstUnit)
    assertSame(tree.get(1), tree.itemCovering(13))
    assertEquals(4, tree.prepareWidth())
    tree.checkInvariants()
  }

  @Test
  fun `random inserts, splits and state changes match a flat list`() {
    val random = Random(20261001L)
    val tree = ItemTree()
    val expected = ArrayList<Item>()
    var nextUnit = 0
    var step = 0
    while (expected.size < ITEMS) {
      val roll = random.nextInt(10)
      if (roll < 6 || expected.isEmpty()) {
        val index = insertIndex(random, expected.size)
        val newItem = item(firstUnit = nextUnit, length = 1 + random.nextInt(4))
        nextUnit += newItem.length
        tree.add(index, newItem)
        expected.add(index, newItem)
      } else if (roll < 8) {
        split(random, tree, expected)
      } else {
        changeState(random, tree, expected[random.nextInt(expected.size)])
      }
      step++
      // A read at a random place moves the cached leaf, so the next change works against it.
      val probe = random.nextInt(expected.size)
      assertSame(expected[probe], tree.get(probe)) { "step $step, index $probe" }
      if (step % CHECK_EVERY == 0) {
        checkTree(tree, expected, random)
      }
    }
    checkTree(tree, expected, random)
    assertTrue(tree.depth() >= 3, tree.toString())
  }

  /**
   * An index biased to the end and the start, where a walk inserts most, with the rest spread.
   */
  private fun insertIndex(random: Random, size: Int): Int {
    return when (random.nextInt(4)) {
      0 -> size
      1 -> 0
      else -> random.nextInt(size + 1)
    }
  }

  /**
   * Splits a random item, and checks the right piece against the item before the split.
   */
  private fun split(random: Random, tree: ItemTree, expected: ArrayList<Item>) {
    val index = random.nextInt(expected.size)
    val item = expected[index]
    if (item.length < 2) {
      return
    }
    val units = 1 + random.nextInt(item.length - 1)
    val rightFirstUnit = item.firstUnit + units
    val rightLength = item.length - units
    tree.splitAt(index, units)
    val right = tree.get(index + 1)
    assertEquals(rightFirstUnit, right.firstUnit)
    assertEquals(rightLength, right.length)
    assertEquals(units, item.length)
    expected.add(index + 1, right)
  }

  /**
   * Applies one transition that the state of [item] allows, picked at random.
   */
  private fun changeState(random: Random, tree: ItemTree, item: Item) {
    val choices = ArrayList<() -> Unit>()
    if (item.inPrepare) {
      choices.add { tree.deleteHere(item) }
      choices.add { tree.retreat(item, isDelete = false) }
    }
    if (!item.appliedInPrepare) {
      choices.add { tree.advance(item, isDelete = false) }
    }
    if (!item.inEffect && item.appliedInPrepare) {
      choices.add { tree.advance(item, isDelete = true) }
      if (!item.inPrepare) {
        choices.add { tree.retreat(item, isDelete = true) }
      }
    }
    choices[random.nextInt(choices.size)]()
  }

  /**
   * Compares every lookup of [tree] with a scan of [expected], and checks the invariants.
   */
  private fun checkTree(tree: ItemTree, expected: List<Item>, random: Random) {
    tree.checkInvariants()
    assertEquals(expected.size, tree.size())
    assertEquals(expected.sumOf { it.prepareWidth }, tree.prepareWidth())
    for ((index, item) in expected.withIndex()) {
      assertSame(item, tree.get(index)) { "index $index" }
      val unit = item.firstUnit + random.nextInt(item.length)
      assertEquals(index, tree.indexCovering(unit)) { "unit $unit" }
      assertSame(item, tree.itemCovering(unit)) { "unit $unit" }
    }
    // The first applied item at or after each index, from the end backwards.
    var nextApplied: Item? = null
    for (index in expected.size downTo 0) {
      if (index < expected.size && expected[index].appliedInPrepare) {
        nextApplied = expected[index]
      }
      assertSame(nextApplied, tree.firstAppliedFrom(index)) { "first applied item from $index" }
    }
    var index = 0
    var prepare = 0
    var effect = 0
    for (pos in 0 until tree.prepareWidth()) {
      while (prepare + expected[index].prepareWidth <= pos) {
        prepare += expected[index].prepareWidth
        effect += expected[index].effectWidth
        index++
      }
      val cursor = tree.cursorBefore(pos)
      assertEquals(index, cursor.itemIndex) { "prepare position $pos" }
      assertEquals(prepare, cursor.preparePos) { "prepare position $pos" }
      assertEquals(effect, cursor.effectPos) { "prepare position $pos" }
      // The lookup caches the leaf it found, and the walker reads the item right after it.
      assertSame(expected[index], tree.get(index)) { "prepare position $pos" }
    }
  }

  private fun item(firstUnit: LV, length: Int): Item {
    return Item(
      firstUnit = firstUnit,
      length = length,
      originLeft = NO_UNIT,
      rightParent = NO_UNIT,
    )
  }

  private companion object {
    /**
     * Enough items for three inner levels: a level holds up to [ItemTree.WIDTH] members, and a
     * split leaves each half about half full.
     */
    const val ITEMS = 40_000

    /**
     * The steps between two full compares. A full compare scans every item and every position.
     */
    const val CHECK_EVERY = 4_000
  }
}

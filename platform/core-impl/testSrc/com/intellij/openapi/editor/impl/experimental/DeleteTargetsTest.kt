// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Tests [DeleteTargets]: the lookup by delete unit, the merge of pieces, and the order checks. */
internal class DeleteTargetsTest {

  @Test
  fun `every delete unit finds the unit it deleted across many pieces`() {
    val targets = DeleteTargets()
    // Pieces with gaps in the delete space and targets that jump around, past the first growth.
    val pieces = (0 until PIECES).map { i -> Triple(10 + 5 * i, 1_000 - 7 * i, 1 + i % 3) }
    for ((deleteStart, targetStart, length) in pieces) {
      targets.add(deleteStart, targetStart, length)
    }
    assertEquals(PIECES, targets.size())
    for ((deleteStart, targetStart, length) in pieces) {
      for (unit in 0 until length) {
        assertEquals(targetStart + unit, targets.targetOf(deleteStart + unit)) {
          "the delete unit ${deleteStart + unit}"
        }
      }
      // The units between two pieces were never applied.
      assertThrows(IllegalArgumentException::class.java) { targets.targetOf(deleteStart + length) }
    }
    assertThrows(IllegalArgumentException::class.java) { targets.targetOf(9) }
  }

  @Test
  fun `a piece that continues in both spaces extends the one before it`() {
    val targets = DeleteTargets()
    targets.add(5, 20, 2)
    targets.add(7, 22, 3)
    assertEquals(1, targets.size())
    assertEquals(24, targets.targetOf(9))
    // A walk moves the whole piece as one batch.
    assertEquals(5, targets.pieceStartOf(9))
    assertEquals(10, targets.pieceEndOf(5))
  }

  @Test
  fun `a piece bound names the piece of the unit`() {
    val targets = DeleteTargets()
    targets.add(5, 20, 2)
    targets.add(7, 40, 3)
    assertEquals(5, targets.pieceStartOf(6))
    assertEquals(7, targets.pieceEndOf(6))
    assertEquals(7, targets.pieceStartOf(7))
    assertEquals(10, targets.pieceEndOf(9))
    assertThrows(IllegalArgumentException::class.java) { targets.pieceStartOf(10) }
    assertThrows(IllegalArgumentException::class.java) { targets.pieceEndOf(4) }
  }

  @Test
  fun `a piece that continues in one space only stays apart`() {
    val deleteOnly = DeleteTargets()
    deleteOnly.add(5, 20, 2)
    deleteOnly.add(7, 40, 1)
    assertEquals(2, deleteOnly.size())
    assertEquals(21, deleteOnly.targetOf(6))
    assertEquals(40, deleteOnly.targetOf(7))

    val targetOnly = DeleteTargets()
    targetOnly.add(5, 20, 2)
    targetOnly.add(9, 22, 1)
    assertEquals(2, targetOnly.size())
    assertEquals(22, targetOnly.targetOf(9))
    assertThrows(IllegalArgumentException::class.java) { targetOnly.targetOf(7) }
  }

  @Test
  fun `a piece of placeholder units never joins a real piece`() {
    // The last placeholder id is -2, and -1 is the free id between the two spaces.
    val targets = DeleteTargets()
    targets.add(5, -3, 2)
    targets.add(7, 0, 1)
    assertEquals(2, targets.size())
    assertEquals(-2, targets.targetOf(6))
    assertEquals(0, targets.targetOf(7))
  }

  @Test
  fun `an empty table finds nothing`() {
    assertThrows(IllegalArgumentException::class.java) { DeleteTargets().targetOf(0) }
  }

  @Test
  fun `a piece out of order or empty is rejected`() {
    val targets = DeleteTargets()
    targets.add(5, 20, 3)
    assertThrows(IllegalArgumentException::class.java) { targets.add(7, 30, 1) }
    assertThrows(IllegalArgumentException::class.java) { targets.add(2, 30, 1) }
    assertThrows(IllegalArgumentException::class.java) { targets.add(8, 30, 0) }
    assertEquals(1, targets.size())
    assertEquals(22, targets.targetOf(7))
  }

  private companion object {
    /** More pieces than the initial capacity, so the arrays grow at least twice. */
    const val PIECES = 70
  }
}

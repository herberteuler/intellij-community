// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

/**
 * Where the walk of [ReplayWalker] is: the place in the item list, plus the matching position
 * in each of the two versions. The positions are the summed widths of the items before
 * [itemIndex], so all three move together. [moveTo] is the one exception: the Fugue scan crosses
 * only items with no prepare width, so it leaves [preparePos] as it is.
 *
 * [itemIndex] names its list on purpose, because an index into the walk ranges means something
 * else.
 */
internal class Cursor(
  itemIndex: Int,
  preparePos: Int,
  effectPos: Int,
) {
  var itemIndex: Int = itemIndex
    private set
  var preparePos: Int = preparePos
    private set
  var effectPos: Int = effectPos
    private set

  fun advanceOver(item: Item) {
    itemIndex++
    preparePos += item.prepareWidth
    effectPos += item.effectWidth
  }

  fun retreatOver(item: Item) {
    itemIndex--
    preparePos -= item.prepareWidth
    effectPos -= item.effectWidth
  }

  /** Jumps to the place that the Fugue scan chose. The scan crosses no prepare width. */
  fun moveTo(itemIndex: Int, effectPos: Int) {
    this.itemIndex = itemIndex
    this.effectPos = effectPos
  }

  override fun toString(): String {
    return "Cursor(itemIndex=$itemIndex, preparePos=$preparePos, effectPos=$effectPos)"
  }
}

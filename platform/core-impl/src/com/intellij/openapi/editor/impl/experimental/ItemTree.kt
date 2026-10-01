// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import org.jetbrains.annotations.TestOnly
import java.util.TreeMap

/**
 * The items of one [ReplayWalker] walk in document order, as an order-statistic B+tree. Every
 * node keeps three sums of its subtree: the items, the prepare width and the effect width. So a
 * lookup by index, by prepare position or by unit costs O(log n), and so does an insert or a split.
 *
 * The tree only grows. An insert adds an item, a split adds the right piece, and nothing removes
 * an item. So no node ever merges, and every leaf sits at the same depth.
 *
 * The sums stay true only when an item changes through this tree: its state through [advance],
 * [retreat] and [deleteHere], and its length through [splitAt]. So the walker must not call the
 * transitions of [Item] itself.
 *
 * Thread safety: none, and none is needed. A tree lives inside one walk, which runs on one thread.
 */
internal class ItemTree {
  /**
   * Every leaf, by its number. An item keeps the number of its leaf, see [Item.leafNumber].
   */
  private val leaves = ArrayList<Leaf>()

  private var root: Node = newLeaf()

  /**
   * Every item, by the lv of its first unit. An item covers a range of units, and two ranges never
   * overlap. So the floor entry of a unit names the only item that can cover it.
   */
  private val itemsByUnit = TreeMap<LV, Item>()

  /**
   * The leaf that the last lookup found, by index or by prepare position, and the index of its
   * first item. A walk reads the items around one place, so the next lookup usually lands in the
   * same leaf. An insert or a split always goes through this cache, because it shifts every later
   * leaf, and the cache then names the leaf that holds the new item.
   */
  private var cachedLeaf: Leaf? = null
  private var cachedLeafStart = 0

  /**
   * The number of items.
   */
  fun size(): Int {
    return root.count
  }

  /**
   * The prepare width of the whole document.
   */
  fun prepareWidth(): Int {
    return root.prepareWidth
  }

  /**
   * The item at [index].
   */
  fun get(index: Int): Item {
    checkIndex(index)
    val leaf = leafFor(index, isInsert = false)
    return leaf.items[index - cachedLeafStart]
  }

  /**
   * Adds [item] at [index], so the items from [index] on move one place to the right. The item
   * must not be in a tree yet, and no item of this tree may start at its first unit. An add that
   * fails changes nothing.
   */
  fun add(index: Int, item: Item) {
    checkInsertIndex(index)
    checkUnfiled(item)
    fileByUnit(item)
    val leaf = leafFor(index, isInsert = true)
    val slot = index - cachedLeafStart
    leaf.items.add(slot, item)
    item.fileUnderLeaf(leaf.number)
    addToSums(leaf, 1, item.prepareWidth, item.effectWidth)
    splitIfFull(leaf, slot)
  }

  /**
   * Splits the item at [index] after [units] units, and files the right piece at `index + 1`.
   */
  fun splitAt(index: Int, units: Int) {
    checkIndex(index)
    val leaf = leafFor(index, isInsert = false)
    val slot = index - cachedLeafStart
    val right = leaf.items[slot].splitAfter(units)
    leaf.items.add(slot + 1, right)
    right.fileUnderLeaf(leaf.number)
    fileByUnit(right)
    // The two pieces share one state, so together they keep the widths of the item.
    addToSums(leaf, 1, 0, 0)
    splitIfFull(leaf, slot + 1)
  }

  /**
   * The item that covers [unit].
   */
  fun itemCovering(unit: LV): Item {
    val item = itemsByUnit.floorEntry(unit)?.value
    return requireCovering(item, unit)
  }

  /**
   * The index of the item that covers [unit].
   */
  fun indexCovering(unit: LV): Int {
    val item = itemCovering(unit)
    val leaf = leafOf(item)
    return startOf(leaf) + leaf.items.indexOfFirst { it === item }
  }

  /**
   * The cursor just before the item that covers the prepare position [preparePos]. That item is
   * the first one whose prepare width reaches past [preparePos], so the cursor passes every item
   * of no prepare width before it.
   */
  fun cursorBefore(preparePos: Int): Cursor {
    checkPreparePos(preparePos)
    var node = root
    var index = 0
    var prepare = 0
    var effect = 0
    while (true) {
      when (val current = node) {
        is Inner -> {
          var i = 0
          while (prepare + current.children[i].prepareWidth <= preparePos) {
            val child = current.children[i]
            index += child.count
            prepare += child.prepareWidth
            effect += child.effectWidth
            i++
          }
          node = current.children[i]
        }
        is Leaf -> {
          cacheLeaf(current, index)
          var slot = 0
          while (prepare + current.items[slot].prepareWidth <= preparePos) {
            val item = current.items[slot]
            prepare += item.prepareWidth
            effect += item.effectWidth
            slot++
          }
          return Cursor(index + slot, prepare, effect)
        }
      }
    }
  }

  /**
   * Applies one op to the prepare version of [item] again. See [Item.advance].
   */
  fun advance(item: Item, isDelete: Boolean) {
    changeState(item) {
      item.advance(isDelete)
    }
  }

  /**
   * Takes one op of the prepare version of [item] back. See [Item.retreat].
   */
  fun retreat(item: Item, isDelete: Boolean) {
    changeState(item) {
      item.retreat(isDelete)
    }
  }

  /**
   * Removes [item] from both versions. See [Item.deleteHere].
   */
  fun deleteHere(item: Item) {
    changeState(item) {
      item.deleteHere()
    }
  }

  /**
   * Runs the state [change] of [item], and moves the change of its widths into the sums.
   */
  private inline fun changeState(item: Item, change: () -> Unit) {
    val prepare = item.prepareWidth
    val effect = item.effectWidth
    change()
    val prepareDelta = item.prepareWidth - prepare
    val effectDelta = item.effectWidth - effect
    if (prepareDelta != 0 || effectDelta != 0) {
      addToSums(leafOf(item), 0, prepareDelta, effectDelta)
    }
  }

  /**
   * The leaf for [index], from the cache when the cached leaf holds it. An insert can also take the
   * end of the cached leaf, so a run of inserts at the end of one leaf needs no descent.
   */
  private fun leafFor(index: Int, isInsert: Boolean): Leaf {
    val cached = cachedLeaf
    if (cached != null) {
      val slot = index - cachedLeafStart
      val end = if (isInsert) {
        cached.items.size
      } else {
        cached.items.size - 1
      }
      if (slot in 0..end) {
        return cached
      }
    }
    return leafAt(index)
  }

  /**
   * The leaf that holds [index], or the leaf that an insert at [index] goes to. It becomes the
   * cached leaf. An index at the end of a child goes to the next child, and the end of the tree
   * goes to the last leaf.
   */
  private fun leafAt(index: Int): Leaf {
    var node = root
    var start = 0
    while (true) {
      when (val current = node) {
        is Leaf -> {
          cacheLeaf(current, start)
          return current
        }
        is Inner -> {
          val children = current.children
          val last = children.size - 1
          val lastStart = start + current.count - children[last].count
          if (index >= lastStart) {
            // A walk that appends lands here, and it needs no scan of the children.
            start = lastStart
            node = children[last]
          } else {
            var i = 0
            while (index - start >= children[i].count) {
              start += children[i].count
              i++
            }
            node = children[i]
          }
        }
      }
    }
  }

  private fun cacheLeaf(leaf: Leaf, start: Int) {
    cachedLeaf = leaf
    cachedLeafStart = start
  }

  /**
   * The index of the first item of [leaf]: the items of every subtree to its left.
   */
  private fun startOf(leaf: Leaf): Int {
    var start = 0
    var node: Node = leaf
    var parent = node.parent()
    while (parent != null) {
      for (child in parent.children) {
        if (child === node) {
          break
        }
        start += child.count
      }
      node = parent
      parent = node.parent()
    }
    return start
  }

  /**
   * Adds the three deltas to the sums of [from] and of every node above it.
   */
  private fun addToSums(
    from: Node,
    count: Int,
    prepare: Int,
    effect: Int,
  ) {
    var node: Node? = from
    while (node != null) {
      node.addToSums(count, prepare, effect)
      node = node.parent()
    }
  }

  /**
   * Moves the right part of a full [leaf], the cached leaf, to a new leaf after it. [slot] is where
   * the last insert went. An insert at the end of the leaf moves only itself, so a run of inserts
   * there fills the new leaf and moves no older item. Any other insert moves the right half. The
   * cache follows [slot], so the next insert after it needs no descent.
   */
  private fun splitIfFull(leaf: Leaf, slot: Int) {
    val items = leaf.items
    if (items.size <= WIDTH) {
      return
    }
    val from = if (slot == items.size - 1) {
      slot
    } else {
      items.size / 2
    }
    val right = newLeaf()
    val moved = items.subList(from, items.size)
    right.items.addAll(moved)
    moved.clear()
    for (item in right.items) {
      item.fileUnderLeaf(right.number)
    }
    leaf.recount()
    right.recount()
    if (slot >= from) {
      cacheLeaf(right, cachedLeafStart + from)
    }
    insertAfter(leaf, right)
  }

  /**
   * Moves the right half of a full [inner] node to a new node after it.
   */
  private fun splitIfFull(inner: Inner) {
    if (inner.children.size <= WIDTH) {
      return
    }
    val right = Inner()
    val moved = inner.children.subList(inner.children.size / 2, inner.children.size)
    for (child in moved) {
      right.adopt(child)
    }
    moved.clear()
    inner.recount()
    right.recount()
    insertAfter(inner, right)
  }

  /**
   * Files [sibling] right after [node] in the parent of [node]. The parent keeps its sums, because
   * the two hold what [node] held before. A root that splits gets a new root above it.
   */
  private fun insertAfter(node: Node, sibling: Node) {
    val parent = node.parent()
    if (parent == null) {
      val newRoot = Inner()
      newRoot.adopt(node)
      newRoot.adopt(sibling)
      newRoot.recount()
      root = newRoot
      return
    }
    parent.adopt(parent.children.indexOf(node) + 1, sibling)
    splitIfFull(parent)
  }

  /**
   * The leaf of [item], which must be in this tree.
   */
  private fun leafOf(item: Item): Leaf {
    val number = item.leafNumber()
    checkLeafNumber(number, item)
    return leaves[number]
  }

  private fun newLeaf(): Leaf {
    val leaf = Leaf(leaves.size)
    leaves.add(leaf)
    return leaf
  }

  /**
   * Files [item] in the unit index, unless another item starts at its first unit. That item stays,
   * and the index changes nothing.
   */
  private fun fileByUnit(item: Item) {
    val present = itemsByUnit.putIfAbsent(item.firstUnit, item)
    checkFirstUnitFree(present, item)
  }

  private fun requireCovering(item: Item?, unit: LV): Item {
    require(item != null && item.contains(unit)) {
      "No item covers the unit $unit"
    }
    return item
  }

  private fun checkUnfiled(item: Item) {
    require(!item.isFiled()) {
      "The item $item is in a tree already"
    }
  }

  private fun checkFirstUnitFree(present: Item?, item: Item) {
    require(present == null) {
      "The items $present and $item start at one unit"
    }
  }

  private fun checkLeafNumber(number: Int, item: Item) {
    require(number in leaves.indices) {
      "The item $item is not in the tree"
    }
  }

  private fun checkIndex(index: Int) {
    require(index in 0 until size()) {
      "The index $index is outside the ${size()} items"
    }
  }

  private fun checkInsertIndex(index: Int) {
    require(index in 0..size()) {
      "The insert index $index is outside the ${size()} items"
    }
  }

  private fun checkPreparePos(preparePos: Int) {
    require(preparePos in 0 until prepareWidth()) {
      "The prepare position $preparePos is outside the document of width ${prepareWidth()}"
    }
  }

  /**
   * Fails when any invariant of the tree does not hold:
   * - every node knows its parent and holds at most [WIDTH] members;
   * - an inner node holds two members or more;
   * - only the empty tree has an empty leaf;
   * - the three sums of a node are the sums of its members;
   * - every leaf sits at the same depth, and the tree reaches every leaf it numbered;
   * - every item knows the number of its leaf;
   * - the unit index holds every item, and only the items;
   * - the cached leaf starts at the cached index.
   */
  @TestOnly
  fun checkInvariants() {
    val reached = ArrayList<Leaf>()
    checkNode(root, null, 0, reached)
    require(reached.size == leaves.size) {
      "The tree reaches ${reached.size} of its ${leaves.size} leaves"
    }
    var items = 0
    for (leaf in reached) {
      require(leaves[leaf.number] === leaf) {
        "The leaf ${leaf.number} is filed under another number"
      }
      for (item in leaf.items) {
        require(item.leafNumber() == leaf.number) {
          "The item $item does not know its leaf"
        }
        require(itemsByUnit[item.firstUnit] === item) {
          "The unit index does not hold $item"
        }
        items++
      }
    }
    require(itemsByUnit.size == items) {
      "The unit index holds ${itemsByUnit.size} items, but the tree holds $items"
    }
    val cached = cachedLeaf
    require(cached == null || startOf(cached) == cachedLeafStart) {
      "The cached leaf does not start at the cached index $cachedLeafStart"
    }
  }

  /**
   * The inner levels above the leaves. 0 means the root is a leaf.
   */
  @TestOnly
  fun depth(): Int {
    return depthOf(leaves[0])
  }

  private fun checkNode(
    node: Node,
    parent: Inner?,
    depth: Int,
    reached: ArrayList<Leaf>,
  ) {
    require(node.parent() === parent) {
      "A node at the depth $depth does not know its parent"
    }
    when (node) {
      is Leaf -> {
        require(node.items.size <= WIDTH) {
          "A leaf holds ${node.items.size} items"
        }
        require(node.items.isNotEmpty() || node === root) {
          "A leaf other than the root is empty"
        }
        require(reached.isEmpty() || depthOf(reached[0]) == depth) {
          "The leaves do not all sit at one depth"
        }
        reached.add(node)
        val prepare = node.items.sumOf { it.prepareWidth }
        val effect = node.items.sumOf { it.effectWidth }
        checkSums(node, node.items.size, prepare, effect)
      }
      is Inner -> {
        require(node.children.size in 2..WIDTH) {
          "An inner node holds ${node.children.size} children"
        }
        for (child in node.children) {
          checkNode(child, node, depth + 1, reached)
        }
        val count = node.children.sumOf { it.count }
        val prepare = node.children.sumOf { it.prepareWidth }
        val effect = node.children.sumOf { it.effectWidth }
        checkSums(node, count, prepare, effect)
      }
    }
  }

  private fun checkSums(
    node: Node,
    count: Int,
    prepare: Int,
    effect: Int,
  ) {
    require(node.count == count) {
      "A node keeps the count ${node.count}, but its members hold $count items"
    }
    require(node.prepareWidth == prepare) {
      "A node keeps the prepare width ${node.prepareWidth}, but its members hold $prepare"
    }
    require(node.effectWidth == effect) {
      "A node keeps the effect width ${node.effectWidth}, but its members hold $effect"
    }
  }

  private fun depthOf(node: Node): Int {
    var depth = 0
    var parent = node.parent()
    while (parent != null) {
      depth++
      parent = parent.parent()
    }
    return depth
  }

  override fun toString(): String {
    return "ItemTree(items=${size()}, prepareWidth=${prepareWidth()}, depth=${depthOf(leaves[0])})"
  }

  /**
   * A node of the tree, with the three sums of its subtree.
   */
  private sealed class Node {
    private var parent: Inner? = null

    var count: Int = 0
      private set
    var prepareWidth: Int = 0
      private set
    var effectWidth: Int = 0
      private set

    fun parent(): Inner? {
      return parent
    }

    fun moveUnder(newParent: Inner) {
      parent = newParent
    }

    fun addToSums(count: Int, prepare: Int, effect: Int) {
      this.count += count
      prepareWidth += prepare
      effectWidth += effect
    }

    /**
     * Sets the three sums from the direct members, after a split moved some of them away.
     */
    abstract fun recount()

    protected fun setSums(count: Int, prepare: Int, effect: Int) {
      this.count = count
      prepareWidth = prepare
      effectWidth = effect
    }
  }

  /**
   * A leaf, with the [number] that its items keep.
   */
  private class Leaf(val number: Int) : Node() {
    val items = ArrayList<Item>(WIDTH + 1)

    override fun recount() {
      var prepare = 0
      var effect = 0
      for (item in items) {
        prepare += item.prepareWidth
        effect += item.effectWidth
      }
      setSums(items.size, prepare, effect)
    }
  }

  private class Inner : Node() {
    val children = ArrayList<Node>(WIDTH + 1)

    fun adopt(child: Node) {
      adopt(children.size, child)
    }

    fun adopt(index: Int, child: Node) {
      children.add(index, child)
      child.moveUnder(this)
    }

    override fun recount() {
      var count = 0
      var prepare = 0
      var effect = 0
      for (child in children) {
        count += child.count
        prepare += child.prepareWidth
        effect += child.effectWidth
      }
      setSums(count, prepare, effect)
    }
  }

  companion object {
    /**
     * The most members of one node.
     */
    const val WIDTH: Int = 32
  }
}

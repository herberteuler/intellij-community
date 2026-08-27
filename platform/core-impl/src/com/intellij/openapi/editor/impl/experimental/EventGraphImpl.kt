// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Agent
import com.intellij.openapi.editor.experimental.DocText
import com.intellij.openapi.editor.experimental.Event
import com.intellij.openapi.editor.experimental.EventGraph
import com.intellij.openapi.editor.experimental.Version
import com.intellij.openapi.editor.impl.DocTextImpl
import java.util.Arrays
import java.util.BitSet
import java.util.Collections
import java.util.PriorityQueue

/**
 * The event id: the (agent, seq) pair. One id names one event forever.
 */
internal data class EventId(val agent: Agent, val seq: Int)

/**
 * One stored event: the public value plus its parents as internal indexes (LVs).
 * The parents array is sorted and transitively reduced.
 */
internal class StoredEvent(val event: Event, val parents: IntArray)

/**
 * The shared append-only storage behind [EventGraphImpl] values.
 *
 * Successive graphs of one linear chain share one store; each graph sees the prefix
 * `[0, size)`. When a graph that is not the tip appends, the store copies that prefix
 * into a new store, so the old chain stays untouched.
 *
 * Thread safety: appends and id lookups synchronize on this store. Reads of committed
 * slots do not synchronize. That is safe because a slot is written before the graph
 * that covers it is constructed, and a graph reaches another thread only through a
 * safe publication of that graph value (the same reasoning as in [DocTextImpl]).
 */
internal class EventStore private constructor(
  @Volatile private var slots: Array<StoredEvent?>,
  private var committed: Int,
  private val idToLv: HashMap<EventId, Int>,
) {

  fun eventAt(lv: Int): StoredEvent {
    val stored = slots[lv]
    checkCommittedSlot(stored, lv)
    return stored!!
  }

  /** The lv of [id] when it is below [limit], or -1. [limit] is the calling graph's size. */
  fun lvOf(id: EventId, limit: Int): Int {
    val lv = synchronized(this) { idToLv[id] } ?: return -1
    return if (lv < limit) lv else -1
  }

  /**
   * Appends [stored] after the prefix `[0, expectedSize)` and returns the store that
   * holds the result: this store when it is the tip, or a fresh prefix copy otherwise.
   */
  fun appendAt(expectedSize: Int, stored: StoredEvent, id: EventId): EventStore {
    if (tryAppend(expectedSize, stored, id)) {
      return this
    }
    val copy = copyPrefix(expectedSize)
    val appended = copy.tryAppend(expectedSize, stored, id)
    checkDivergedAppend(appended)
    return copy
  }

  private fun tryAppend(expectedSize: Int, stored: StoredEvent, id: EventId): Boolean {
    synchronized(this) {
      if (committed != expectedSize) {
        return false
      }
      var slots = this.slots
      if (committed == slots.size) {
        slots = slots.copyOf(maxOf(INITIAL_CAPACITY, slots.size * 2))
      }
      slots[committed] = stored
      this.slots = slots // volatile write publishes the resized array and its elements
      committed++
      idToLv[id] = committed - 1
      return true
    }
  }

  private fun copyPrefix(size: Int): EventStore {
    val slots = this.slots // one volatile read; the prefix slots are immutable once written
    val newSlots = arrayOfNulls<StoredEvent>(maxOf(INITIAL_CAPACITY, size * 2))
    System.arraycopy(slots, 0, newSlots, 0, size)
    val newIdToLv = HashMap<EventId, Int>(size * 2)
    for (lv in 0 until size) {
      val event = newSlots[lv]!!.event
      newIdToLv[EventId(event.agent(), event.seq())] = lv
    }
    return EventStore(newSlots, size, newIdToLv)
  }

  private fun checkCommittedSlot(stored: StoredEvent?, lv: Int) {
    require(stored != null) {
      "The slot $lv is not committed"
    }
  }

  private fun checkDivergedAppend(appended: Boolean) {
    require(appended) {
      "An append into a fresh prefix copy failed"
    }
  }

  companion object {
    private const val INITIAL_CAPACITY = 16

    fun empty(): EventStore {
      return EventStore(arrayOfNulls(INITIAL_CAPACITY), 0, HashMap())
    }
  }
}

/**
 * See [EventGraph]. An immutable view over an [EventStore] prefix.
 */
internal class EventGraphImpl private constructor(
  private val store: EventStore,
  private val size: Int,
  private val version: VersionImpl,
) : EventGraph {

  override fun size(): Int {
    return size
  }

  override fun version(): Version {
    return version
  }

  fun versionImpl(): VersionImpl {
    return version
  }

  override fun append(event: Event, parents: Version): EventGraph {
    return appendImpl(event, VersionImpl.implOf(parents))
  }

  fun appendImpl(event: Event, parents: VersionImpl): EventGraphImpl {
    checkVersionOfThisGraph(parents)
    val id = EventId(event.agent(), event.seq())
    checkNewId(id)
    val stored = StoredEvent(event, parents.lvs)
    val newStore = store.appendAt(size, stored, id)
    val newVersion = VersionImpl(advanceFrontier(version.lvs, parents.lvs, size))
    return EventGraphImpl(newStore, size + 1, newVersion)
  }

  /** Appends [event] at this graph's own frontier. */
  fun appendAtTip(event: Event): EventGraphImpl {
    return appendImpl(event, version)
  }

  override fun mergeFrom(other: EventGraph): EventGraph {
    return mergeFromImpl(implOf(other)).graph
  }

  /**
   * The union of the two graphs, joined by event ids, plus the version of [other]
   * re-expressed in the result graph's indexes.
   */
  fun mergeFromImpl(other: EventGraphImpl): MergeResult {
    val lvMap = IntArray(other.size)
    var graph = this
    for (lv in 0 until other.size) {
      val stored = other.store.eventAt(lv)
      val event = stored.event
      val known = graph.store.lvOf(EventId(event.agent(), event.seq()), graph.size)
      if (known >= 0) {
        lvMap[lv] = known
        continue
      }
      // A parent always precedes its child, so every parent is mapped already.
      val parents = IntArray(stored.parents.size) { i -> lvMap[stored.parents[i]] }
      parents.sort()
      graph = graph.appendImpl(event, VersionImpl(parents))
      lvMap[lv] = graph.size - 1
    }
    val remapped = IntArray(other.version.lvs.size) { i -> lvMap[other.version.lvs[i]] }
    remapped.sort()
    return MergeResult(graph, VersionImpl(remapped))
  }

  override fun replay(version: Version): DocText {
    val versionImpl = VersionImpl.implOf(version)
    checkVersionOfThisGraph(versionImpl)
    val text = StringBuilder()
    EgWalkerReplay.replay(this, versionImpl, StringBuilderSink(text))
    return DocText.createText(text)
  }

  // ------------------------------------------------------------------- queries for the replay

  fun eventOf(lv: Int): Event {
    checkLv(lv)
    return store.eventAt(lv).event
  }

  fun parentsOf(lv: Int): IntArray {
    checkLv(lv)
    return store.eventAt(lv).parents
  }

  /**
   * The tie-break order for concurrent insertions: by agent, then by seq.
   * The reference implementation calls this `lvCmp`.
   */
  fun compareEvents(lvA: Int, lvB: Int): Int {
    val a = eventOf(lvA)
    val b = eventOf(lvB)
    val byAgent = a.agent().compareTo(b.agent())
    if (byAgent != 0) {
      return byAgent
    }
    return a.seq().compareTo(b.seq())
  }

  /** The paper's `Events(V)`: [version] and all its ancestors, as a set of lvs. */
  fun eventsOf(version: VersionImpl): BitSet {
    val seen = BitSet(size)
    val stack = ArrayDeque<Int>()
    for (lv in version.lvs) {
      stack.addLast(lv)
    }
    while (stack.isNotEmpty()) {
      val lv = stack.removeLast()
      if (seen.get(lv)) {
        continue
      }
      seen.set(lv)
      for (parent in parentsOf(lv)) {
        if (!seen.get(parent)) {
          stack.addLast(parent)
        }
      }
    }
    return seen
  }

  /**
   * The events only in the history of [a] and only in the history of [b].
   * Both results are ascending. A per-event port of `diff` from the reference
   * implementation's causal-graph library, without the run-length encoding.
   */
  fun diff(a: IntArray, b: IntArray): Diff {
    val flags = HashMap<Int, Int>()
    val queue = PriorityQueue<Int>(11, Collections.reverseOrder())
    var numShared = 0

    fun enqueue(lv: Int, flag: Int) {
      val current = flags[lv]
      if (current == null) {
        queue.add(lv)
        flags[lv] = flag
        if (flag == FLAG_SHARED) {
          numShared++
        }
      }
      else if (flag != current && current != FLAG_SHARED) {
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

    val aOnly = ArrayList<Int>()
    val bOnly = ArrayList<Int>()
    while (queue.size > numShared) {
      val lv = queue.poll()
      val flag = flags[lv]!!
      when (flag) {
        FLAG_SHARED -> numShared--
        FLAG_A -> aOnly.add(lv)
        else -> bOnly.add(lv)
      }
      for (parent in parentsOf(lv)) {
        enqueue(parent, flag)
      }
    }

    // The queue pops in descending order; the results must ascend.
    aOnly.reverse()
    bOnly.reverse()
    return Diff(aOnly.toIntArray(), bOnly.toIntArray())
  }

  // ------------------------------------------------------------------------------------ checks

  private fun checkVersionOfThisGraph(version: VersionImpl) {
    val lvs = version.lvs
    require(lvs.isEmpty() || lvs[lvs.size - 1] < size) {
      "The version $version does not belong to a graph of size $size"
    }
  }

  private fun checkNewId(id: EventId) {
    require(store.lvOf(id, size) < 0) {
      "The event id $id is already in the graph"
    }
  }

  private fun checkLv(lv: Int) {
    require(lv in 0 until size) {
      "The lv $lv is out of the graph of size $size"
    }
  }

  private class StringBuilderSink(private val text: StringBuilder) : EgWalkerReplay.Sink {
    override fun insert(pos: Int, character: Char) {
      text.insert(pos, character)
    }

    override fun delete(pos: Int) {
      text.deleteCharAt(pos)
    }
  }

  internal class MergeResult(val graph: EventGraphImpl, val remappedOtherVersion: VersionImpl)

  internal class Diff(val aOnly: IntArray, val bOnly: IntArray)

  companion object {
    private const val FLAG_A = 0
    private const val FLAG_B = 1
    private const val FLAG_SHARED = 2

    fun empty(): EventGraphImpl {
      return EventGraphImpl(EventStore.empty(), 0, VersionImpl.ROOT)
    }

    fun implOf(graph: EventGraph): EventGraphImpl {
      require(graph is EventGraphImpl) {
        "Foreign EventGraph implementation: ${graph.javaClass.name}"
      }
      return graph
    }

    /** The new frontier after an append: `(frontier - parents) + newLv`. */
    private fun advanceFrontier(frontier: IntArray, parents: IntArray, newLv: Int): IntArray {
      var kept = 0
      for (lv in frontier) {
        if (Arrays.binarySearch(parents, lv) < 0) {
          kept++
        }
      }
      val result = IntArray(kept + 1)
      var i = 0
      for (lv in frontier) {
        if (Arrays.binarySearch(parents, lv) < 0) {
          result[i] = lv
          i++
        }
      }
      result[i] = newLv // newLv is greater than every existing lv, so the order holds
      return result
    }
  }
}

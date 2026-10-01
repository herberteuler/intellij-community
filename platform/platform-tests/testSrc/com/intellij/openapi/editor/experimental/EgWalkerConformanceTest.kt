// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.BitSet

/**
 * Replays editing histories whose final texts come from the reference implementation, and checks
 * that the port reaches the same texts. Only this test holds the port to an implementation other
 * than itself.
 *
 * A history takes one path or two:
 * - the full replay of its event graph;
 * - a rebuild through branches: each transaction starts from the branch at its parents, which is a
 *   merge of the branches at those units, and applies its ops as local edits. Every merge of two
 *   branches that went apart is a partial replay, so this path checks the partial replay too. It
 *   needs a history that keeps the agent contract of [DocBranch].
 *
 * A history is a list of transactions in the export format of diamond types. A transaction names
 * its agent, its first seq, its parents as lvs, and a list of ops. Each op after the first has the
 * op before it as its only parent. The lvs match the lvs of the port, because the graph appends the
 * transactions in their order.
 *
 * The histories in the repository come from `conformanceHistories.ts` next to them: random
 * histories, with the final texts from the reference. The other data sets come with the checkout of
 * the reference under `Resources/eg-walker`, and their tests are skipped without it.
 */
class EgWalkerConformanceTest {

  @Test
  fun `every history in the repository reaches the text of the reference`() {
    val histories = readHistories(testDataFile())
    assertEquals(REPOSITORY_HISTORIES, histories.size)
    for ((index, history) in histories.withIndex()) {
      val where = { "history $index" }
      checkFullReplay(history, where)
      checkBranches(history, stepChecks = true, where)
    }
  }

  /**
   * The fuzzer of diamond types lets one agent edit concurrently with itself, which the agent
   * contract of [DocBranch] forbids. So these histories take the full replay only.
   */
  @Test
  fun `every conformance history of the reference reaches its text`() {
    val file = referenceFile("conformance.json")
    assumeTrue(Files.exists(file)) { "No conformance data at $file" }
    val histories = readHistories(file)
    assertEquals(REFERENCE_HISTORIES, histories.size)
    for ((index, history) in histories.withIndex()) {
      checkFullReplay(history) { "history $index" }
    }
  }

  /**
   * Two agents that keep the agent contract, so the trace takes both paths.
   */
  @Test
  fun `the ff trace of the reference reaches its final text`() {
    val file = referenceFile("ff-raw.json")
    assumeTrue(Files.exists(file)) { "No editing trace at $file" }
    val history = readHistory(file)
    checkFullReplay(history) { "ff" }
    checkBranches(history, stepChecks = false) { "ff" }
  }

  /**
   * 194 agents and 947,337 units. Some agents edit concurrently with themselves, so the trace takes
   * the full replay only.
   *
   * The trace `git-makefile-raw.json` is left out on purpose. Its seqs do not follow the lvs within
   * one agent, which the graph rejects, and a renumbering changes the text that the reference reaches.
   */
  @Test
  fun `the node_nodecc trace of the reference reaches its final text`() {
    val file = referenceFile("node_nodecc-raw.json")
    assumeTrue(Files.exists(file)) { "No editing trace at $file" }
    checkFullReplay(readHistory(file)) { "node_nodecc" }
  }

  private fun checkFullReplay(history: History, where: () -> String) {
    assertEquals(history.endContent, graphOf(history).replay().string()) { "${where()}: the full replay" }
  }

  /**
   * Fails unless the rebuild through branches reaches the final text of [history]. With
   * [stepChecks], every branch that a transaction starts from must also hold the text of the full
   * replay at its parents, which names the first merge that goes wrong. That costs a replay per
   * transaction, so a long trace skips it.
   */
  private fun checkBranches(history: History, stepChecks: Boolean, where: () -> String) {
    val stepGraph = if (stepChecks) graphOf(history) else null
    assertEquals(history.endContent, textThroughBranches(history, stepGraph, where)) { "${where()}: the branches" }
  }

  private fun graphOf(history: History): EventGraph {
    var graph = EventGraph.createGraph()
    for (txn in history.txns) {
      assertEquals(txn.start, graph.size()) { "the first lv of $txn" }
      var seq = txn.seqStart
      var parents = versionOf(txn.parents)
      for (op in txn.ops) {
        val event = if (op.deleted > 0) {
          Event.createDelete(txn.agent, seq, op.offset, op.deleted)
        } else {
          Event.createInsert(txn.agent, seq, op.offset, op.inserted)
        }
        graph = graph.append(event, parents)
        seq += event.length()
        parents = Version.of(graph.size() - 1)
      }
      assertEquals(txn.end, graph.size()) { "the lv after $txn" }
    }
    return graph
  }

  /**
   * The text that [history] reaches through branches. With [stepGraph], each branch that a
   * transaction starts from must hold the text of [stepGraph] at the parents.
   *
   * A branch mints the next seq of its agent, so the rebuild gives the ids of the history only when
   * each transaction of an agent descends from the one before it. That is the agent contract of
   * [DocBranch], and [checkAgentContract] makes sure the data keeps it.
   *
   * The branch at a unit exists only after the op that holds it, so an op is cut at every unit that
   * a later transaction names as a parent.
   */
  private fun textThroughBranches(
    history: History,
    stepGraph: EventGraph?,
    where: () -> String,
  ): String {
    checkAgentContract(history, where)
    val heads = headsOf(history)
    val needed = BitSet()
    for (txn in history.txns) {
      for (parent in txn.parents) {
        needed.set(parent)
      }
    }
    for (head in heads) {
      needed.set(head)
    }
    val branchAt = HashMap<Int, DocBranch>()
    val root = DocBranch.createBranch("", agent("root"))
    for (txn in history.txns) {
      var branch = branchAt(txn.parents, root.fork(txn.agent), branchAt)
      if (stepGraph != null) {
        assertEquals(stepGraph.replay(versionOf(txn.parents)).string(), branch.string()) {
          "${where()}: the branch before $txn"
        }
      }
      var lv = txn.start
      for (op in txn.ops) {
        val length = op.length()
        var done = 0
        while (done < length) {
          // The piece ends at the next unit that a branch must exist at, or at the op end.
          val cut = needed.nextSetBit(lv + done)
          val pieceEnd = if (cut in 0 until lv + length) cut + 1 else lv + length
          val count = pieceEnd - (lv + done)
          branch = if (op.deleted > 0) {
            branch.applyOp(DocTextOp.deleteOp(op.offset, count))
          } else {
            branch.applyOp(DocTextOp.insertOp(op.offset + done, op.inserted.substring(done, done + count)))
          }
          done += count
          if (needed.get(pieceEnd - 1)) {
            branchAt[pieceEnd - 1] = branch
          }
        }
        lv += length
      }
    }
    return branchAt(heads, root, branchAt).string()
  }

  /**
   * The branch at [lvs]: [start] when they are the root, and the merge of their branches otherwise.
   */
  private fun branchAt(
    lvs: IntArray,
    start: DocBranch,
    branchAt: Map<Int, DocBranch>,
  ): DocBranch {
    if (lvs.isEmpty()) {
      return start
    }
    var branch = branchAt.getValue(lvs[0]).fork(start.agent())
    for (index in 1 until lvs.size) {
      branch = branch.merge(branchAt.getValue(lvs[index]))
    }
    return branch
  }

  /**
   * Fails unless every transaction of an agent descends from the transaction of that agent before it.
   */
  private fun checkAgentContract(history: History, where: () -> String) {
    val lastUnitOf = HashMap<Agent, Int>()
    for (txn in history.txns) {
      val last = lastUnitOf[txn.agent]
      if (last != null) {
        assertTrue(eventsOf(history, txn.parents).get(last)) {
          "${where()}: $txn does not descend from the transaction of its agent before it"
        }
      }
      lastUnitOf[txn.agent] = txn.end - 1
    }
  }

  /**
   * The units at or before [lvs]. The units of a transaction before a unit are its ancestors.
   */
  private fun eventsOf(history: History, lvs: IntArray): BitSet {
    val seen = BitSet()
    val stack = ArrayDeque<Int>()
    lvs.forEach { stack.addLast(it) }
    while (stack.isNotEmpty()) {
      val lv = stack.removeLast()
      if (seen.get(lv)) {
        continue
      }
      val txn = history.txnAt(lv)
      seen.set(txn.start, lv + 1)
      txn.parents.forEach { stack.addLast(it) }
    }
    return seen
  }

  /**
   * The units that no transaction names as a parent and that end their transaction, ascending.
   */
  private fun headsOf(history: History): IntArray {
    val named = BitSet()
    for (txn in history.txns) {
      txn.parents.forEach { named.set(it) }
    }
    return history.txns.map { it.end - 1 }.filter { !named.get(it) }.toIntArray()
  }

  private fun versionOf(lvs: IntArray): Version {
    if (lvs.isEmpty()) {
      return Version.root()
    }
    return Version.of(lvs[0], *lvs.copyOfRange(1, lvs.size))
  }

  private fun readHistories(file: Path): List<History> {
    val histories = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonArray }
    return histories.map { historyOf(it.asJsonObject) }
  }

  private fun readHistory(file: Path): History {
    return historyOf(Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject })
  }

  private fun historyOf(json: JsonObject): History {
    val txns = json.arrayAt("txns").map { element ->
      val txn = element.asJsonObject
      val span = txn.arrayAt("span")
      val ops = txn.arrayAt("ops").map { opElement ->
        val op = opElement.asJsonArray
        Op(op[0].asInt, op[1].asInt, op[2].asString)
      }
      val parents = txn.arrayAt("parents").map { it.asInt }.sorted().toIntArray()
      Txn(
        agent = agent(txn.get("agent").asString),
        seqStart = txn.get("seqStart").asInt,
        start = span[0].asInt,
        end = span[1].asInt,
        parents = parents,
        ops = ops,
      )
    }
    return History(txns, json.get("endContent").asString)
  }

  /**
   * The array under [key], which the export format always writes.
   */
  private fun JsonObject.arrayAt(key: String): JsonArray {
    val array = getAsJsonArray(key)
    requireNotNull(array) {
      "The export has no \"$key\" array: $this"
    }
    return array
  }

  private fun testDataFile(): Path {
    return Path.of(PathManager.getCommunityHomePath(), TEST_DATA, "conformanceHistories.json")
  }

  private fun referenceFile(name: String): Path {
    return Path.of(PathManager.getCommunityHomePath()).parent.resolve(REFERENCE_DATA).resolve(name)
  }

  /**
   * One op of a transaction: a delete of [deleted] units at [offset], or an insert of [inserted] there.
   */
  private class Op(
    val offset: Int,
    val deleted: Int,
    val inserted: String,
  ) {
    fun length(): Int {
      return if (deleted > 0) deleted else inserted.length
    }
  }

  /**
   * One transaction: the units `[start, end)` of [agent], from [seqStart], after [parents].
   */
  private class Txn(
    val agent: Agent,
    val seqStart: Int,
    val start: Int,
    val end: Int,
    val parents: IntArray,
    val ops: List<Op>,
  ) {
    override fun toString(): String {
      return "txn[$start..${end - 1}] of $agent after ${parents.contentToString()}"
    }
  }

  private class History(val txns: List<Txn>, val endContent: String) {
    private val starts = IntArray(txns.size) { txns[it].start }

    /**
     * The transaction that holds the unit [lv].
     */
    fun txnAt(lv: Int): Txn {
      val index = starts.binarySearch(lv)
      return txns[if (index >= 0) index else -index - 2]
    }
  }

  private companion object {
    /**
     * The sizes of the data sets, so a truncated file fails and does not pass on less data.
     */
    const val REPOSITORY_HISTORIES = 150

    /**
     * The test data of the feature, under the community root.
     */
    const val TEST_DATA = "platform/platform-tests/testData/editor/docBranch"

    /**
     * The test data of the reference checkout, under the repository root.
     */
    const val REFERENCE_DATA = "Resources/eg-walker/eg-walker-reference/testdata"
    const val REFERENCE_HISTORIES = 1000
  }
}

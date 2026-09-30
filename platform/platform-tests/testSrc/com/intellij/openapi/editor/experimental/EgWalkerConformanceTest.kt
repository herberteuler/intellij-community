// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Replays the conformance histories of the reference implementation, and compares each text with
 * the text the reference expects. Diamond types wrote the histories, and the reference replays
 * all of them to the same texts. So only this test holds the port to an implementation other
 * than itself.
 *
 * A history is a list of transactions in the export format of diamond types. A transaction names
 * its agent, its first seq, its parents as lvs, and a list of ops. Each op after the first has
 * the op before it as its only parent. The lvs match this graph's lvs, because the graph appends
 * the transactions in their order.
 *
 * The data is not in the repository. It comes with the checkout of the reference under
 * `Resources/eg-walker`, and the test is skipped without it.
 */
class EgWalkerConformanceTest {

  @Test
  fun `every conformance history replays to the expected text`() {
    val file = conformanceFile()
    assumeTrue(Files.exists(file)) { "No conformance data at $file" }
    val histories = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonArray }
    assertEquals(HISTORIES, histories.size())
    for ((index, element) in histories.withIndex()) {
      val history = element.asJsonObject
      val graph = graphOf(history.arrayAt("txns"))
      assertEquals(history.get("endContent").asString, graph.replay().string()) { "history $index" }
    }
  }

  private fun graphOf(txns: JsonArray): EventGraph {
    var graph = EventGraph.createGraph()
    for (element in txns) {
      val txn = element.asJsonObject
      val span = txn.arrayAt("span")
      assertEquals(span[0].asInt, graph.size()) { "the first lv of $txn" }
      val agent = agent(txn.get("agent").asString)
      var seq = txn.get("seqStart").asInt
      var parents = versionOf(txn.arrayAt("parents"))
      for (opElement in txn.arrayAt("ops")) {
        val op = opElement.asJsonArray
        val offset = op[0].asInt
        val deleted = op[1].asInt
        val event = if (deleted > 0) {
          Event.createDelete(agent, seq, offset, deleted)
        } else {
          Event.createInsert(agent, seq, offset, op[2].asString)
        }
        graph = graph.append(event, parents)
        seq += event.length()
        parents = Version.of(graph.size() - 1)
      }
      assertEquals(span[1].asInt, graph.size()) { "the lv after $txn" }
    }
    return graph
  }

  private fun versionOf(lvs: JsonArray): Version {
    if (lvs.isEmpty) {
      return Version.root()
    }
    val all = lvs.map { it.asInt }.sorted()
    return Version.of(all[0], *all.drop(1).toIntArray())
  }

  /** The array under [key], which the export format always writes. */
  private fun JsonObject.arrayAt(key: String): JsonArray {
    val array = getAsJsonArray(key)
    requireNotNull(array) {
      "The export has no \"$key\" array: $this"
    }
    return array
  }

  private fun conformanceFile(): Path {
    return Path.of(PathManager.getCommunityHomePath()).parent
      .resolve("Resources/eg-walker/eg-walker-reference/testdata/conformance.json")
  }

  private companion object {
    /** The size of the data set, so a truncated file fails and does not pass on less data. */
    const val HISTORIES = 1000
  }
}

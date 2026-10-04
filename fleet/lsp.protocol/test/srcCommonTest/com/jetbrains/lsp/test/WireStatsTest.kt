package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.implementation.LspWireStats
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.implementation.withLsp
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.RequestType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fast path of the `withLsp` read loop, seen through [LspWireStats] (A10): a request, a notification and a response
 * in the usual member order decode their payload in the envelope pass, with no second pass and no retry. This pins on
 * the session what the tests of [LspWireBody.read] cannot see from outside the module.
 */
class WireStatsTest {
  private val echo = RequestType("x/echo", String.serializer(), String.serializer(), String.serializer())
  private val number = RequestType("x/number", Int.serializer(), Int.serializer(), String.serializer())
  private val note = NotificationType("x/note", String.serializer())

  @Test
  fun `the loop decodes a request, a notification and a response in one pass each`() = runTest {
    val stats = session { client, toSession, fromSession ->
      toSession.send(body("""{"jsonrpc":"2.0","id":1,"method":"x/echo","params":"a"}"""))
      assertEquals(parse("""{"jsonrpc":"2.0","id":1,"result":"a!"}"""), fromSession.receive().json())
      toSession.send(body("""{"jsonrpc":"2.0","method":"x/note","params":"n"}"""))
      assertEquals("n", notes.receive())
      val answer = async { client.request(echo, "mine") }
      val id = idOf(fromSession.receive())
      toSession.send(body("""{"jsonrpc":"2.0","id":$id,"result":"yours"}"""))
      assertEquals("yours", answer.await())
    }
    assertEquals("firstPassHits=3, secondPasses=0, retries=0", stats.toString())
  }

  @Test
  fun `a payload before method or id is counted as a second pass`() = runTest {
    val stats = session { client, toSession, fromSession ->
      toSession.send(body("""{"jsonrpc":"2.0","params":"a","id":1,"method":"x/echo"}"""))
      assertEquals(parse("""{"jsonrpc":"2.0","id":1,"result":"a!"}"""), fromSession.receive().json())
      val answer = async { client.request(echo, "mine") }
      val id = idOf(fromSession.receive())
      toSession.send(body("""{"jsonrpc":"2.0","result":"yours","id":$id}"""))
      assertEquals("yours", answer.await())
      // an unknown method needs no second pass: nobody decodes its params
      toSession.send(body("""{"jsonrpc":"2.0","params":"a","method":"x/unknown"}"""))
    }
    assertEquals("firstPassHits=0, secondPasses=2, retries=0", stats.toString())
  }

  @Test
  fun `a payload that fails in the envelope pass is counted as a retry`() = runTest {
    val stats = session { _, toSession, fromSession ->
      toSession.send(body("""{"jsonrpc":"2.0","id":1,"method":"x/number","params":"not a number"}"""))
      val error = (fromSession.receive().json() as JsonObject)["error"] as JsonObject
      assertEquals(parse("${ErrorCodes.InvalidParams}"), error["code"])
    }
    assertEquals("firstPassHits=0, secondPasses=0, retries=1", stats.toString())
  }

  // --- helpers ---

  private val notes = Channel<String>(Channel.UNLIMITED)

  /** Runs [checks] against a wire session that counts in its own [LspWireStats], ends it, and returns the counts. */
  private suspend fun TestScope.session(
    checks: suspend CoroutineScope.(LspClient, Channel<LspWireBody>, Channel<LspWireOutgoing>) -> Unit,
  ): LspWireStats {
    val toSession = Channel<LspWireBody>(Channel.UNLIMITED)
    val fromSession = Channel<LspWireOutgoing>(Channel.UNLIMITED)
    val handlers = lspHandlers {
      request(echo) { text -> "$text!" }
      request(number) { n -> n + 1 }
      notification(note) { text -> notes.send(text) }
    }
    val stats = LspWireStats()
    val client = CompletableDeferred<LspClient>()
    val job = launch(stats) {
      withLsp(toSession, fromSession, handlers) { lspClient ->
        client.complete(lspClient)
        awaitCancellation()
      }
    }
    coroutineScope { checks(client.await(), toSession, fromSession) }
    testScheduler.advanceUntilIdle()
    job.cancelAndJoin()
    return stats
  }

  private fun idOf(message: LspWireOutgoing): String = (message.json() as JsonObject)["id"].toString()

  private fun body(text: String): LspWireBody = LspWireCodec.decodeFrameBody(text.encodeToByteArray())

  private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)
}

package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.implementation.LspResultDecodeException
import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.implementation.withLsp
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.RequestType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * JSON-RPC tolerance of the `withLsp` loop (S2): a message whose envelope does not decode fails only what it belongs
 * to. A response fails its request, a request with a readable id gets `InvalidRequest`, the rest is dropped; the
 * session keeps serving after each. Only a framing failure (a body that is not JSON) ends it, see
 * `ProtocolFramingTest`. The peer is the test itself, over wire channels.
 */
class EnvelopeToleranceTest {
  private val echo = RequestType("x/echo", String.serializer(), String.serializer(), String.serializer())
  private val note = NotificationType("x/note", String.serializer())

  @Test
  fun `a response with a malformed error fails only its request`() = runTest {
    session { client, toSession, fromSession ->
      val bad = async { assertFailsWith<LspResultDecodeException> { client.request(echo, "first") } }
      val firstId = idOf(fromSession.receive())
      toSession.send(body("""{"jsonrpc":"2.0","id":$firstId,"error":{"code":"not a number","message":1}}"""))
      val failure = bad.await()
      assertEquals(ErrorCodes.RequestFailed, failure.errorCode)
      assertEquals(echo.method, failure.method)

      assertServes(client, toSession, fromSession)
    }
  }

  @Test
  fun `a response that is not JSON-RPC 2_0 fails only its request`() = runTest {
    session { client, toSession, fromSession ->
      val bad = async { assertFailsWith<LspResultDecodeException> { client.request(echo, "first") } }
      val firstId = idOf(fromSession.receive())
      toSession.send(body("""{"jsonrpc":"1.0","id":$firstId,"result":"r"}"""))
      assertTrue(bad.await().message!!.contains("not json rpc message"), bad.await().message)

      assertServes(client, toSession, fromSession)
    }
  }

  @Test
  fun `a bad response to no pending request, or with no readable id, is dropped`() = runTest {
    session { client, toSession, fromSession ->
      val pending = async { client.request(echo, "first") }
      val id = idOf(fromSession.receive())
      toSession.send(body("""{"jsonrpc":"2.0","id":999,"error":{"code":"x"}}"""))
      toSession.send(body("""{"jsonrpc":"2.0","id":{"x":1},"error":{"code":"x"}}"""))
      toSession.send(body("""{"jsonrpc":"2.0","id":null,"error":{"code":"x"}}"""))
      // the request is still pending: its own good response completes it
      toSession.send(body("""{"jsonrpc":"2.0","id":$id,"result":"late"}"""))
      assertEquals("late", pending.await())

      assertServes(client, toSession, fromSession)
    }
  }

  @Test
  fun `a request whose envelope does not decode gets InvalidRequest when its id is readable`() = runTest {
    session { client, toSession, fromSession ->
      for ((id, request) in listOf(
        "5" to """{"jsonrpc":"1.0","id":5,"method":"x/echo","params":"a"}""",
        "\"s\"" to """{"jsonrpc":"2.0","id":"s","method":{"m":1},"params":"a"}""",
        "6" to """{"id":6,"method":"x/echo","params":"a"}""",
      )) {
        toSession.send(body(request))
        val answer = fromSession.receive().json()
        assertEquals(parse(id), (answer as JsonObject)["id"], request)
        val error = answer["error"] as JsonObject
        assertEquals(parse("${ErrorCodes.InvalidRequest}"), error["code"], request)
        assertTrue(error["message"].toString().startsWith("\"invalid request: "), "$error")
      }

      assertServes(client, toSession, fromSession)
    }
  }

  @Test
  fun `a request with no readable id whose envelope does not decode is dropped`() = runTest {
    session { client, toSession, fromSession ->
      toSession.send(body("""{"jsonrpc":"1.0","id":{"x":1},"method":"x/echo","params":"a"}"""))
      toSession.send(body("""{"jsonrpc":"2.0","id":[1],"method":{"m":1}}"""))
      toSession.send(body("""[1,2]"""))
      toSession.send(body(""""a string""""))
      // no answer to any of them: the first frame out is the answer to the good request
      assertServes(client, toSession, fromSession)
    }
  }

  @Test
  fun `a bad notification is dropped`() = runTest {
    session { client, toSession, fromSession ->
      toSession.send(body("""{"jsonrpc":"1.0","method":"x/note","params":"bad"}"""))
      toSession.send(body("""{"jsonrpc":"2.0","method":{"m":1},"params":"bad"}"""))
      toSession.send(body("""{"jsonrpc":"2.0","id":{"x":1},"method":"x/note","params":"bad"}"""))
      toSession.send(body("""{"jsonrpc":"2.0","method":"${'$'}/cancelRequest","params":{"id":{"x":1}}}"""))
      toSession.send(body("""{"jsonrpc":"2.0","method":"${'$'}/cancelRequest"}"""))
      toSession.send(body("""{"jsonrpc":"2.0","method":"x/note","params":"good"}"""))
      assertEquals("good", notes.receive())

      assertServes(client, toSession, fromSession)
    }
  }

  // --- helpers ---

  private val notes = Channel<String>(Channel.UNLIMITED)

  /** Runs [checks] against a wire session with [echo] and [note] handlers, then ends the session. */
  private suspend fun CoroutineScope.session(
    checks: suspend CoroutineScope.(LspClient, Channel<LspWireBody>, Channel<LspWireOutgoing>) -> Unit,
  ) {
    val toSession = Channel<LspWireBody>(Channel.UNLIMITED)
    val fromSession = Channel<LspWireOutgoing>(Channel.UNLIMITED)
    val handlers = lspHandlers {
      request(echo) { text -> "$text!" }
      notification(note) { text -> notes.send(text) }
    }
    val client = CompletableDeferred<LspClient>()
    val job = launch {
      withLsp(toSession, fromSession, handlers) { lspClient ->
        client.complete(lspClient)
        awaitCancellation()
      }
    }
    coroutineScope { checks(client.await(), toSession, fromSession) }
    assertTrue(job.isActive, "the session is still running")
    job.cancelAndJoin()
  }

  /** The session still answers a request from the peer, handles a notification and gets the answer of its own request. */
  private suspend fun CoroutineScope.assertServes(client: LspClient, toSession: Channel<LspWireBody>, fromSession: Channel<LspWireOutgoing>) {
    toSession.send(body("""{"jsonrpc":"2.0","id":100,"method":"x/echo","params":"still"}"""))
    assertEquals(parse("""{"jsonrpc":"2.0","id":100,"result":"still!"}"""), fromSession.receive().json())
    toSession.send(body("""{"jsonrpc":"2.0","method":"x/note","params":"after"}"""))
    assertEquals("after", notes.receive())
    val answer = async { client.request(echo, "mine") }
    val id = idOf(fromSession.receive())
    toSession.send(body("""{"jsonrpc":"2.0","id":$id,"result":"yours"}"""))
    assertEquals("yours", answer.await())
  }

  private fun idOf(message: LspWireOutgoing): String = (message.json() as JsonObject)["id"].toString()

  private fun body(text: String): LspWireBody = LspWireCodec.decodeFrameBody(text.encodeToByteArray())

  private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)
}

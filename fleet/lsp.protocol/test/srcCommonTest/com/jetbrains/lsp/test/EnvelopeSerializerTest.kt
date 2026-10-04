package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireIncoming
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.implementation.withLsp
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.HoverParams
import com.jetbrains.lsp.protocol.HoverRequestType
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.RequestType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The one-pass envelope decode on the `withLsp` loop: when `method` (or a response's `id`) comes before the payload and
 * the session knows its serializer, the payload is decoded typed in the envelope pass; otherwise it is skipped and
 * decoded on demand. Either way the handler (or the caller) gets the same value. A payload that fails in the envelope
 * pass leaves the envelope readable and fails only that message.
 *
 * The session reads the bodies the test sends ([LspWireBody.read] with its resolver, internal); a message the test reads
 * itself ([LspWireBody.read], no resolver) decodes each payload on demand, a new instance per call. Whether the session
 * hit the envelope pass is not visible from here (no shared instance since the body/message split).
 */
class EnvelopeSerializerTest {
  private val params = """{"textDocument":{"uri":"file:///a.kt"},"position":{"line":3,"character":7}}"""
  private val note = NotificationType("t/note", HoverRequestType.paramsSerializer)
  private val line = RequestType("t/line", HoverRequestType.paramsSerializer, Int.serializer(), Unit.serializer())

  @Test
  fun `params after method reach the handler`() = session {
    val message = send("""{"jsonrpc":"2.0","method":"t/note","params":$params}""").read()
    val handled = notes.receive()
    assertEquals(secondPass(message), handled, "same value as the second pass")
  }

  @Test
  fun `params before method are decoded on demand`() = session {
    val message = send("""{"jsonrpc":"2.0","params":$params,"method":"t/note"}""").read()
    val handled = notes.receive()
    assertEquals(secondPass(message), handled)
    assertTrue(message.decodeParams(note.paramsSerializer) !== message.decodeParams(note.paramsSerializer), "decoded per call")
  }

  @Test
  fun `params of another serializer or an unknown method are decoded on demand`() = session {
    val unknown = send("""{"jsonrpc":"2.0","method":"t/unknown","params":$params}""").read()
    val known = send("""{"jsonrpc":"2.0","method":"t/note","params":$params}""").read()
    val handled = notes.receive()
    // the loop handled `unknown` before `known`
    assertEquals(handled, unknown.decodeParams(note.paramsSerializer))
    assertTrue(unknown.decodeParams(note.paramsSerializer) !== unknown.decodeParams(note.paramsSerializer), "decoded per call")
    // another serializer than the handler's gets its own decode
    val other = HoverParams.serializer().nullable
    assertEquals(handled, known.decodeParams(other))
    assertTrue(known.decodeParams(other) !== handled)
  }

  @Test
  fun `request params decode and wrong-shaped params fail only the request`() = session {
    send("""{"jsonrpc":"2.0","id":1,"method":"t/line","params":$params}""")
    assertEquals(3, resultOf(fromServer.receive(), 1))

    val bad = send("""{"jsonrpc":"2.0","id":2,"method":"t/line","params":{"textDocument":5,"position":{"line":3,"character":7}}}""")
    val error = fromServer.receive().json().jsonObject
    assertEquals(JsonPrimitive(2), error["id"])
    assertEquals(JsonPrimitive(ErrorCodes.InvalidParams), error["error"]!!.jsonObject["code"])
    assertTrue(error["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.startsWith("invalid params of t/line: "), error.toString())
    val badMessage = bad.read()
    assertEquals(LspWireIncoming.Kind.Request, badMessage.kind)
    assertEquals("t/line", badMessage.method)
    assertFailsWith<SerializationException> { badMessage.decodeParams(line.paramsSerializer) }

    // the session goes on
    send("""{"jsonrpc":"2.0","id":3,"method":"t/line","params":$params}""")
    assertEquals(3, resultOf(fromServer.receive(), 3))
  }

  @Test
  fun `a result after id and a result before id reach the caller`() = session {
    val hover = """{"contents":{"kind":"markdown","value":"v"}}"""
    for (idFirst in listOf(true, false)) {
      val call = async { client.request(HoverRequestType, LSP.json.decodeFromString(HoverParams.serializer(), params)) }
      val request = fromServer.receive().json().jsonObject
      val id = request["id"]!!.jsonPrimitive.content
      val response = send(if (idFirst) """{"jsonrpc":"2.0","id":$id,"result":$hover}""" else """{"jsonrpc":"2.0","result":$hover,"id":$id}""").read()
      val value = assertNotNull(call.await())
      assertEquals(value, response.decodeResult(HoverRequestType.resultSerializer))
      assertTrue(value !== response.decodeResult(HoverRequestType.resultSerializer), "a message of its own")
    }
  }

  @Test
  fun `a body read before the session is read again by the session`() = session {
    val body = LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0","method":"t/note","params":$params}""".encodeToByteArray())
    val message = body.read()
    assertEquals(LspWireIncoming.Kind.Notification, message.kind)
    toServer.send(body)
    val handled = notes.receive()
    assertEquals(secondPass(message), handled)
    assertTrue(handled !== message.decodeParams(note.paramsSerializer), "decoded per call")
  }

  private class Session(
    val toServer: Channel<LspWireBody>,
    val fromServer: Channel<LspWireOutgoing>,
    val notes: Channel<HoverParams>,
    val client: LspClient,
    scope: CoroutineScope,
  ) : CoroutineScope by scope {
    suspend fun send(body: String): LspWireBody =
      LspWireCodec.decodeFrameBody(body.encodeToByteArray()).also { toServer.send(it) }
  }

  private fun session(test: suspend Session.() -> Unit) = runTest {
    val toServer = Channel<LspWireBody>(Channel.UNLIMITED)
    val fromServer = Channel<LspWireOutgoing>(Channel.UNLIMITED)
    val notes = Channel<HoverParams>(Channel.UNLIMITED)
    val client = CompletableDeferred<LspClient>()
    val server = launch {
      withLsp(toServer, fromServer, lspHandlers {
        notification(note) { notes.send(it) }
        request(line) { it.position.line }
      }) { client.complete(it); awaitCancellation() }
    }
    Session(toServer, fromServer, notes, client.await(), this).test()
    server.cancelAndJoin()
  }

  private fun secondPass(message: LspWireIncoming): HoverParams =
    LspWireCodec.decodeFrameBody(message.toString().encodeToByteArray()).read().decodeParams(note.paramsSerializer)!!

  private fun resultOf(response: LspWireOutgoing, id: Int): Int {
    val json = response.json() as JsonObject
    assertEquals(JsonPrimitive(id), json["id"])
    return json["result"]!!.jsonPrimitive.content.toInt()
  }
}

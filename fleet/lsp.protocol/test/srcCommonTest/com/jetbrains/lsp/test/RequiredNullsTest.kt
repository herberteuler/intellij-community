package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.protocol.ClientCapabilities
import com.jetbrains.lsp.protocol.ClientInfo
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionResolveRequestType
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.Initialize
import com.jetbrains.lsp.protocol.InitializeParams
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.StringOrInt
import com.jetbrains.lsp.protocol.URI
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Members the LSP 3.17 spec marks required and nullable (`x: T | null`, no `?`) are written as `null` on the wire, though
 * [LSP.json] drops a null member (`explicitNulls = false`). Today these are [InitializeParams.processId] and
 * [InitializeParams.rootUri]; optional nullable members stay absent when null.
 */
class RequiredNullsTest {

  @Test
  fun `initialize writes null processId and rootUri`() {
    val params = InitializeParams(processId = null, rootUri = null, capabilities = ClientCapabilities())
    val body = body(LspWireCodec.encodeRequestFrame(StringOrInt.int(1), Initialize.method, Initialize.paramsSerializer, params))
    assertEquals(
      """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}""",
      body,
    )
    val decoded = LspWireCodec.decodeFrameBody(body.encodeToByteArray()).read()
    assertEquals(params, decoded.decodeParams(Initialize.paramsSerializer))
    val tree = assertNotNull(parse(body).jsonObject["params"]).jsonObject
    assertEquals(JsonNull, tree["processId"])
    assertEquals(JsonNull, tree["rootUri"])
  }

  @Test
  fun `initialize keeps member order and values when processId and rootUri are set`() {
    val params = InitializeParams(
      processId = 42,
      clientInfo = ClientInfo(name = "c"),
      rootUri = DocumentUri(URI("file:///p")),
      initializationOptions = JsonObject(mapOf("k" to JsonNull, "n" to JsonPrimitive(1))),
      capabilities = ClientCapabilities(),
    )
    val payload = LSP.json.encodeToString(InitializeParams.serializer(), params)
    assertEquals(
      """{"processId":42,"clientInfo":{"name":"c"},"rootUri":"file:///p","initializationOptions":{"k":null,"n":1},"capabilities":{}}""",
      payload,
    )
    assertEquals(params, LSP.json.decodeFromString(InitializeParams.serializer(), payload))
  }

  @Test
  fun `initialize without processId and rootUri decodes as null`() {
    val params = LSP.decodeJson.decodeFromString(InitializeParams.serializer(), """{"capabilities":{}}""")
    assertEquals(InitializeParams(processId = null, rootUri = null, capabilities = ClientCapabilities()), params)
    assertEquals(params, LSP.json.decodeFromString(InitializeParams.serializer(), """{"processId":null,"rootUri":null,"capabilities":{}}"""))
  }

  @Test
  fun `the tree encoder writes the required nulls too`() {
    val tree = LSP.json.encodeToJsonElement(InitializeParams.serializer(), InitializeParams(capabilities = ClientCapabilities()))
    assertEquals("""{"processId":null,"rootUri":null,"capabilities":{}}""", tree.toString())
  }

  @Test
  fun `completion item with every optional member null encodes as before`() {
    val item = CompletionItem(label = "foo")
    val body = body(LspWireCodec.encodeResultFrame(StringOrInt.int(2), CompletionResolveRequestType.resultSerializer, item))
    assertEquals("""{"jsonrpc":"2.0","id":2,"result":{"label":"foo"}}""", body)
    assertEquals(item, LspWireCodec.decodeFrameBody(body.encodeToByteArray()).read().decodeResult(CompletionResolveRequestType.resultSerializer))
  }

  private fun body(message: LspWireOutgoing): String {
    val frame = message.frame().decodeToString()
    val body = frame.substring(frame.indexOf("\r\n\r\n") + 4)
    assertEquals(parse(body), message.json())
    return body
  }

  private fun parse(body: String): JsonElement = LSP.json.decodeFromString(JsonElement.serializer(), body)
}

package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireIncoming
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.protocol.ApplyEditRequests
import com.jetbrains.lsp.protocol.CompletionRequestType
import com.jetbrains.lsp.protocol.CompletionResolveRequestType
import com.jetbrains.lsp.protocol.Diagnostics
import com.jetbrains.lsp.protocol.ExitNotificationType
import com.jetbrains.lsp.protocol.FoldingRangeRequestType
import com.jetbrains.lsp.protocol.HoverRequestType
import com.jetbrains.lsp.protocol.Initialize
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.NoValueSerializer
import com.jetbrains.lsp.protocol.NotificationMessage
import com.jetbrains.lsp.protocol.RequestMessage
import com.jetbrains.lsp.protocol.ResponseError
import com.jetbrains.lsp.protocol.ResponseMessage
import com.jetbrains.lsp.protocol.Shutdown
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Wire compatibility of [LspWireCodec]: every corpus message decodes to the same typed value and re-encodes to the same
 * frame as the pre-codec path, kept below as a reference copy (`withLsp.kt` envelope steps + `protocolFraming.kt`
 * `writeFrame`, 2026-09-28). The frame bodies must parse to the same JSON tree (key order and whitespace are free), the
 * codec body must be compact (no line break), and its `Content-Length` must count the body's UTF-8 bytes.
 */
class WireCodecCompatTest {

  @Test
  fun `request round-trips tree-identical`() {
    val body = """{"jsonrpc":"2.0","id":1,"method":"textDocument/hover",""" +
               """"params":{"textDocument":{"uri":"file:///a.kt"},"position":{"line":3,"character":7}}}"""
    val request = kindOf(LspWireIncoming.Kind.Request, LspWireCodec.decodeFrameBody(body.encodeToByteArray()))
    assertEquals("textDocument/hover", request.method)
    val serializer = HoverRequestType.paramsSerializer
    val params = assertNotNull(request.decodeParams(serializer))
    val reference = referenceRequest(body, serializer)
    assertEquals(reference.second, params)
    assertFrame(reference.first, LspWireCodec.encodeRequestFrame(request.id!!, request.method!!, serializer, params))
  }

  @Test
  fun `response round-trips tree-identical`() {
    val body = """{"jsonrpc":"2.0","id":2,"result":{"contents":{"kind":"markdown","value":"**fun** foo(): Int"},""" +
               """"range":{"start":{"line":3,"character":4},"end":{"line":3,"character":7}}}}"""
    assertResponse(body)
  }

  @Test
  fun `null result response round-trips tree-identical`() {
    assertResponse("""{"jsonrpc":"2.0","id":"r-7","result":null}""")
  }

  @Test
  fun `error response round-trips tree-identical`() {
    val body = """{"jsonrpc":"2.0","id":3,"error":{"code":-32803,"message":"boom","data":{"reason":"x","n":1}}}"""
    val response = kindOf(LspWireIncoming.Kind.Response, LspWireCodec.decodeFrameBody(body.encodeToByteArray()))
    val error = assertNotNull(response.error)
    assertEquals(-32803, error.code)
    val envelope = LSP.json.decodeFromJsonElement(ResponseMessage.serializer(), parse(body))
    assertEquals(envelope.error, error)
    val referenceFrame = referenceWriteFrame(
      LSP.json.encodeToJsonElement(
        ResponseMessage.serializer(),
        ResponseMessage(jsonrpc = "2.0", id = envelope.id, result = null, error = envelope.error),
      )
    )
    assertFrame(referenceFrame, LspWireCodec.encodeErrorFrame(response.id!!, error))
  }

  @Test
  fun `notification round-trips tree-identical`() {
    val body = """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///a.kt","version":4,""" +
               """"diagnostics":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":5}},""" +
               """"severity":1,"code":"UNRESOLVED","source":"kotlin","message":"Unresolved reference: foo"}]}}"""
    assertNotification(body)
  }

  @Test
  fun `server request round-trips tree-identical`() {
    val body = """{"jsonrpc":"2.0","id":"s-1","method":"workspace/applyEdit","params":{"label":"Rename",""" +
               """"edit":{"changes":{"file:///a.kt":[{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}},""" +
               """"newText":"bar"}]}}}}"""
    val request = kindOf(LspWireIncoming.Kind.Request, LspWireCodec.decodeFrameBody(body.encodeToByteArray()))
    val serializer = ApplyEditRequests.ApplyEdit.paramsSerializer
    val params = assertNotNull(request.decodeParams(serializer))
    val reference = referenceRequest(body, serializer)
    assertEquals(reference.second, params)
    assertFrame(reference.first, LspWireCodec.encodeRequestFrame(request.id!!, request.method!!, serializer, params))
  }

  @Test
  fun `non-ASCII text round-trips tree-identical`() {
    // Content-Length counts UTF-8 bytes: 2-, 3- and 4-byte sequences, plus an escaped quote and a line break.
    assertResponse("""{"jsonrpc":"2.0","id":5,"result":{"contents":{"kind":"plaintext","value":"héllo — 日本語 \"q\"\n😀"}}}""")
    assertNotification(
      """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///ü/ß.kt","diagnostics":""" +
      """[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"Ошибка: ∅ 🚫"}]}}"""
    )
  }

  @Test
  fun `method and string id with quotes, backslashes and non-ASCII encode tree-identical`() {
    val id = StringOrInt.string("id \"q\" \\ é 😀\n")
    val method = "x/\"odd\" \\ méthode 日本"
    val serializer = HoverRequestType.paramsSerializer
    val params = assertNotNull(
      LSP.json.decodeFromString(serializer, """{"textDocument":{"uri":"file:///a.kt"},"position":{"line":1,"character":2}}""")
    )
    val reference = referenceWriteFrame(
      LSP.json.encodeToJsonElement(
        RequestMessage.serializer(),
        RequestMessage(jsonrpc = "2.0", id = id, method = method, params = LSP.json.encodeToJsonElement(serializer, params)),
      )
    )
    val frame = LspWireCodec.encodeRequestFrame(id, method, serializer, params)
    assertFrame(reference, frame)
    val request = kindOf(LspWireIncoming.Kind.Request, LspWireCodec.decodeFrameBody(body(frame)))
    assertEquals<StringOrInt?>(id, request.id)
    assertEquals(method, request.method)
    val notification = LspWireCodec.encodeNotificationFrame(method, serializer, params)
    assertFrame(
      referenceWriteFrame(
        LSP.json.encodeToJsonElement(
          NotificationMessage.serializer(),
          NotificationMessage(jsonrpc = "2.0", method = method, params = LSP.json.encodeToJsonElement(serializer, params)),
        )
      ),
      notification,
    )
    assertEquals(method, kindOf(LspWireIncoming.Kind.Notification, LspWireCodec.decodeFrameBody(body(notification))).method)
    assertFrame(
      referenceWriteFrame(
        LSP.json.encodeToJsonElement(
          ResponseMessage.serializer(),
          ResponseMessage(jsonrpc = "2.0", id = id, result = LSP.json.encodeToJsonElement(NoValueSerializer, null), error = null),
        )
      ),
      LspWireCodec.encodeResultFrame(id, NoValueSerializer, null),
    )
  }

  @Test
  fun `frame is the Content-Length header and the compact body, counted in UTF-8 bytes`() {
    val id = StringOrInt.string("é-1")
    val method = "x/日本"
    val serializer = HoverRequestType.paramsSerializer
    val paramsText = """{"textDocument":{"uri":"file:///ü/😀.kt"},"position":{"line":1,"character":2}}"""
    val params = assertNotNull(LSP.json.decodeFromString(serializer, paramsText))
    val idText = "\"é-1\""
    assertExactFrame(
      """{"jsonrpc":"2.0","id":$idText,"method":"x/日本","params":$paramsText}""",
      LspWireCodec.encodeRequestFrame(id, method, serializer, params),
    )
    assertExactFrame(
      """{"jsonrpc":"2.0","method":"x/日本","params":$paramsText}""",
      LspWireCodec.encodeNotificationFrame(method, serializer, params),
    )
    assertExactFrame(
      """{"jsonrpc":"2.0","id":$idText,"result":null}""",
      LspWireCodec.encodeResultFrame(id, HoverRequestType.resultSerializer, null),
    )
    assertExactFrame(
      """{"jsonrpc":"2.0","id":7,"result":{"contents":{"kind":"plaintext","value":"Ошибка ∅ 🚫"}}}""",
      LspWireCodec.encodeResultFrame(StringOrInt.int(7), HoverRequestType.resultSerializer,
                                     LSP.json.decodeFromString(HoverRequestType.resultSerializer, """{"contents":{"kind":"plaintext","value":"Ошибка ∅ 🚫"}}""")),
    )
    assertExactFrame(
      """{"jsonrpc":"2.0","id":$idText,"error":{"code":-32001,"message":"boom ü","data":[1]}}""",
      LspWireCodec.encodeErrorFrame(id, ResponseError(code = -32001, message = "boom ü", data = parse("[1]"))),
    )
  }

  /** [message]'s frame is exactly `Content-Length: <UTF-8 byte count of body>\r\n\r\n<body>`; its tree is the body's tree. */
  private fun assertExactFrame(body: String, message: LspWireOutgoing) {
    val bodyBytes = body.encodeToByteArray()
    assertTrue(bodyBytes.size > body.length, "the body must have non-ASCII text so that bytes differ from chars: $body")
    assertContentEquals("Content-Length: ${bodyBytes.size}\r\n\r\n".encodeToByteArray() + bodyBytes, message.frame(), body)
    assertEquals(parse(body), message.json())
    assertEquals(message.json().toString(), message.toString())
  }

  @Test
  fun `null params and null results keep the explicit null`() {
    val id = StringOrInt.int(9)
    // shutdown: params and result are NoValueSerializer, which encodes `null`
    val request = LspWireCodec.encodeRequestFrame(id, Shutdown.method, Shutdown.paramsSerializer, null)
    assertFrame(
      referenceWriteFrame(
        LSP.json.encodeToJsonElement(
          RequestMessage.serializer(),
          RequestMessage(jsonrpc = "2.0", id = id, method = Shutdown.method, params = LSP.json.encodeToJsonElement(Shutdown.paramsSerializer, null)),
        )
      ),
      request,
    )
    assertEquals(JsonNull, parse(body(request).decodeToString()).jsonObject["params"])
    // JSON-RPC requires `result` on success, also when it is null
    val result = LspWireCodec.encodeResultFrame(id, Shutdown.resultSerializer, null)
    assertFrame(
      referenceWriteFrame(
        LSP.json.encodeToJsonElement(
          ResponseMessage.serializer(),
          ResponseMessage(jsonrpc = "2.0", id = id, result = LSP.json.encodeToJsonElement(Shutdown.resultSerializer, null), error = null),
        )
      ),
      result,
    )
    assertEquals(JsonNull, parse(body(result).decodeToString()).jsonObject["result"])
    val nullableHover = LspWireCodec.encodeResultFrame(id, HoverRequestType.resultSerializer, null)
    assertEquals(JsonNull, parse(body(nullableHover).decodeToString()).jsonObject["result"])
    // exit: Unit params encode as `{}`
    val exit = LspWireCodec.encodeNotificationFrame(ExitNotificationType.method, ExitNotificationType.paramsSerializer, Unit)
    assertFrame(
      referenceWriteFrame(
        LSP.json.encodeToJsonElement(
          NotificationMessage.serializer(),
          NotificationMessage(jsonrpc = "2.0", method = ExitNotificationType.method, params = LSP.json.encodeToJsonElement(ExitNotificationType.paramsSerializer, Unit)),
        )
      ),
      exit,
    )
  }

  @Test
  fun `error responses without data, with null data and with a quoted message encode tree-identical`() {
    val id = StringOrInt.string("e-\"1\"")
    for (error in listOf(
      ResponseError(code = -32601, message = "no handler"),
      ResponseError(code = -32603, message = "null data", data = JsonNull),
      ResponseError(code = -32803, message = "boom \"x\" \\ ü\n", data = JsonObject(mapOf("n" to JsonPrimitive(1)))),
    )) {
      val frame = LspWireCodec.encodeErrorFrame(id, error)
      assertFrame(
        referenceWriteFrame(
          LSP.json.encodeToJsonElement(ResponseMessage.serializer(), ResponseMessage(jsonrpc = "2.0", id = id, result = null, error = error))
        ),
        frame,
      )
      val body = parse(body(frame).decodeToString()).jsonObject
      assertFalse("result" in body, "an error response has no result: $body")
      // decode reads `"data":null` as no data
      val decoded = kindOf(LspWireIncoming.Kind.Response, LspWireCodec.decodeFrameBody(body(frame))).error
      assertEquals(error.copy(data = error.data?.takeIf { it != JsonNull }), decoded)
    }
  }

  /**
   * Codec bytes equal the bytes of the generated serializers under [LSP.json] (the encoding before S4) for the whole
   * corpus, except the spec's required nullable members ([requiredNullMembers]), which go out as `null` instead of being
   * dropped. Every other null member stays absent.
   */
  @Test
  fun `wire bytes equal generated serializer bytes except required null members`() {
    var stripped = 0
    for ((serializer, payload, before) in wireCorpus) {
      @Suppress("UNCHECKED_CAST")
      val ser = serializer as KSerializer<Any?>
      val value = LSP.json.decodeFromString(ser, payload)
      val expected = before ?: LSP.json.encodeToString(ser, value)
      for (frame in listOf(LspWireCodec.encodeResultFrame(StringOrInt.int(1), ser, value),
                           LspWireCodec.encodeNotificationFrame("x/corpus", ser, value))) {
        val body = parse(body(frame).decodeToString()).jsonObject
        val member = assertNotNull(body["result"] ?: body["params"])
        val (withoutRequired, count) = stripRequiredNulls(member)
        stripped += count
        assertEquals(expected, LSP.json.encodeToString(JsonElement.serializer(), withoutRequired), payload)
      }
      assertEquals(value, LspWireCodec.decodeFrameBody(body(LspWireCodec.encodeResultFrame(StringOrInt.int(1), ser, value))).read().decodeResult(ser))
    }
    assertEquals(2, stripped / 2, "the corpus holds one initialize with both required members null")
  }

  /** The members the LSP 3.17 metaModel marks required and nullable that the codec writes as `null` (S4 audit). */
  private val requiredNullMembers = setOf("processId", "rootUri")

  private fun stripRequiredNulls(element: JsonElement): Pair<JsonElement, Int> = when (element) {
    is JsonObject -> {
      var count = 0
      val members = LinkedHashMap<String, JsonElement>()
      for ((key, value) in element) {
        if (key in requiredNullMembers && value == JsonNull) {
          count++
          continue
        }
        val (inner, n) = stripRequiredNulls(value)
        count += n
        members[key] = inner
      }
      JsonObject(members) to count
    }
    is JsonArray -> {
      var count = 0
      JsonArray(element.map { item -> stripRequiredNulls(item).also { count += it.second }.first }) to count
    }
    else -> element to 0
  }

  /** [before]: the bytes before S4 where today's serializer differs (`InitializeParams`), else today's [LSP.json] bytes. */
  private fun <T> corpus(serializer: KSerializer<T>, payload: String, before: String? = null) = Triple(serializer, payload, before)

  private val wireCorpus: List<Triple<KSerializer<*>, String, String?>> = listOf(
    corpus(HoverRequestType.paramsSerializer, """{"textDocument":{"uri":"file:///a.kt"},"position":{"line":3,"character":7}}"""),
    corpus(HoverRequestType.resultSerializer, """{"contents":{"kind":"markdown","value":"x"},"range":{"start":{"line":3,"character":4},"end":{"line":3,"character":7}}}"""),
    corpus(HoverRequestType.resultSerializer, """{"contents":{"kind":"plaintext","value":"y"}}"""),
    corpus(Diagnostics.PublishDiagnosticsNotificationType.paramsSerializer,
      """{"uri":"file:///a.kt","diagnostics":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":5}},""" +
      """"severity":1,"code":"UNRESOLVED","source":"kotlin","message":"Unresolved reference: foo"},""" +
      """{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}},"message":"m"}]}"""),
    corpus(ApplyEditRequests.ApplyEdit.paramsSerializer,
      """{"label":"Rename","edit":{"changes":{"file:///a.kt":[{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}},"newText":"bar"}]}}}"""),
    corpus(ApplyEditRequests.ApplyEdit.paramsSerializer,
      """{"edit":{"documentChanges":[{"textDocument":{"uri":"file:///a.kt","version":null},"edits":[]},""" +
      """{"textDocument":{"uri":"file:///b.kt","version":3},"edits":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"x"}]},""" +
      """{"kind":"create","uri":"file:///c.kt"},{"kind":"rename","oldUri":"file:///c.kt","newUri":"file:///d.kt"},""" +
      """{"kind":"delete","uri":"file:///d.kt","options":{"recursive":true}}]}}"""),
    corpus(Initialize.paramsSerializer, """{"processId":null,"rootUri":null,"capabilities":{}}""", before = """{"capabilities":{}}"""),
    corpus(Initialize.paramsSerializer,
      """{"processId":42,"clientInfo":{"name":"c"},"rootUri":"file:///p","capabilities":{"textDocument":{"hover":{"contentFormat":["markdown"]},""" +
      """"foldingRange":{"lineFoldingOnly":true},"completion":{"completionItem":{"snippetSupport":true}}},"workspace":{"applyEdit":true}},""" +
      """"workspaceFolders":[{"uri":"file:///p","name":"p"}]}""", before =
      """{"processId":42,"clientInfo":{"name":"c"},"rootUri":"file:///p","capabilities":{"workspace":{"applyEdit":true},""" +
      """"textDocument":{"completion":{"completionItem":{"snippetSupport":true}},"hover":{"contentFormat":["markdown"]},""" +
      """"foldingRange":{"lineFoldingOnly":true}}},"workspaceFolders":[{"uri":"file:///p","name":"p"}]}"""),
    corpus(Initialize.resultSerializer,
      """{"capabilities":{"textDocumentSync":2,"hoverProvider":{},"completionProvider":{"triggerCharacters":["."]},"definitionProvider":true,""" +
      """"referencesProvider":{"workDoneProgress":true},"renameProvider":{"prepareProvider":true},""" +
      """"semanticTokensProvider":{"legend":{"tokenTypes":["class"],"tokenModifiers":[]},"full":true}},"serverInfo":{"name":"s"}}"""),
    corpus(FoldingRangeRequestType.resultSerializer, """[{"startLine":1,"endLine":3},{"startLine":4,"endLine":6,"kind":"comment"}]"""),
    corpus(CompletionRequestType.resultSerializer,
      """{"isIncomplete":false,"items":[{"label":"a"},{"label":"b","kind":3,"detail":"x",""" +
      """"textEdit":{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"b"}}]}"""),
    corpus(CompletionResolveRequestType.resultSerializer, """{"label":"a","documentation":{"kind":"markdown","value":"d"},"data":{"k":null}}"""),
  )

  private fun assertResponse(body: String) {
    val response = kindOf(LspWireIncoming.Kind.Response, LspWireCodec.decodeFrameBody(body.encodeToByteArray()))
    val serializer = HoverRequestType.resultSerializer
    val result = response.decodeResult(serializer)
    // reference: readFrame, then withLspImpl, then withLspImpl's response encode and writeFrame
    val envelope = LSP.json.decodeFromJsonElement(ResponseMessage.serializer(), parse(body))
    val referenceValue = envelope.result?.let { LSP.json.decodeFromJsonElement(serializer, it) }
    assertEquals(referenceValue, result)
    val referenceFrame = referenceWriteFrame(
      LSP.json.encodeToJsonElement(
        ResponseMessage.serializer(),
        ResponseMessage(jsonrpc = "2.0", id = envelope.id, result = LSP.json.encodeToJsonElement(serializer, referenceValue), error = null),
      )
    )
    assertFrame(referenceFrame, LspWireCodec.encodeResultFrame(response.id!!, serializer, result))
  }

  private fun assertNotification(body: String) {
    val notification = kindOf(LspWireIncoming.Kind.Notification, LspWireCodec.decodeFrameBody(body.encodeToByteArray()))
    assertEquals(Diagnostics.PublishDiagnosticsNotificationType.method, notification.method)
    val serializer = Diagnostics.PublishDiagnosticsNotificationType.paramsSerializer
    val params = assertNotNull(notification.decodeParams(serializer))
    val envelope = LSP.json.decodeFromJsonElement(NotificationMessage.serializer(), parse(body))
    val referenceValue = LSP.json.decodeFromJsonElement(serializer, assertNotNull(envelope.params))
    assertEquals(referenceValue, params)
    val referenceFrame = referenceWriteFrame(
      LSP.json.encodeToJsonElement(
        NotificationMessage.serializer(),
        NotificationMessage(jsonrpc = "2.0", method = envelope.method, params = LSP.json.encodeToJsonElement(serializer, referenceValue)),
      )
    )
    assertFrame(referenceFrame, LspWireCodec.encodeNotificationFrame(notification.method!!, serializer, params))
  }

  /** @return the reference frame and the reference typed params of the request [body]. */
  private fun <T> referenceRequest(body: String, serializer: KSerializer<T>): Pair<ByteArray, T> {
    val envelope = LSP.json.decodeFromJsonElement(RequestMessage.serializer(), parse(body))
    val value = LSP.json.decodeFromJsonElement(serializer, assertNotNull(envelope.params))
    val frame = referenceWriteFrame(
      LSP.json.encodeToJsonElement(
        RequestMessage.serializer(),
        RequestMessage(jsonrpc = "2.0", id = envelope.id, method = envelope.method, params = LSP.json.encodeToJsonElement(serializer, value)),
      )
    )
    return frame to value
  }

  private fun kindOf(kind: LspWireIncoming.Kind, body: LspWireBody): LspWireIncoming {
    val message = body.read()
    assertEquals(kind, message.kind)
    return message
  }

  private fun parse(body: String): JsonElement =
    LSP.json.decodeFromString(JsonElement.serializer(), body.encodeToByteArray().decodeToString())

  /** Reference copy of `protocolFraming.kt` `writeFrame` before [LspWireCodec], up to the bytes it writes. */
  private fun referenceWriteFrame(jsonElement: JsonElement): ByteArray {
    val str = LSP.json.encodeToString(JsonElement.serializer(), jsonElement)
    val frameStr = buildString {
      val contentLengthInBytes = str.encodeToByteArray().size
      append("Content-Length: $contentLengthInBytes\r\n")
      append("\r\n")
      append(str)
    }
    return frameStr.encodeToByteArray()
  }

  private fun assertFrame(expected: ByteArray, actual: LspWireOutgoing) {
    val (expectedHeader, expectedBody) = splitFrame(expected)
    val (actualHeader, actualBody) = splitFrame(actual.frame())
    assertEquals(parse(expectedBody.decodeToString()), parse(actualBody.decodeToString()))
    assertFalse('\n' in actualBody.decodeToString(), "frame body must be compact: ${actualBody.decodeToString()}")
    assertEquals("Content-Length: ${actualBody.size}", actualHeader)
    assertEquals("Content-Length: ${expectedBody.size}", expectedHeader)
    // the tree is parsed from the same parts the frame is made of
    assertEquals(parse(actualBody.decodeToString()), actual.json())
  }

  /** The body bytes of [message]'s frame. */
  private fun body(message: LspWireOutgoing): ByteArray = splitFrame(message.frame()).second

  /** @return the header block (without the blank line) and the body bytes of [frame]. */
  private fun splitFrame(frame: ByteArray): Pair<String, ByteArray> {
    val separator = "\r\n\r\n".encodeToByteArray()
    val at = (0..frame.size - separator.size).first { i -> separator.indices.all { frame[i + it] == separator[it] } }
    return frame.copyOfRange(0, at).decodeToString() to frame.copyOfRange(at + separator.size, frame.size)
  }
}

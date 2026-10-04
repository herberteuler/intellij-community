package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.ByteWriter
import com.jetbrains.lsp.implementation.LspConnection
import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireIncoming
import com.jetbrains.lsp.implementation.withBaseProtocolFraming
import com.jetbrains.lsp.implementation.withLspFraming
import com.jetbrains.lsp.protocol.ApplyEditRequests
import com.jetbrains.lsp.protocol.CodeActions
import com.jetbrains.lsp.protocol.CompletionRequestType
import com.jetbrains.lsp.protocol.Diagnostics
import com.jetbrains.lsp.protocol.FileChange
import com.jetbrains.lsp.protocol.HoverRequestType
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.NotificationMessage
import com.jetbrains.lsp.protocol.RequestMessage
import com.jetbrains.lsp.protocol.ResponseError
import com.jetbrains.lsp.protocol.ResponseMessage
import com.jetbrains.lsp.protocol.SemanticTokens
import com.jetbrains.lsp.protocol.SemanticTokensDelta
import com.jetbrains.lsp.protocol.SemanticTokensDeltaResult
import com.jetbrains.lsp.protocol.StringOrInt
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.Workspace
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [LspWireIncoming] read from a frame body ([LspWireCodec.decodeFrameBody]) behaves like the tree path it replaces,
 * kept below as a reference (`protocolFraming.kt` `decodeBody` + `LspWireCodec.decodeMessage` + `decodeFromJsonElement`
 * of the payload, 2026-09-28): the same messages fail to parse, the same ones are protocol violations or fail their
 * envelope decode, and the rest have the same kind, id, method, error, payload and [LspWireIncoming.json].
 *
 * The envelope and the payload are decoded by kotlinx passes over the body text, so a body and its message keep the
 * text as [LspWireBody.toString] and [LspWireIncoming.toString], and a body that is not JSON fails its read
 * ([LspWireBody.read]), not when the body is made. These reads have no payload resolver (`withLsp` has one), so each
 * payload is decoded by the second pass; the one-pass path is in [EnvelopeSerializerTest].
 */
class WireIncomingTest {

  private val r = """{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}"""

  // --- bodies kotlinx takes ---

  @Test
  fun `escapes before a closing quote, unicode escapes and surrogate pairs`() {
    assertLikeTree(
      """{"jsonrpc":"2.0", "id":1, "method":"a\\", "params":{"s":"x\"}\\","t":"\\\"","u":"\u00e9\uD83D\uDE00\u0000","v":"\/\b\f\n\r\t"}}""",
      JsonElement.serializer(),
    )
    assertLikeTree("""{"jsonrpc":"2.0", "method":"m\"x\\", "params":"\\\\"}""", JsonElement.serializer())
    assertLikeTree("""{"jsonrpc":"2.0", "id":"\\", "result":"a\"b"}""", JsonElement.serializer())
    // a lone surrogate is only an escape to the parser
    assertLikeTree("""{"jsonrpc":"2.0", "id":2, "result":"\uD800 \uDFFF"}""", JsonElement.serializer())
  }

  @Test
  fun `braces, brackets, commas and colons inside strings`() {
    assertLikeTree(
      """{"jsonrpc":"2.0", "id":1, "method":"}{", "params":{"a":"{[\"}]\":,","b":["]","}",":,"],"c":{"}":"{"}}}""",
      JsonElement.serializer(),
    )
    assertLikeTree("""{"jsonrpc":"2.0", "method":"x", "params":["{", "\"method\":\"y\"", {"id":"}"}]}""", JsonElement.serializer())
  }

  @Test
  fun `numbers, literals and deep nesting`() {
    assertLikeTree(
      """{"jsonrpc":"2.0", "id":7, "result":[1, -2, 3.5, 1e5, -1.25E-3, 6e+2, true, false, null, 0, -0, 123456789012345678901234567890]}""",
      JsonElement.serializer(),
    )
    val deep = "[".repeat(300) + "1" + "]".repeat(300)
    assertLikeTree("""{"jsonrpc":"2.0", "id":7, "result":$deep}""", JsonElement.serializer())
    val deepObject = """{"a":""".repeat(300) + "{}" + "}".repeat(300)
    assertLikeTree("""{"jsonrpc":"2.0", "method":"x", "params":$deepObject}""", JsonElement.serializer())
    assertLikeTree("""{"jsonrpc":"2.0", "id":7, "result":{}}""", JsonElement.serializer())
    assertLikeTree("""{"jsonrpc":"2.0", "id":7, "result":[]}""", JsonElement.serializer())
  }

  @Test
  fun `whitespace everywhere`() {
    val body = " \r\n\t{ \n\"jsonrpc\" \t: \r\n \"2.0\" , \"id\" : 1 ,\n \"method\"\t:\"textDocument/hover\" , \"params\" : " +
               """{ "textDocument" : { "uri" : "file:///a.kt" } , "position" : { "line" : 3 , "character" : 7 } } , "x" : [ 1 , { } , [ ] ] } """ +
               "\n\r\t "
    assertLikeTree(body, HoverRequestType.paramsSerializer)
  }

  @Test
  fun `member orders`() {
    val params = """{"textDocument":{"uri":"file:///a.kt"},"position":{"line":3,"character":7}}"""
    val members = listOf(""""jsonrpc":"2.0"""", """"id":"q-1"""", """"method":"textDocument/hover"""", """"params":$params""")
    for (order in permutations(members)) {
      assertLikeTree("{ ${order.joinToString(" , ")} }", HoverRequestType.paramsSerializer)
    }
    val result = """{"contents":{"kind":"markdown","value":"v"}}"""
    for (order in permutations(listOf(""""jsonrpc":"2.0"""", """"id":1""", """"result":$result""", """"extra":{"id":2}"""))) {
      assertLikeTree("{ ${order.joinToString(" , ")} }", HoverRequestType.resultSerializer)
    }
  }

  @Test
  fun `duplicate top-level keys take the last value, like the tree`() {
    assertLikeTree("""{"jsonrpc":"1.0", "jsonrpc":"2.0", "id":1, "id":"two", "method":"a", "method":"b", "params":1, "params":[2]}""", JsonElement.serializer())
    assertLikeTree("""{"jsonrpc":"2.0", "jsonrpc":"1.0", "method":"a"}""", JsonElement.serializer())
    assertLikeTree("""{"jsonrpc":"2.0", "id":1, "result":{"a":1}, "result":null}""", JsonElement.serializer())
    assertLikeTree("""{"jsonrpc":"2.0", "id":1, "error":{"code":1,"message":"m"}, "error":null, "result":3}""", JsonElement.serializer())
    // inside the payload: kotlinx decides, from the text or from the tree
    assertLikeTree("""{"jsonrpc":"2.0", "id":1, "method":"workspace/applyEdit", "params":{"edit":{}, "edit":{"changes":{}}, "label":"a", "label":"b"}}""",
                  ApplyEditRequests.ApplyEdit.paramsSerializer)
  }

  @Test
  fun `id as int, string, null, other numbers and bad types`() {
    for (id in listOf("1", "0", "007", "-3", "2147483648", "1.5", "1e2", "\"a\"", "\"\"", "\"\\\"q\\\"\"", "null", "true", "x", "\"null\"")) {
      assertLikeTree("""{"jsonrpc":"2.0", "id":$id, "result":null}""", JsonElement.serializer())
      assertLikeTree("""{"jsonrpc":"2.0", "id":$id, "method":"m", "params":null}""", JsonElement.serializer())
    }
    // not a primitive: the envelope decode fails, as before
    for (id in listOf("{}", "[1]", """{"a":1}""")) {
      assertLikeTree("""{"jsonrpc":"2.0", "id":$id, "result":null}""", JsonElement.serializer())
    }
    val scanned = LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0", "id":12, "result":null}""".encodeToByteArray()).read()
    assertEquals(StringOrInt.int(12), scanned.id)
    assertEquals(StringOrInt.string("x"), LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0", "id":"x", "result":null}""".encodeToByteArray()).read().id)
  }

  @Test
  fun `method, params, result and error values`() {
    for (method in listOf("\"a/b\"", "\"\"", "\"a\\u00e9\"", "5", "true", "x/y", "null", "{}", "[]")) {
      assertLikeTree("""{"jsonrpc":"2.0", "method":$method}""", JsonElement.serializer())
      assertLikeTree("""{"jsonrpc":"2.0", "id":1, "method":$method}""", JsonElement.serializer())
    }
    for (payload in listOf("null", "\"null\"", "nullx", "{}", "[]", "1", "\"s\"", "true")) {
      assertLikeTree("""{"jsonrpc":"2.0", "method":"m", "params":$payload}""", JsonElement.serializer())
      assertLikeTree("""{"jsonrpc":"2.0", "id":1, "result":$payload}""", JsonElement.serializer())
    }
    for (error in listOf(
      """{"code":-32601,"message":"no"}""",
      """{"code":-1,"message":"m","data":{"a":[1]}}""",
      """{"code":-1,"message":"m","data":null}""",
      """{"code":"5","message":"m"}""",
      """{"message":"m"}""",
      """{"code":1}""",
      "null", "5", "[]", "\"e\"",
    )) {
      assertLikeTree("""{"jsonrpc":"2.0", "id":1, "error":$error}""", JsonElement.serializer())
      assertLikeTree("""{"jsonrpc":"2.0", "id":1, "error":$error, "result":[1]}""", JsonElement.serializer())
      // a request ignores `error`
      assertLikeTree("""{"jsonrpc":"2.0", "id":1, "method":"m", "error":$error}""", JsonElement.serializer())
    }
  }

  @Test
  fun `protocol violations - missing or wrong jsonrpc, no id or method, not an object`() {
    for (body in listOf(
      """{"id":1, "method":"m"}""",
      """{"jsonrpc":"1.0", "id":1, "method":"m"}""",
      """{"jsonrpc":2.0, "id":1, "method":"m"}""",
      """{"jsonrpc":null, "id":1, "method":"m"}""",
      """{"jsonrpc":"2.0 ", "id":1, "method":"m"}""",
      """{"jsonrpc":"2.0", "params":{}}""",
      """{"jsonrpc":"2.0"}""",
      """{ }""",
      """[{"jsonrpc":"2.0", "method":"m"}]""",
      """ "s" """,
      """ 5 """,
      """ null """,
      """ [] """,
    )) {
      assertSameAsTree(body, JsonElement.serializer())
      assertTrue(outcome(body, JsonElement.serializer()) is Outcome.Violation, body)
    }
    // an escaped "2.0" is still "2.0"
    assertLikeTree("""{"jsonrpc":"\u0032.0", "method":"m"}""", JsonElement.serializer())
  }

  @Test
  fun `lenient keys and literals like the tree parser`() {
    assertLikeTree("""{jsonrpc:"2.0", id:1, method:"m", params:{a:b, c:d-e.f}}""", JsonElement.serializer())
    assertLikeTree("""{"jsonr\u0070c":"2.0", "i\u0064":1, "result":{"a":1}}""", JsonElement.serializer())
    assertLikeTree("""{"jsonrpc":"2.0", "id":1, "result":[abc, 1.2.3, --, tru, nul, ~x, é]}""", JsonElement.serializer())
  }

  // --- bodies kotlinx refuses ---

  @Test
  fun `invalid JSON fails to parse as before - unbalanced, truncated, bad top-level key or separator`() {
    for (body in listOf(
      "",
      "   ",
      """{"jsonrpc":"2.0", "id":1, "result":{"a":1}""",
      """{"jsonrpc":"2.0", "id":1, "result":"abc""",
      """{"jsonrpc":"2.0", "id":1, "result":"\""",
      """{"jsonrpc":"2.0", "id":1, "result":\u0001}""",
      """{"jsonrpc":"2.0", "id":1, "result":1,}""",
      """{"jsonrpc":"2.0", "id":1, "result":1} x""",
      """{"jsonrpc":"2.0", "id":1, "result":1}}""",
      """{"jsonrpc":"2.0", "id":1, "result":\ }""",
      """{"jsonrpc":"2.0", "id":1, "result":}""",
      """{"jsonrpc":"2.0", "id":1, "result":,}""",
      """{"jsonrpc":"2.0", "id":1, "result"}""",
      """{"jsonrpc":"2.0", "id":1, "res\ult":1}""",
      "{\"jsonrpc\":\"2.0\",\u0000\"id\":1}",
      """{"jsonrpc":"2.0", "id":1, "result":1 /* c */}""",
      """{"jsonrpc":"2.0", "id":1, {}:1}""",
      """{"jsonrpc":"2.0", "id":1, "result":{"a":[}}""",
      "{\"jsonrpc\":\"2.0\", \"id\":1, \"result\":{\"a\":\"}}",
    )) {
      assertSameAsTree(body, JsonElement.serializer())
      assertIs<Outcome.ParseFailure>(outcome(body, JsonElement.serializer()), body)
    }
  }

  @Test
  fun `invalid JSON inside a payload that kotlinx cannot skip fails to parse`() {
    // kotlinx skips a payload by its tokens: it reads every string (escapes too) and matches the brackets
    for (body in listOf(
      """{"jsonrpc":"2.0", "id":1, "result":"\u12"}""",
      """{"jsonrpc":"2.0", "id":1, "result":"\u12G4"}""",
      """{"jsonrpc":"2.0", "id":1, "result":"\x"}""",
      """{"jsonrpc":"2.0", "id":1, "result":{"a":1]}""",
      """{"jsonrpc":"2.0", "id":1, "result":[1}}""",
      """{"jsonrpc":"2.0", "id":1, "result":{"a":[{"b":[1,{"c":"d"}]}}]}""",
    )) {
      assertSameAsTree(body, JsonElement.serializer())
      assertIs<Outcome.ParseFailure>(outcome(body, JsonElement.serializer()), body)
    }
  }

  @Test
  fun `invalid JSON inside a payload with matched tokens fails on decode`() {
    // The envelope pass skips the payload token by token, so a payload whose tokens match reads as a message; the
    // payload decode reports the error, and the tree parse of the whole body fails.
    for (body in listOf(
      """{"jsonrpc":"2.0", "id":1, "result":[1,]}""",
      """{"jsonrpc":"2.0", "id":1, "result":[,1]}""",
      """{"jsonrpc":"2.0", "id":1, "result":{"a":1,}}""",
      """{"jsonrpc":"2.0", "id":1, "result":{,"a":1}}""",
      """{"jsonrpc":"2.0", "id":1, "result":{"a" 1}}""",
      """{"jsonrpc":"2.0", "id":1, "result":{"a":}}""",
      """{"jsonrpc":"2.0", "id":1, "result":[1 2]}""",
      """{"jsonrpc":"2.0", "id":1, "result":["a" "b"]}""",
    )) {
      assertIs<Outcome.ParseFailure>(referenceOutcome(body, JsonElement.serializer()), body)
      val message = decode(body)
      assertEquals(body, message.toString(), body)
      assertEquals(LspWireIncoming.Kind.Response, message.kind, body)
      assertEquals(StringOrInt.int(1), message.id, body)
      assertFailsWith<SerializationException>(body) { message.decodeResult(JsonElement.serializer()) }
      assertFailsWith<IllegalStateException>(body) { message.json() }
    }
  }

  @Test
  fun `truncated bodies fail to parse as before`() {
    val body = """{"jsonrpc":"2.0","id":"a\"b","method":"textDocument/hover","params":{"textDocument":{"uri":"file:///a\u00e9.kt"},""" +
               """"position":{"line":3,"character":7},"x":[1,-2.5e3,true,null,"s"]}}"""
    for (end in 0 until body.length) {
      assertSameAsTree(body.substring(0, end), JsonElement.serializer())
    }
    assertLikeTree(body, HoverRequestType.paramsSerializer)
  }

  @Test
  fun `odd input only the tree parser takes fails the envelope`() {
    // Deviation on malformed input: the kotlinx tree parser reads `[1]2]` as `[1,2]`, the streaming decoder does not.
    // The envelope pass fails, and since the tree is a JSON-RPC object, its kotlinx error is the envelope failure.
    for (body in listOf(
      """{"jsonrpc":"2.0", "id":1, "result":[1]2]}""",
      """{"jsonrpc":"2.0", "id":1, "result":{"a":[1]2]}}""",
    )) {
      assertIs<Outcome.Message>(referenceOutcome(body, JsonElement.serializer()), body)
      assertIs<Outcome.EnvelopeFailure>(outcome(body, JsonElement.serializer()), body)
      assertEquals(body, wireBody(body).toString(), body)
    }
  }

  @Test
  fun `a missing comma between top-level members reads like the streaming decoder`() {
    // Deviation on malformed input: kotlinx streaming reads the next key without a comma before it (as every typed
    // payload decode always did inside the payload); the tree parser refuses the body.
    val body = """{"jsonrpc":"2.0", "id":1 "result":1}"""
    assertIs<Outcome.ParseFailure>(referenceOutcome(body, JsonElement.serializer()), body)
    val message = decode(body)
    assertEquals(LspWireIncoming.Kind.Response, message.kind)
    assertEquals(StringOrInt.int(1), message.id)
    assertEquals(JsonPrimitive(1), message.decodeResult(JsonElement.serializer()))
    assertFailsWith<IllegalStateException> { message.json() }
  }

  @Test
  fun `a null result and an absent result both decode as null`() {
    for (body in listOf(
      """{"jsonrpc":"2.0", "id":1, "result":null}""",
      """{"jsonrpc":"2.0", "id":1}""",
      """{"jsonrpc":"2.0", "id":1, "error":null}""",
    )) {
      assertLikeTree(body, HoverRequestType.resultSerializer)
      assertEquals(null, decode(body).decodeResult(HoverRequestType.resultSerializer), body)
      assertEquals(null, decode(body).error, body)
    }
    for (body in listOf("""{"jsonrpc":"2.0", "method":"m", "params":null}""", """{"jsonrpc":"2.0", "method":"m"}""")) {
      assertLikeTree(body, HoverRequestType.paramsSerializer)
      assertEquals(null, decode(body).decodeParams(HoverRequestType.paramsSerializer), body)
    }
  }

  @Test
  fun `a token-matched but malformed payload fails on decode`() {
    // The envelope pass skips a payload with matched tokens; its decode reports the error. The envelope reads fine, the
    // tree does not.
    val body = """{"jsonrpc":"2.0", "id":1, "method":"m", "params":{"a" 1}}"""
    val message = decode(body)
    assertEquals(body, message.toString())
    assertEquals(LspWireIncoming.Kind.Request, message.kind)
    assertEquals(StringOrInt.int(1), message.id)
    assertEquals("m", message.method)
    assertFailsWith<SerializationException> { message.decodeParams(JsonElement.serializer()) }
    assertFailsWith<SerializationException> { message.decodeParams(HoverRequestType.paramsSerializer) }
    assertFailsWith<IllegalStateException> { message.json() }
    // a union: the selector refuses the malformed text, the union serializer reports its own error
    for ((payload, serializer) in listOf(
      """[{"label":"a"},]""" to CompletionRequestType.resultSerializer,
      """[{"name" 1}]""" to Workspace.Symbol.resultSerializer,
      """{"edits" []}""" to SemanticTokensDeltaResult.serializer(),
      """[{"title":"t","command" "c"}]""" to CodeActions.CodeActionRequest.resultSerializer,
    )) {
      val response = decode("""{"jsonrpc":"2.0", "id":1, "result":$payload}""")
      assertEquals(LspWireIncoming.Kind.Response, response.kind, payload)
      assertFailsWith<SerializationException>(payload) { response.decodeResult(serializer) }
    }
  }

  // --- payload decode: the typed value equals the old tree decode ---

  @Test
  fun `typed payloads decode like the tree - wire codec corpus`() {
    assertLikeTree("""{"jsonrpc":"2.0","id":1,"method":"textDocument/hover","params":{"textDocument":{"uri":"file:///a.kt"},"position":{"line":3,"character":7}}}""",
                  HoverRequestType.paramsSerializer)
    assertLikeTree("""{"jsonrpc":"2.0","id":2,"result":{"contents":{"kind":"markdown","value":"**fun** foo(): Int"},"range":$r}}""",
                  HoverRequestType.resultSerializer)
    assertLikeTree("""{"jsonrpc":"2.0","id":"r-7","result":null}""", HoverRequestType.resultSerializer)
    assertLikeTree("""{"jsonrpc":"2.0","id":5,"result":{"contents":{"kind":"plaintext","value":"héllo — 日本語 \"q\"\n😀"}}}""",
                  HoverRequestType.resultSerializer)
    assertLikeTree("""{"jsonrpc":"2.0","id":3,"error":{"code":-32803,"message":"boom","data":{"reason":"x","n":1}}}""", HoverRequestType.resultSerializer)
    assertLikeTree(
      """{"jsonrpc":"2.0","method":"textDocument/publishDiagnostics","params":{"uri":"file:///ü/ß.kt","version":4,"diagnostics":""" +
      """[{"range":$r,"severity":1,"code":"UNRESOLVED","source":"kotlin","message":"Ошибка: ∅ 🚫"},{"range":$r,"code":5,"message":"m","tags":[1,2]}]}}""",
      Diagnostics.PublishDiagnosticsNotificationType.paramsSerializer,
    )
    assertLikeTree(
      """{"jsonrpc":"2.0","id":"s-1","method":"workspace/applyEdit","params":{"label":"Rename","edit":{"changes":{"file:///a.kt":""" +
      """[{"range":$r,"newText":"bar"}]},"documentChanges":[{"textDocument":{"uri":"file:///a.kt","version":null},"edits":[{"range":$r,"newText":"x"}]},""" +
      """{"kind":"create","uri":"file:///b.kt","options":{"overwrite":true}},{"kind":"rename","oldUri":"file:///b.kt","newUri":"file:///c.kt"},""" +
      """{"uri":"file:///c.kt","kind":"delete"}]}}}""",
      ApplyEditRequests.ApplyEdit.paramsSerializer,
    )
    assertLikeTree(
      """{"jsonrpc":"2.0","id":9,"result":{"isIncomplete":false,"items":[{"label":"foo","kind":3,"textEdit":{"range":$r,"newText":"foo()"}},""" +
      """{"label":"bar","insertTextFormat":2,"textEdit":{"range":$r,"snippet":{"kind":"snippet","value":"bar(${'$'}1)"}},"unknown":{"a":[1]}}]}}""",
      CompletionRequestType.resultSerializer,
    )
    assertLikeTree("""{"jsonrpc":"2.0","id":9,"result":[{"label":"a"},{"label":"b","kind":999}]}""", CompletionRequestType.resultSerializer)
  }

  @Test
  fun `typed payloads decode like the tree - streaming serializers corpus`() {
    val edits = listOf(
      """{"range":$r,"newText":"a"}""",
      """{"newText":"a","range":$r}""",
      """{"annotationId":"a1","newText":"x","range":$r}""",
      """{"snippet":{"value":"s","kind":"snippet"},"newText":"ignored","range":$r}""",
      """{"range":$r,"newText":null}""",
      """{"range":$r,"newText":5}""",
      """{"x":{"a":[1,{"b":null}]},"range":$r,"y":[1,2],"newText":"a","z":null}""",
      """{"range":$r,"newText":"a","newText":"b"}""",
      """{"newText":"a"}""",
      """{"range":$r,"snippet":null}""",
    )
    for (edit in edits) {
      assertLikeTree("""{"jsonrpc":"2.0","id":1,"result":[$edit]}""", ListSerializer(TextEdit.serializer()))
    }
    val documentEdits = listOf(
      """{"textDocument":{"uri":"file:///a.kt","version":3},"edits":[{"range":$r,"newText":"a"}]}""",
      """{"edits":[{"newText":"a","range":$r}],"textDocument":{"version":null,"uri":"file:///a.kt"}}""",
      """{"extra":null,"edits":[],"textDocument":{"uri":"file:///a.kt","version":"4"}}""",
      """{"textDocument":{"uri":"file:///a.kt"}}""",
      """{"textDocument":null,"edits":[]}""",
    )
    for (edit in documentEdits) {
      assertLikeTree("""{"jsonrpc":"2.0","id":1,"result":$edit}""", TextDocumentEdit.serializer())
    }
    val fileChanges = listOf(
      """{"uri":"file:///a.kt","kind":"create","options":{"overwrite":true,"ignoreIfExists":false},"annotationId":"a"}""",
      """{"options":{"ignoreIfExists":true},"kind":"rename","newUri":"file:///b.kt","oldUri":"file:///a.kt"}""",
      """{"uri":"file:///a.kt","options":null,"annotationId":null,"kind":"delete"}""",
      """{"kind":"unknown","uri":"file:///a.kt"}""",
      """{"kind":"create","uri":"file:///a.kt","options":5}""",
    )
    for (change in fileChanges) {
      assertLikeTree("""{"jsonrpc":"2.0","id":1,"result":[$change]}""", ListSerializer(FileChange.serializer()))
    }
    for (data in listOf("[]", "[1,2,3]", """["1",2,"3"]""", "[ 1 , 2 ]", "[null]", "[1.5]", "null", "[2147483648]", "[-0]")) {
      assertLikeTree("""{"jsonrpc":"2.0","id":1,"result":{"resultId":"r","data":$data}}""", SemanticTokens.serializer())
    }
    assertLikeTree(
      """{"jsonrpc":"2.0","id":1,"result":{"resultId":"2","edits":[{"start":0,"deleteCount":5,"data":[2,5,3,0,3]},{"start":10,"deleteCount":1,"data":null},{"start":12,"deleteCount":0}]}}""",
      SemanticTokensDelta.serializer(),
    )
  }

  @Test
  fun `large semantic tokens decode from the text`() {
    val data = List(100_000) { (it * 7919) % 100_003 }
    val body = """{"jsonrpc":"2.0","id":42,"result":{"resultId":"7","data":[${data.joinToString(",")}]}}"""
    val message = decode(body)
    assertEquals(body, message.toString())
    assertEquals(LspWireIncoming.Kind.Response, message.kind)
    val tokens = message.decodeResult(SemanticTokens.serializer())!!
    assertEquals(data, tokens.data)
    assertEquals("7", tokens.resultId)
  }

  // --- lazy tree ---

  @Test
  fun `lazy json equals the parse of the body and is cached`() {
    val body = """ { "jsonrpc" : "2.0" , "id" : 1 , "result" : { "a" : [ 1 , "\u00e9" , null ] , "b" : { } } , "extra" : true } """
    val message = decode(body)
    assertEquals(body, message.toString())
    val tree = message.json()
    assertEquals(parseTree(body), tree)
    assertTrue(tree === message.json(), "cached")
  }

  // --- error messages ---

  @Test
  fun `a parse error of a huge body echoes only the start of the body`() {
    val body = """{"jsonrpc":"2.0","method":"x","params":"${"a".repeat(1 shl 20)}",}"""
    val x = assertFailsWith<IllegalStateException> { decode(body).kind }
    val message = x.message!!
    assertTrue(message.length < 300, "length ${message.length}")
    assertEquals("could not decode json: ${body.take(200)}... (${body.length} chars)", message)
    assertTrue(x.cause != null, "cause kept")
  }

  @Test
  fun `a parse error of a short body echoes the whole body`() {
    val body = """{"jsonrpc":"2.0","method":"ping",}"""
    val x = assertFailsWith<IllegalStateException> { decode(body).kind }
    assertEquals("could not decode json: $body", x.message)
    val atLimit = """{"a":"${"b".repeat(200 - 9)}",}"""
    assertEquals(200, atLimit.length)
    assertEquals("could not decode json: $atLimit", assertFailsWith<IllegalStateException> { decode(atLimit).kind }.message)
  }

  @Test
  fun `a protocol violation of a huge message echoes only the start of the message`() {
    val body = """{"jsonrpc":"1.0","method":"x","params":"${"a".repeat(1 shl 20)}"}"""
    assertEquals(body, wireBody(body).toString())
    val x = assertFails { decode(body) }
    val text = x.message!!
    assertTrue(text.startsWith("not json rpc message: "), text.take(100))
    assertTrue(text.length < 300, "length ${text.length}")
    val short = """{"jsonrpc":"1.0","method":"x"}"""
    assertEquals("not json rpc message: $short", assertFails { decode(short).kind }.message)
  }

  @Test
  fun `a frame body parse error of a huge body echoes only the start of the body`() {
    runTest {
      val body = """{"jsonrpc":"2.0","method":"x","params":"${"a".repeat(1 shl 20)}",}"""
      val frame = "Content-Length: ${body.encodeToByteArray().size}\r\n\r\n$body".encodeToByteArray()
      for (wire in listOf(false, true)) {
        val x = assertFailsWith<IllegalStateException> {
          // the wire reader makes the body; its read (on the `withLsp` loop) fails it
          if (wire) withLspFraming(ChunkedFrameConnection(ChunkedByteReader(frame))) { incoming, _ -> incoming.consumeEach { it.read() } }
          else withBaseProtocolFraming(ChunkedFrameConnection(ChunkedByteReader(frame))) { incoming, _ -> incoming.consumeEach { } }
        }
        assertEquals("could not decode json: ${body.take(200)}... (${body.length} chars)", x.message, "wire $wire")
        assertTrue(x.cause != null, "cause kept")
      }
    }
  }

  // --- helpers ---

  private class ChunkedFrameConnection(override val input: ChunkedByteReader) : LspConnection {
    override val output: ByteWriter = object : ByteWriter {
      override val isClosedForWrite: Boolean get() = true
      override val closedCause: Throwable? get() = null
      override val writeBuffer: Sink = Buffer()
      override suspend fun flush() {}
      override suspend fun flushAndClose() {}
      override fun cancel(cause: Throwable?) {}
    }
    override fun isAlive(): Boolean = true
    override fun close() {}
  }

  private sealed class Outcome {
    object ParseFailure : Outcome()
    object Violation : Outcome()
    object EnvelopeFailure : Outcome()
    data class Message(
      val kind: LspWireIncoming.Kind, val id: StringOrInt?, val method: String?, val error: ResponseError?,
      val payload: Result<Any?>, val json: JsonElement,
    ) : Outcome() {
      override fun equals(other: Any?): Boolean =
        other is Message && kind == other.kind && id == other.id && method == other.method && error == other.error && json == other.json &&
        payload.isSuccess == other.payload.isSuccess && (payload.isFailure || payload.getOrNull() == other.payload.getOrNull())

      override fun hashCode(): Int = kind.hashCode()
    }
  }

  private fun wireBody(body: String): LspWireBody = LspWireCodec.decodeFrameBody(body.encodeToByteArray())

  private fun decode(body: String): LspWireIncoming = wireBody(body).read()

  private fun <T> outcome(body: String, serializer: DeserializationStrategy<T>): Outcome {
    val message = try {
      decode(body)
    }
    catch (x: Exception) {
      return when {
        x is IllegalStateException && x.message!!.startsWith("could not decode json: ") -> Outcome.ParseFailure
        x.message?.startsWith("not json rpc message: ") == true -> Outcome.Violation
        else -> Outcome.EnvelopeFailure
      }
    }
    val kind = message.kind
    val payload = runCatching {
      if (kind == LspWireIncoming.Kind.Response) message.decodeResult(serializer) else message.decodeParams(serializer)
    }
    return Outcome.Message(kind, message.id, message.method, message.error, payload, message.json())
  }

  /** The pre-S5 path: tree parse, envelope decode from the tree, payload from the tree. */
  private fun <T> referenceOutcome(body: String, serializer: DeserializationStrategy<T>): Outcome {
    val tree = runCatching { parseTree(body) }.getOrElse { return Outcome.ParseFailure }
    if (tree !is JsonObject || tree["jsonrpc"] != JsonPrimitive("2.0")) return Outcome.Violation
    val hasId = tree.containsKey("id")
    val hasMethod = tree.containsKey("method")
    return runCatching {
      when {
        hasId && hasMethod -> {
          val request = LSP.json.decodeFromJsonElement(RequestMessage.serializer(), tree)
          Outcome.Message(LspWireIncoming.Kind.Request, request.id, request.method, null,
                          runCatching { request.params?.let { LSP.json.decodeFromJsonElement(serializer, it) } }, tree)
        }
        hasId -> {
          val response = LSP.json.decodeFromJsonElement(ResponseMessage.serializer(), tree)
          Outcome.Message(LspWireIncoming.Kind.Response, response.id, null, response.error,
                          runCatching { response.result?.let { LSP.json.decodeFromJsonElement(serializer, it) } }, tree)
        }
        hasMethod -> {
          val notification = LSP.json.decodeFromJsonElement(NotificationMessage.serializer(), tree)
          Outcome.Message(LspWireIncoming.Kind.Notification, null, notification.method, null,
                          runCatching { notification.params?.let { LSP.json.decodeFromJsonElement(serializer, it) } }, tree)
        }
        else -> Outcome.Violation
      }
    }.getOrElse { Outcome.EnvelopeFailure }
  }

  private fun <T> assertSameAsTree(body: String, serializer: DeserializationStrategy<T>) {
    val expected = referenceOutcome(body, serializer)
    val actual = outcome(body, serializer)
    if (expected != actual) fail("$body:\n expected $expected\n actual   $actual")
  }

  /** Same outcome as the tree path, and the message prints its body text. */
  private fun <T> assertLikeTree(body: String, serializer: DeserializationStrategy<T>) {
    assertSameAsTree(body, serializer)
    assertEquals(body, wireBody(body).toString(), body)
  }

  private fun parseTree(body: String): JsonElement = LSP.json.decodeFromString(JsonElement.serializer(), body)

  private fun <T> permutations(items: List<T>): List<List<T>> =
    if (items.size <= 1) listOf(items)
    else items.flatMap { item -> permutations(items - item).map { listOf(item) + it } }
}

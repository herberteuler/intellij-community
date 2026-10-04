package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.implementation.withLsp
import com.jetbrains.lsp.protocol.Diagnostics
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.PublishDiagnosticsParams
import com.jetbrains.lsp.protocol.SemanticTokens
import com.jetbrains.lsp.protocol.SemanticTokensRequests
import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Payloads decode with [LSP.decodeJson] (`explicitNulls = true`), which is cheaper than [LSP.json] but requires every
 * nullable property of the protocol types to default to `null`: a missing member must decode to `null` exactly like a
 * `null` member does (with a nullable property WITHOUT a default the frame decode would fail with a missing-field error).
 * Encoding stays on [LSP.json], so `null` members are still dropped from the wire.
 */
class ExplicitNullsDecodeTest {

  private val publishDiagnostics = Diagnostics.PublishDiagnosticsNotificationType
  private val semanticTokens = SemanticTokensRequests.SemanticTokensFullRequest
  private val range = """{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}"""

  private fun body(text: String) = LspWireCodec.decodeFrameBody(text.encodeToByteArray()).read()

  @Test
  fun `decodeJson decodes explicit nulls and json does not`() {
    assertTrue(LSP.decodeJson.configuration.explicitNulls)
    assertFalse(LSP.json.configuration.explicitNulls)
    assertEquals(LSP.json.configuration.coerceInputValues, LSP.decodeJson.configuration.coerceInputValues)
    assertEquals(LSP.json.configuration.ignoreUnknownKeys, LSP.decodeJson.configuration.ignoreUnknownKeys)
  }

  @Test
  fun `missing optional nullable params members decode to null`() {
    val message = body(
      """{"jsonrpc":"2.0","method":"${publishDiagnostics.method}","params":{"uri":"file:///a.kt","diagnostics":[{"range":$range,"message":"m"}]}}""",
    )
    val params = assertNotNull(message.decodeParams(publishDiagnostics.paramsSerializer))
    assertNull(params.version)
    val diagnostic = params.diagnostics.single()
    assertEquals("m", diagnostic.message)
    assertNull(diagnostic.severity)
    assertNull(diagnostic.code)
    assertNull(diagnostic.source)
    assertNull(diagnostic.data)
  }

  @Test
  fun `null optional nullable params members decode to null`() {
    val message = body(
      """{"jsonrpc":"2.0","method":"${publishDiagnostics.method}","params":{"uri":"file:///a.kt","version":null,"diagnostics":[{"range":$range,"message":"m","severity":null,"code":null,"source":null,"data":null}]}}""",
    )
    val params = assertNotNull(message.decodeParams(publishDiagnostics.paramsSerializer))
    assertNull(params.version)
    val diagnostic = params.diagnostics.single()
    assertNull(diagnostic.severity)
    assertNull(diagnostic.code)
    assertNull(diagnostic.source)
    assertNull(diagnostic.data)
  }

  @Test
  fun `missing and null optional nullable result members decode to null`() {
    val missing = body("""{"jsonrpc":"2.0","id":1,"result":{"data":[1,2,3]}}""").decodeResult(semanticTokens.resultSerializer)
    assertNotNull(missing)
    assertNull(missing.resultId)
    assertEquals(listOf(1, 2, 3), missing.data)

    val explicit = body("""{"jsonrpc":"2.0","id":1,"result":{"resultId":null,"data":[1,2,3]}}""").decodeResult(semanticTokens.resultSerializer)
    assertNotNull(explicit)
    assertNull(explicit.resultId)
    assertEquals(missing, explicit)
  }

  @Test
  fun `error envelope without data and with null data decodes`() {
    val missing = body("""{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"bad"}}""").error
    assertNotNull(missing)
    assertEquals(-32600, missing.code)
    assertNull(missing.data)

    val explicit = body("""{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"bad","data":null}}""").error
    assertNotNull(explicit)
    assertEquals(missing, explicit)
  }

  @Test
  fun `both Json configurations decode the same values`() {
    val payload = """{"uri":"file:///a.kt","diagnostics":[{"range":$range,"message":"m","severity":2},{"range":$range,"message":"n","code":"C","source":null}]}"""
    val plain = LSP.json.decodeFromString(PublishDiagnosticsParams.serializer(), payload)
    val explicit = LSP.decodeJson.decodeFromString(PublishDiagnosticsParams.serializer(), payload)
    assertEquals(plain, explicit)
    assertEquals(plain, body("""{"jsonrpc":"2.0","method":"${publishDiagnostics.method}","params":$payload}""").decodeParams(publishDiagnostics.paramsSerializer))
  }

  @Test
  fun `decodeJson fails on a missing nullable member without a default and json does not`() {
    assertFailsWith<MissingFieldException> { LSP.decodeJson.decodeFromString(ReportSerializer, """{"message":"m"}""") }
    assertEquals(Report("m", null), LSP.json.decodeFromString(ReportSerializer, """{"message":"m"}"""))
  }

  @Test
  fun `missing nullable member without a default decodes to null on demand`() {
    val reports = ListSerializer(ReportSerializer)
    val result = body("""{"jsonrpc":"2.0","id":1,"result":[{"message":"m"},{"message":"n","exceptionClass":"E"}]}""").decodeResult(reports)
    assertEquals(listOf(Report("m", null), Report("n", "E")), result)

    val data = body("""{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"bad","data":{"message":"m"}}}""").decodeErrorData(ReportSerializer)
    assertEquals(Report("m", null), data)

    // a member that is really missing still fails
    assertFailsWith<MissingFieldException> { body("""{"jsonrpc":"2.0","id":1,"result":[{"exceptionClass":"E"}]}""").decodeResult(reports) }
  }

  @Test
  fun `missing nullable member without a default decodes to null in the envelope pass`() = runTest {
    val report = NotificationType("t/report", ReportSerializer)
    val toServer = Channel<LspWireBody>(Channel.UNLIMITED)
    val received = Channel<Report>(Channel.UNLIMITED)
    val server = launch {
      withLsp(toServer, Channel<LspWireOutgoing>(Channel.UNLIMITED), lspHandlers {
        notification(report) { received.send(it) }
      }) { awaitCancellation() }
    }
    val frame = LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0","method":"t/report","params":{"message":"m"}}""".encodeToByteArray())
    toServer.send(frame)
    val message = frame.read()
    assertEquals(Report("m", null), received.receive())
    assertEquals(Report("m", null), message.decodeParams(report.paramsSerializer))
    server.cancelAndJoin()
  }

  @Test
  fun `encoding still drops null members`() {
    val frame = LspWireCodec.encodeResultFrame(StringOrInt.int(1), semanticTokens.resultSerializer, SemanticTokens(resultId = null, data = listOf(1)))
    val text = frame.frame().decodeToString()
    assertTrue(text.endsWith("""{"jsonrpc":"2.0","id":1,"result":{"data":[1]}}"""), text)
    assertEquals("""{"data":[1]}""", LSP.json.encodeToString(SemanticTokens.serializer(), SemanticTokens(resultId = null, data = listOf(1))))
  }
}

/** A type outside the protocol module whose nullable property has no `null` default, like the LS `LoggedError`. */
private data class Report(val message: String, val exceptionClass: String?)

/** What the serialization plugin generates for [Report]: a nullable member with no default is required. */
private object ReportSerializer : KSerializer<Report> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("test.Report") {
    element("message", String.serializer().descriptor)
    element("exceptionClass", String.serializer().nullable.descriptor)
  }

  override fun serialize(encoder: Encoder, value: Report) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, 0, value.message)
      encodeNullableSerializableElement(descriptor, 1, String.serializer(), value.exceptionClass)
    }
  }

  override fun deserialize(decoder: Decoder): Report = decoder.decodeStructure(descriptor) {
    var message: String? = null
    var exceptionClass: String? = null
    var seenExceptionClass = false
    while (true) {
      when (val index = decodeElementIndex(descriptor)) {
        CompositeDecoder.DECODE_DONE -> break
        0 -> message = decodeStringElement(descriptor, 0)
        1 -> {
          exceptionClass = decodeNullableSerializableElement(descriptor, 1, String.serializer().nullable)
          seenExceptionClass = true
        }
        else -> throw SerializationException("unexpected element $index")
      }
    }
    val missing = listOfNotNull("message".takeIf { message == null }, "exceptionClass".takeIf { !seenExceptionClass })
    if (missing.isNotEmpty()) throw MissingFieldException(missing, descriptor.serialName)
    Report(message!!, exceptionClass)
  }
}

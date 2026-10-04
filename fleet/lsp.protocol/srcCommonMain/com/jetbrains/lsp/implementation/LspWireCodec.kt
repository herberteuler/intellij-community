package com.jetbrains.lsp.implementation

import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.ResponseError
import com.jetbrains.lsp.protocol.StringOrInt
import fleet.multiplatform.shims.MultiplatformConcurrentHashMap
import fleet.util.decodeToStringUtf8
import fleet.util.encodeToByteArrayUtf8
import fleet.util.logging.KLoggers
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.annotations.ApiStatus

internal class ProtocolViolation(message: String) : Exception(message)

private const val ERROR_TEXT_LIMIT = 200

/**
 * [text] for an error message: as is up to [ERROR_TEXT_LIMIT] chars, else its start and its length. A body can be
 * megabytes; the whole text in a message would be copied into every log line and report that prints it.
 */
internal fun errorText(text: String): String =
  if (text.length <= ERROR_TEXT_LIMIT) text else "${text.take(ERROR_TEXT_LIMIT)}... (${text.length} chars)"

/**
 * [decode] with [LSP.decodeJson], or again with [LSP.json] after a [MissingFieldException]: [LSP.decodeJson] needs a
 * `null` default on every nullable property, which the protocol types have but extension types outside this module may
 * not. A member that is really missing fails both ways, with the [LSP.json] error and the first one suppressed.
 * Each retry reason is logged once ([logPlainRetry]): such a type costs two passes and should get its `null` defaults.
 * A retry counts in [stats] when given.
 */
@OptIn(ExperimentalSerializationApi::class)
internal inline fun <T> decodeOrPlain(stats: LspWireStats? = null, decode: (Json) -> T): T =
  try {
    decode(LSP.decodeJson)
  }
  catch (x: MissingFieldException) {
    logPlainRetry(x)
    if (stats != null) stats.retries++
    try {
      decode(LSP.json)
    }
    catch (y: Throwable) {
      y.addSuppressed(x)
      throw y
    }
  }

private val LOG = KLoggers.logger("com.jetbrains.lsp.implementation.LspWireCodec")

/** The messages logged by [logPlainRetry]: one per type and missing fields, so a bounded set. */
private val loggedPlainRetries = MultiplatformConcurrentHashMap<String, Unit>()

@OptIn(ExperimentalSerializationApi::class)
internal fun logPlainRetry(x: MissingFieldException) {
  val message = x.message ?: x.missingFields.toString()
  if (loggedPlainRetries.putIfAbsent(message, Unit) == null) {
    LOG.warn { "decodeOrPlain: retrying with LSP.json, a nullable property has no null default: $message" }
  }
}

/**
 * The LSP wire codec: frame body bytes <-> JSON-RPC message, with [LSP.json] (encode) and [LSP.decodeJson] (payload decode,
 * [decodeOrPlain]).
 *
 * [withBaseProtocolFraming] and [withLsp] go through it, so this is the one place where wire JSON is read and written.
 * The header parsing and the transport stay in `protocolFraming.kt`.
 *
 * NOT A STABLE API. It is public only so that benchmarks in other modules measure the real codec. Its shape and its
 * insides change without notice.
 */
@ApiStatus.Internal
object LspWireCodec {
  /**
   * Decodes a frame body (the bytes after the header) into its text. Nothing is parsed here: [LspWireBody.read] reads the
   * envelope (with the payload, when its serializer is known), else the payload is read on demand
   * ([LspWireIncoming.decodeParams], [LspWireIncoming.decodeResult]). A body that is not JSON fails that read, not this
   * call.
   */
  fun decodeFrameBody(body: ByteArray): LspWireBody = LspWireBody.fromText(body.decodeToStringUtf8())

  /** Encodes a request: one frame ([LspWireOutgoing]). */
  fun <T> encodeRequestFrame(id: StringOrInt, method: String, serializer: SerializationStrategy<T>, params: T): LspWireOutgoing =
    encodeEnvelope(OutgoingEnvelope(id, method, OutgoingEnvelopeSerializer.PARAMS, serializer, params))

  /** Encodes a notification: one frame ([LspWireOutgoing]). */
  fun <T> encodeNotificationFrame(method: String, serializer: SerializationStrategy<T>, params: T): LspWireOutgoing =
    encodeEnvelope(OutgoingEnvelope(null, method, OutgoingEnvelopeSerializer.PARAMS, serializer, params))

  /**
   * Encodes a successful response: one frame ([LspWireOutgoing]). A `null` [result] is sent as `"result":null`:
   * JSON-RPC requires the member on success.
   */
  fun <T> encodeResultFrame(id: StringOrInt, serializer: SerializationStrategy<T>, result: T): LspWireOutgoing =
    encodeEnvelope(OutgoingEnvelope(id, null, OutgoingEnvelopeSerializer.RESULT, serializer, result))

  /** Encodes an error response: one frame ([LspWireOutgoing]). */
  fun encodeErrorFrame(id: StringOrInt, error: ResponseError): LspWireOutgoing =
    encodeEnvelope(OutgoingEnvelope(id, null, OutgoingEnvelopeSerializer.ERROR, ResponseError.serializer(), error))

  /**
   * The body is one [LSP.json] pass over [envelope] ([OutgoingEnvelopeSerializer]), then UTF-8 once; the frame writer
   * writes the header and that array, no frame array is built, no JSON tree either.
   */
  private fun encodeEnvelope(envelope: OutgoingEnvelope<*>): LspWireOutgoing =
    LspWireOutgoing.ofBody(LSP.json.encodeToString(OutgoingEnvelopeSerializer, envelope).encodeToByteArrayUtf8())

  /** `Content-Length: <bodySize>\r\n\r\n`: the protocol counts the body in bytes. */
  internal fun contentLengthHeader(bodySize: Int): ByteArray = "Content-Length: $bodySize\r\n\r\n".encodeToByteArrayUtf8()

  /** The tree of a frame body, for the `JsonElement` channels of [withBaseProtocolFraming]. */
  internal fun decodeBody(body: ByteArray): JsonElement {
    val jsonStr = body.decodeToStringUtf8()
    return try {
      LSP.json.decodeFromString(JsonElement.serializer(), jsonStr)
    }
    catch (x: Throwable) {
      throw IllegalStateException("could not decode json: ${errorText(jsonStr)}", x)
    }
  }

  /** The frame of a tree, for the `JsonElement` channels of [withBaseProtocolFraming]. */
  internal fun encodeFrame(message: JsonElement): ByteArray {
    val body = LSP.json.encodeToString(JsonElement.serializer(), message).encodeToByteArrayUtf8()
    val header = contentLengthHeader(body.size)
    val frame = header.copyOf(header.size + body.size)
    body.copyInto(frame, header.size)
    return frame
  }

  internal fun <T> encodeValue(serializer: SerializationStrategy<T>, value: T): JsonElement =
    LSP.json.encodeToJsonElement(serializer, value)

  internal fun <T> decodeValue(serializer: DeserializationStrategy<T>, value: JsonElement): T =
    decodeOrPlain { it.decodeFromJsonElement(serializer, value) }
}

/**
 * An outgoing message for [OutgoingEnvelopeSerializer]: [id] and [method] when present, and the payload [value] under
 * the [member] index of [OutgoingEnvelopeSerializer.descriptor] (`params`, `result` or `error`), written by [serializer].
 */
internal class OutgoingEnvelope<T>(
  val id: StringOrInt?,
  val method: String?,
  val member: Int,
  private val serializer: SerializationStrategy<T>,
  private val value: T,
) {
  /**
   * Writes the payload member. Not the nullable element variant: with `explicitNulls = false` that one drops a `null`,
   * this one lets [serializer] write it, so a `null` result goes out as `"result":null`.
   */
  fun encodePayload(encoder: CompositeEncoder, descriptor: SerialDescriptor) {
    encoder.encodeSerializableElement(descriptor, member, serializer, value)
  }
}

/**
 * The JSON-RPC envelope writer: `jsonrpc`, then `id` and `method` when present, then the payload member, in that order.
 * Encode only, with [LSP.json] (never [LSP.decodeJson]: it would write `null` for absent members of the payload).
 */
internal object OutgoingEnvelopeSerializer : SerializationStrategy<OutgoingEnvelope<*>> {
  const val JSONRPC: Int = 0
  const val ID: Int = 1
  const val METHOD: Int = 2
  const val PARAMS: Int = 3
  const val RESULT: Int = 4
  const val ERROR: Int = 5

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.implementation.OutgoingEnvelope") {
    element("jsonrpc", String.serializer().descriptor)
    element("id", StringOrInt.serializer().descriptor, isOptional = true)
    element("method", String.serializer().descriptor, isOptional = true)
    // the payload descriptors are the caller's; kotlinx does not read element descriptors when it encodes
    element("params", JsonElement.serializer().descriptor, isOptional = true)
    element("result", JsonElement.serializer().descriptor, isOptional = true)
    element("error", ResponseError.serializer().descriptor, isOptional = true)
  }

  override fun serialize(encoder: Encoder, value: OutgoingEnvelope<*>) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, JSONRPC, "2.0")
      value.id?.let { encodeSerializableElement(descriptor, ID, StringOrInt.serializer(), it) }
      value.method?.let { encodeStringElement(descriptor, METHOD, it) }
      value.encodePayload(this, descriptor)
    }
  }
}

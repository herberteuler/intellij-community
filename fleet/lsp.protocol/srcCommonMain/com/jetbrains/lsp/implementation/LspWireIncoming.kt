@file:OptIn(ExperimentalSerializationApi::class)

package com.jetbrains.lsp.implementation

import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.ResponseError
import com.jetbrains.lsp.protocol.StringOrInt
import com.jetbrains.lsp.protocol.ignoreUnknownKeys
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.annotations.ApiStatus
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Counters of the payload decoding of one `withLsp` session, to see the fast path. The envelope pass ([LspWireBody.read]
 * with the session's resolver) counts with plain int increments on the read loop; nothing is allocated per message.
 * The session logs them at DEBUG when it ends. A test reads them by running the session in a context with its own
 * instance: `withContext(LspWireStats()) { withLsp(...) }`, one instance per session.
 *
 * NOT A STABLE API, see [LspWireCodec].
 */
@ApiStatus.Internal
class LspWireStats : AbstractCoroutineContextElement(LspWireStats) {
  companion object Key : CoroutineContext.Key<LspWireStats>

  /** Payloads decoded typed in the envelope pass: their `decodeParams` / `decodeResult` with that serializer is free. */
  var firstPassHits: Int = 0
    internal set

  private val secondPassCount = AtomicInt(0)

  /**
   * Payloads the session decoded with a second pass over the text: the envelope pass did not decode them with the
   * handler's serializer (`params` before `method`, `result` before `id`, or no payload member at all). Counted where
   * the handlers decode, off the loop too, so this one is atomic; it is only touched on the slow path.
   */
  val secondPasses: Int get() = secondPassCount.load()

  internal fun countSecondPass() {
    secondPassCount.incrementAndFetch()
  }

  /** Envelope passes run again: the envelope alone after a payload failed, or [decodeOrPlain] with [LSP.json] after a missing `null` default. */
  var retries: Int = 0
    internal set

  override fun toString(): String = "firstPassHits=$firstPassHits, secondPasses=$secondPasses, retries=$retries"
}

/** A [LspPayloadResolver] that also counts the envelope passes of its session. */
internal interface CountingPayloadResolver : LspPayloadResolver {
  val stats: LspWireStats
}

/**
 * The body of an incoming JSON-RPC message as received: its text, and the whole message as a tree on demand ([json]).
 * Nothing is decoded until [read], which gives the message ([LspWireIncoming]). Framing and the traffic tee deal with
 * bodies only; the read loop of `withLsp` reads each body once, with its [LspPayloadResolver].
 *
 * NOT A STABLE API, see [LspWireCodec].
 */
@ApiStatus.Internal
class LspWireBody private constructor(internal val text: String) {
  /** The tree of [json], once parsed. A race parses twice and keeps either tree; both are equal. */
  @Volatile
  private var tree: JsonElement? = null

  /** The whole message as a JSON tree: parsed from the text on the first call, then cached. */
  fun json(): JsonElement = tree ?: parse(text).also { tree = it }

  /** [read] with no resolver: every payload is decoded on demand. */
  fun read(): LspWireIncoming = LspWireIncoming.read(this, null)

  /**
   * The message: the envelope, and with [resolver] the payloads it knows, typed, in the same pass ([LspWireIncoming]).
   * Each call reads the text again.
   *
   * @throws ProtocolViolation when this is not a JSON-RPC 2.0 request, response or notification.
   * @throws IllegalStateException `could not decode json: ...` ([BodyNotJsonException]) when the body is not JSON.
   */
  internal fun read(resolver: LspPayloadResolver?): LspWireIncoming = LspWireIncoming.read(this, resolver)

  /** The body text as received. */
  override fun toString(): String = text

  internal companion object {
    /** A body from its text; nothing is decoded until [read]. */
    fun fromText(text: String): LspWireBody = LspWireBody(text)

    /** A body from a tree: its compact text. */
    fun fromJson(message: JsonElement): LspWireBody = LspWireBody(message.toString())

    /** The tree parse of the old `decodeBody`, with its error. */
    private fun parse(text: String): JsonElement =
      try {
        LSP.json.decodeFromString(JsonElement.serializer(), text)
      }
      catch (x: Throwable) {
        throw BodyNotJsonException("could not decode json: ${errorText(text)}", x)
      }
  }
}

/**
 * The body is not JSON ([LspWireBody.json], [LspWireBody.read]): the frames are out of step with the messages, so the
 * `withLsp` loop ends the session on it, as the tree framing does. It is the `IllegalStateException` of the old path.
 */
internal class BodyNotJsonException(message: String, cause: Throwable) : IllegalStateException(message, cause)

/**
 * What the `withLsp` loop can still tell of a body whose read failed ([hintOf]): its [id] when it is readable, and
 * whether it has a `method` member (of any value).
 */
internal class EnvelopeHint(val id: StringOrInt?, val hasMethod: Boolean) {
  companion object {
    private val NONE = EnvelopeHint(null, false)

    /**
     * The `id` and `method` members of [body] alone, the others skipped, then `id` as a [StringOrInt]. Anything that
     * fails gives no id. For the failure path only: one more pass over the text.
     */
    fun hintOf(body: LspWireBody): EnvelopeHint {
      val members = try {
        LSP.decodeJson.decodeFromString(IdMethodReader, body.text)
      }
      catch (_: Exception) {
        return NONE
      }
      val id = members.id?.takeIf { it != JsonNull }?.let {
        try {
          LSP.decodeJson.decodeFromJsonElement(StringOrInt.serializer(), it)
        }
        catch (_: Exception) {
          null
        }
      }
      return EnvelopeHint(id, members.method != null)
    }
  }
}

private class IdMethod(val id: JsonElement?, val method: JsonElement?)

/** The `id` and `method` members of an envelope as trees; the others are skipped. */
private object IdMethodReader : DeserializationStrategy<IdMethod> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.Envelope.id.method") {
    element("id", JsonElement.serializer().descriptor)
    element("method", JsonElement.serializer().descriptor)
    annotations = ignoreUnknownKeys
  }

  override fun deserialize(decoder: Decoder): IdMethod = decoder.decodeStructure(descriptor) {
    var id: JsonElement? = null
    var method: JsonElement? = null
    while (true) {
      when (val index = decodeElementIndex(descriptor)) {
        CompositeDecoder.DECODE_DONE -> break
        0 -> id = decodeSerializableElement(descriptor, 0, JsonElement.serializer())
        1 -> method = decodeSerializableElement(descriptor, 1, JsonElement.serializer())
        else -> throw SerializationException("unexpected element $index of ${descriptor.serialName}")
      }
    }
    IdMethod(id, method)
  }
}

/**
 * An incoming JSON-RPC message, read from its body by [LspWireBody.read]: the envelope ([kind], [id], [method],
 * [error]) decoded by kotlinx, with no JSON tree and no text scan of our own.
 *
 * That read is one kotlinx pass over the whole body. With a [LspPayloadResolver] (the one of `withLsp`, on its read
 * loop), a payload (`params` or `result`) that comes after `method` (or after `id`, for a response) and whose
 * serializer the resolver knows is decoded typed in the same pass; [decodeParams] and [decodeResult] with that
 * serializer return it. Every other payload (no resolver, payload before `method` or `id`, unknown method or id, another
 * serializer) is skipped by that pass and decoded on demand by a second kotlinx pass over the text. A payload that
 * fails in the first pass does not fail the envelope: the envelope is read again without it, and [decodeParams] or
 * [decodeResult] throws the failure. [json] is the whole message as a tree, parsed from the text on the first call.
 *
 * Not an object or `jsonrpc` not `"2.0"` is a protocol violation; `id` and `method` make a request, `id` alone a
 * response, `method` alone a notification. A body that is not JSON fails the read with
 * `IllegalStateException("could not decode json: ...")`.
 *
 * Cost bound: the read is one envelope pass, plus at most one envelope-only pass (the payload failed), one
 * [decodeOrPlain] retry, and one `jsonrpc`-only pass or tree parse (a bad envelope): at most 4 passes, whatever the
 * peer sends. A payload decoded on demand adds one pass.
 *
 * NOT A STABLE API, see [LspWireCodec].
 */
@ApiStatus.Internal
class LspWireIncoming private constructor(
  private val body: LspWireBody,
  /** What the message is. */
  val kind: Kind,
  /** The id of a request or a response; `null` for a notification. */
  val id: StringOrInt?,
  /** The method of a request or a notification; `null` for a response. */
  val method: String?,
  /** The error of a response; `null` for a successful response and for requests and notifications. */
  val error: ResponseError?,
  private val params: Typed?,
  private val result: Typed?,
) {
  enum class Kind { Request, Response, Notification }

  /** The whole message as a JSON tree: parsed from the body on the first call, then cached. */
  fun json(): JsonElement = body.json()

  /** @return the params of a request or a notification, or `null` when it has none (absent or `null`). */
  fun <T> decodeParams(serializer: DeserializationStrategy<T>): T? {
    check(kind != Kind.Response) { "a response has no params" }
    return decodePayload(serializer, params, Member.PARAMS)
  }

  /** @return the result of a response, or `null` when it has none (absent or `null`). */
  fun <T> decodeResult(serializer: DeserializationStrategy<T>): T? {
    check(kind == Kind.Response) { "only a response has a result" }
    return decodePayload(serializer, result, Member.RESULT)
  }

  /** @return the error data, or `null` when there is no error or it has no data. */
  fun <T> decodeErrorData(serializer: DeserializationStrategy<T>): T? =
    error?.data?.let { LspWireCodec.decodeValue(serializer, it) }

  /** The body text as received. */
  override fun toString(): String = body.text

  /** Whether [decodeParams] (or [decodeResult], for a response) with [serializer] returns what the envelope pass decoded. */
  internal fun decodedInEnvelopePass(serializer: DeserializationStrategy<*>): Boolean {
    val typed = if (kind == Kind.Response) result else params
    return typed != null && typed.serializer === serializer
  }

  /** The value of the first pass when it used [serializer], else a second pass over the text for [member] alone. */
  private fun <T> decodePayload(serializer: DeserializationStrategy<T>, typed: Typed?, member: Member): T? {
    if (typed != null && typed.serializer === serializer) {
      typed.failure?.let { throw it }
      @Suppress("UNCHECKED_CAST")
      return typed.value as T?
    }
    return decodeMember(body.text, member, serializer)
  }

  internal companion object {
    /**
     * The envelope of [body], and the payloads [resolver] knows, in one pass. A payload that fails leaves the envelope
     * to a pass without payloads, and its failure to [decodePayload].
     */
    fun read(body: LspWireBody, resolver: LspPayloadResolver?): LspWireIncoming {
      if (resolver == null) return envelopeOnly(body).toMessage(body)
      val stats = (resolver as? CountingPayloadResolver)?.stats
      val reader = EnvelopeReader(resolver)
      val raw = try {
        decodeOrPlain(stats) { it.decodeFromString(reader, body.text) }
      }
      catch (x: Exception) {
        if (stats != null) stats.retries++
        val envelope = envelopeOnly(body)
        when (reader.failedMember) {
          null -> {}
          Member.PARAMS -> envelope.params = Typed(reader.failedSerializer!!, null, x)
          Member.RESULT -> envelope.result = Typed(reader.failedSerializer!!, null, x)
        }
        return envelope.toMessage(body)
      }
      if (stats != null) {
        if (raw.params != null) stats.firstPassHits++
        if (raw.result != null) stats.firstPassHits++
      }
      return raw.toMessage(body)
    }

    /**
     * The envelope with every payload skipped. When kotlinx fails, the error is the one of the old tree path: not JSON
     * is the parse error; JSON that is not an object or has no `jsonrpc` `"2.0"` is a protocol violation; else (a member
     * of the wrong type) the kotlinx error itself. An object is first read again for `jsonrpc` alone, the rest skipped
     * with no tree; only a body that is not an object, or that this pass cannot read either (malformed), goes to the tree.
     */
    private fun envelopeOnly(body: LspWireBody): RawEnvelope {
      val text = body.text
      try {
        return LSP.decodeJson.decodeFromString(EnvelopeReader(null), text)
      }
      catch (x: Exception) {
        if (startsAsObject(text)) {
          val jsonrpc = try {
            LSP.decodeJson.decodeFromString(JsonrpcReader, text)
          }
          catch (_: Exception) {
            JsonrpcReader.FAILED
          }
          if (jsonrpc !== JsonrpcReader.FAILED) {
            if (jsonrpc != JSONRPC_20) violation(text)
            throw x
          }
        }
        val tree = body.json()
        if (tree !is JsonObject || tree["jsonrpc"] != JSONRPC_20) violation(text)
        throw x
      }
    }

    private fun RawEnvelope.toMessage(body: LspWireBody): LspWireIncoming {
      if (jsonrpc != JSONRPC_20) violation(body.text)
      val id = id
      val method = method
      return when {
        id != null && method != null -> LspWireIncoming(body, Kind.Request, id, method, null, params, result)
        id != null -> {
          val error = error?.takeIf { it != JsonNull }?.let { LSP.decodeJson.decodeFromJsonElement(ResponseError.serializer(), it) }
          LspWireIncoming(body, Kind.Response, id, null, error, params, result)
        }
        method != null -> LspWireIncoming(body, Kind.Notification, null, method, null, params, result)
        else -> violation(body.text)
      }
    }

    /** The message in the error is the start of the body [text], with no tree parse. */
    private fun violation(text: String): Nothing = throw ProtocolViolation("not json rpc message: ${errorText(text)}")
  }
}

private val JSONRPC_20 = JsonPrimitive("2.0")

/** The first non-whitespace char of [text] opens an object. */
private fun startsAsObject(text: String): Boolean {
  for (c in text) {
    if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return c == '{'
  }
  return false
}

/** The `jsonrpc` member of an envelope alone; the other members are skipped, with no tree. */
private object JsonrpcReader : DeserializationStrategy<JsonElement?> {
  /** Not a value of `jsonrpc`: the pass failed. */
  val FAILED: JsonElement = JsonObject(emptyMap())

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.Envelope.jsonrpc") {
    element("jsonrpc", JsonElement.serializer().descriptor)
    annotations = ignoreUnknownKeys
  }

  override fun deserialize(decoder: Decoder): JsonElement? = decoder.decodeStructure(descriptor) {
    var jsonrpc: JsonElement? = null
    while (true) {
      when (val index = decodeElementIndex(descriptor)) {
        CompositeDecoder.DECODE_DONE -> break
        0 -> jsonrpc = decodeSerializableElement(descriptor, 0, JsonElement.serializer())
        else -> throw SerializationException("unexpected element $index of ${descriptor.serialName}")
      }
    }
    jsonrpc
  }
}

/** The top-level payload members. */
private enum class Member(val key: String) {
  PARAMS("params"),
  RESULT("result");

  /** The descriptor of the second pass: this member alone, the other keys skipped. */
  val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.Payload.$key") {
    element(key, PAYLOAD_PLACEHOLDER)
    annotations = ignoreUnknownKeys
  }
}

/**
 * The element descriptor of a payload member. The decoder looks only at its name; the value is decoded by the
 * serializer given at decode time. Elements are not optional, so `coerceInputValues` never skips one.
 */
private val PAYLOAD_PLACEHOLDER: SerialDescriptor = JsonElement.serializer().descriptor

/** A payload decoded by the first pass with [serializer]: its [value], or its [failure]. */
private class Typed(val serializer: DeserializationStrategy<*>, val value: Any?, val failure: Exception?)

private class Decoded(
  val kind: LspWireIncoming.Kind,
  val id: StringOrInt?,
  val method: String?,
  val error: ResponseError?,
  val params: Typed?,
  val result: Typed?,
) {
  fun withFailure(member: Member, failure: Typed): Decoded = when (member) {
    Member.PARAMS -> Decoded(kind, id, method, error, failure, null)
    Member.RESULT -> Decoded(kind, id, method, error, null, failure)
  }
}

/** What the envelope pass read; a repeated member keeps the last value, like the tree. */
private class RawEnvelope {
  var jsonrpc: JsonElement? = null
  var id: StringOrInt? = null
  var method: String? = null
  var error: JsonElement? = null
  var params: Typed? = null
  var result: Typed? = null
}

/**
 * The envelope members, plus the payload members whose serializer is known: the decoder skips a key that is not in the
 * descriptor it gets ([CompositeDecoder.decodeElementIndex] takes the descriptor per call), so a payload is skipped
 * until `method` or `id` tells its serializer, and decoded in place after. A payload whose serializer is not known is
 * there under a name no peer sends, so the indices are the same in every shape and the error path of a payload (built
 * from the descriptor of `beginStructure`, [BOTH]) names the right member.
 */
internal class EnvelopeShape private constructor(params: Boolean, result: Boolean, name: String) {
  val descriptor: SerialDescriptor = buildClassSerialDescriptor(name) {
    element("jsonrpc", JsonElement.serializer().descriptor)
    element("id", StringOrInt.serializer().descriptor)
    element("method", String.serializer().descriptor)
    element("error", JsonElement.serializer().descriptor)
    element(if (params) Member.PARAMS.key else "\u0000${Member.PARAMS.key}", PAYLOAD_PLACEHOLDER)
    element(if (result) Member.RESULT.key else "\u0000${Member.RESULT.key}", PAYLOAD_PLACEHOLDER)
    annotations = ignoreUnknownKeys
  }

  companion object {
    const val JSONRPC: Int = 0
    const val ID: Int = 1
    const val METHOD: Int = 2
    const val ERROR: Int = 3
    const val PARAMS: Int = 4
    const val RESULT: Int = 5

    private val NONE = EnvelopeShape(false, false, "com.jetbrains.lsp.Envelope")
    private val PARAMS_ONLY = EnvelopeShape(true, false, "com.jetbrains.lsp.Envelope.params")
    private val RESULT_ONLY = EnvelopeShape(false, true, "com.jetbrains.lsp.Envelope.result")
    val BOTH: EnvelopeShape = EnvelopeShape(true, true, "com.jetbrains.lsp.Envelope.params.result")

    fun of(params: Boolean, result: Boolean): EnvelopeShape = when {
      params && result -> BOTH
      params -> PARAMS_ONLY
      result -> RESULT_ONLY
      else -> NONE
    }
  }
}

/**
 * One envelope pass. With a [resolver], the payload serializers are looked up as soon as `method` (params) or `id`
 * with no `method` (result) is read.
 */
private class EnvelopeReader(private val resolver: LspPayloadResolver?) : DeserializationStrategy<RawEnvelope> {
  override val descriptor: SerialDescriptor get() = EnvelopeShape.BOTH.descriptor

  /** The payload being decoded when the pass failed, and its resolved serializer. */
  var failedMember: Member? = null
  var failedSerializer: DeserializationStrategy<*>? = null

  override fun deserialize(decoder: Decoder): RawEnvelope {
    val raw = RawEnvelope()
    decoder.decodeStructure(EnvelopeShape.BOTH.descriptor) {
      var params: DeserializationStrategy<*>? = null
      var result: DeserializationStrategy<*>? = null
      var paramsMethod: String? = null
      var paramsRequest = false
      var resultResolved = false
      while (true) {
        if (resolver != null) {
          val method = raw.method
          val id = raw.id
          if (method != null && (method !== paramsMethod || paramsRequest != (id != null))) {
            paramsMethod = method
            paramsRequest = id != null
            params = resolver.paramsSerializer(method, paramsRequest)
          }
          if (method != null) {
            result = null
          }
          else if (id != null && !resultResolved) {
            resultResolved = true
            result = resolver.resultSerializer(id)
          }
        }
        val shape = EnvelopeShape.of(params != null, result != null)
        val d = shape.descriptor
        when (val index = decodeElementIndex(d)) {
          CompositeDecoder.DECODE_DONE -> break
          EnvelopeShape.JSONRPC -> raw.jsonrpc = decodeSerializableElement(d, index, JsonElement.serializer())
          EnvelopeShape.ID -> raw.id = decodeSerializableElement(d, index, StringOrInt.serializer())
          EnvelopeShape.METHOD -> raw.method = decodeStringElement(d, index)
          EnvelopeShape.ERROR -> raw.error = decodeSerializableElement(d, index, JsonElement.serializer())
          // a payload index with no serializer is the placeholder name itself, sent by the peer: an unknown key
          EnvelopeShape.PARAMS -> if (params != null) raw.params = typed(d, index, Member.PARAMS, params) else skip(d, index)
          EnvelopeShape.RESULT -> if (result != null) raw.result = typed(d, index, Member.RESULT, result) else skip(d, index)
          else -> throw SerializationException("unexpected element $index of ${d.serialName}")
        }
      }
    }
    return raw
  }

  private fun CompositeDecoder.skip(d: SerialDescriptor, index: Int) {
    decodeSerializableElement(d, index, JsonElement.serializer())
  }

  /** `null` gives `null` without running [serializer], like the tree path (`"params":null` and absent are the same). */
  private fun CompositeDecoder.typed(d: SerialDescriptor, index: Int, member: Member, serializer: DeserializationStrategy<*>): Typed {
    failedMember = member
    failedSerializer = serializer
    @Suppress("UNCHECKED_CAST")
    val value = decodeNullableSerializableElement(d, index, serializer as DeserializationStrategy<Any?>)
    failedMember = null
    failedSerializer = null
    return Typed(serializer, value, null)
  }
}

/** The second pass: [member] alone, decoded with [inner]; the envelope members are skipped. */
private class PayloadReader(private val member: Member, private val inner: DeserializationStrategy<*>) : DeserializationStrategy<Any?> {
  override val descriptor: SerialDescriptor get() = member.descriptor

  override fun deserialize(decoder: Decoder): Any? = decoder.decodeStructure(member.descriptor) {
    var value: Any? = null
    while (true) {
      when (val index = decodeElementIndex(member.descriptor)) {
        CompositeDecoder.DECODE_DONE -> break
        0 -> {
          @Suppress("UNCHECKED_CAST")
          value = decodeNullableSerializableElement(member.descriptor, 0, inner as DeserializationStrategy<Any?>)
        }
        else -> throw SerializationException("unexpected element $index of ${member.descriptor.serialName}")
      }
    }
    value
  }
}

/** [member] of the body [text] alone, with [serializer]. */
private fun <T> decodeMember(text: String, member: Member, serializer: DeserializationStrategy<T>): T? {
  @Suppress("UNCHECKED_CAST")
  return decodeOrPlain { it.decodeFromString(PayloadReader(member, serializer), text) } as T?
}

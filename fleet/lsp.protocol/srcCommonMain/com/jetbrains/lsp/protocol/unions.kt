package com.jetbrains.lsp.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/*
 * Helpers of the hand-written union and edit serializers.
 *
 * How to decode a union, with no JSON tree:
 * - object-vs-array (`T | T[]`, `List | Items`): [decodeObjectOrArray], the next token peeked;
 * - object-vs-object (`Command | CodeAction`, `DocumentSymbol | SymbolInformation`, ...): one merged descriptor with the
 *   members of all variants, one pass, the variant chosen from the members seen;
 * - never a `JsonElement` tree of the whole value.
 * `FileChangeSerializer` is the one hybrid left: small members kept as `JsonElement` until `kind` is known.
 *
 * Tree-era quirks kept on purpose (pinned by the compat tests; do not "fix" them without a decision and golden update):
 * - a JSON `null` in `TextEdit.newText`, `CompletionItem.Edit` `newText`, `snippet.value` is the string "null";
 * - a snippet empties `newText`; a missing `newText` is "";
 * - `FileChange` with `kind: null` is the unknown kind "null" and fails;
 * - `snippet: null` and `annotationId: null` fail.
 */

/**
 * The edit serializers ignore unknown keys whatever the `Json` configuration says, like the `JsonObject` based
 * versions they replaced did.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val ignoreUnknownKeys: List<Annotation> = listOf(JsonIgnoreUnknownKeys())

internal fun missingField(field: String, serialName: String): Nothing =
  throw SerializationException("Field '$field' is required for type with serial name '$serialName', but it was missing")

/**
 * The failure for the members of [members] that are `null`, with the message of a generated serializer. Not a
 * `MissingFieldException`: `decodeOrPlain` reads that as "a nullable property has no `null` default" and decodes again,
 * while a member that is really missing fails the same way twice.
 */
internal fun missingFields(descriptor: SerialDescriptor, vararg members: Pair<String, Any?>): SerializationException {
  val missing = members.filter { it.second == null }.map { it.first }
  val serialName = descriptor.serialName
  return SerializationException(
    if (missing.size == 1) "Field '${missing[0]}' is required for type with serial name '$serialName', but it was missing"
    else "Fields $missing are required for type with serial name '$serialName', but they were missing"
  )
}

/** The descriptor of a `JsonContentPolymorphicSerializer` of [name], kept by the streaming union serializers. */
@OptIn(InternalSerializationApi::class)
internal fun unionDescriptor(name: String): SerialDescriptor =
  buildSerialDescriptor("JsonContentPolymorphicSerializer<$name>", PolymorphicKind.SEALED)

/** A JSON array decoded element by element with [element], like `ListSerializer(element)`, for a decode-only element. */
internal class DecodeOnlyList<T>(private val element: DeserializationStrategy<T>) : DeserializationStrategy<List<T>> {
  @OptIn(ExperimentalSerializationApi::class)
  override val descriptor: SerialDescriptor = listSerialDescriptor(element.descriptor)

  override fun deserialize(decoder: Decoder): List<T> = decoder.decodeStructure(descriptor) {
    val result = ArrayList<T>()
    while (true) {
      val index = decodeElementIndex(descriptor)
      if (index == CompositeDecoder.DECODE_DONE) break
      result.add(decodeSerializableElement(descriptor, index, element))
    }
    result
  }
}

/**
 * An object-or-array union: [ifArray] for an array, [ifObject] for anything else, like the tree `selectDeserializer`.
 * A decoder over text peeks at the next token, so no tree is built; a decoder over a tree already has the value and
 * reads it as a [JsonElement].
 */
internal fun <T> decodeObjectOrArray(decoder: Decoder, ifObject: DeserializationStrategy<T>, ifArray: DeserializationStrategy<T>): T {
  val isArray = peekIsArray(decoder)
  if (isArray != null) return decoder.decodeSerializableValue(if (isArray) ifArray else ifObject)
  val input = decoder as? JsonDecoder ?: throw SerializationException("an object-or-array union can be decoded only from JSON")
  val tree = input.decodeJsonElement()
  return input.json.decodeFromJsonElement(if (tree is JsonArray) ifArray else ifObject, tree)
}

/**
 * Whether the next value of a decoder over text is an array, without consuming it; `null` for any other decoder.
 * kotlinx has no public peek (https://github.com/Kotlin/kotlinx.serialization/issues/2223), so this reads the internal
 * lexer. `KotlinxAssumptionsTest` pins it: a kotlinx update that changes the lexer fails there.
 */
@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private fun peekIsArray(decoder: Decoder): Boolean? {
  val streaming = decoder as? kotlinx.serialization.json.internal.StreamingJsonDecoder ?: return null
  return streaming.lexer.peekNextToken() == kotlinx.serialization.json.internal.TC_BEGIN_LIST
}

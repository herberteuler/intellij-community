package com.jetbrains.lsp.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
import kotlin.jvm.JvmInline

@Serializable
data class SemanticTokensLegend(
    /**
     * The token types a server uses.
     */
    val tokenTypes: List<String>,

    /**
     * The token modifiers a server uses.
     */
    val tokenModifiers: List<String>,
)

@Serializable
data class SemanticTokensParams(
    /**
     * The text document.
     */
    val textDocument: TextDocumentIdentifier,
    override val workDoneToken: ProgressToken? = null,
    override val partialResultToken: ProgressToken? = null,
) : WorkDoneProgressParams, PartialResultParams


@Serializable
data class SemanticTokensRangeParams(
  /**
   * The text document.
   */
  val textDocument: TextDocumentIdentifier,
  /**
   * The range the semantic tokens are requested for.
   */
  val range: Range,
  override val workDoneToken: ProgressToken? = null,
  override val partialResultToken: ProgressToken? = null,
) : WorkDoneProgressParams, PartialResultParams


@Serializable
data class SemanticTokens(
    /**
     * An optional result id. If provided and clients support delta updating
     * the client will include the result id in the next semantic token request.
     * A server can then instead of computing all semantic tokens again simply
     * send a delta.
     */
    val resultId: String? = null,

    /**
     * The actual tokens.
     */
    @Serializable(with = SemanticTokensDataSerializer::class)
    val data: List<Int>,
)

@Serializable
data class SemanticTokensDeltaParams(
  /**
   * The text document.
   */
  val textDocument: TextDocumentIdentifier,

  /**
   * The result id of a previous response. The result Id can either point to
   * a full response or a delta response depending on what was received last.
   */
  val previousResultId: String,

  override val workDoneToken: ProgressToken? = null,
  override val partialResultToken: ProgressToken? = null,
) : WorkDoneProgressParams, PartialResultParams

@Serializable
data class SemanticTokensDelta(
  val resultId: String? = null,

  /**
   * The semantic token edits to transform a previous result into a new
   * result.
   */
  val edits: List<SemanticTokensEdit>,
)

@Serializable
data class SemanticTokensEdit(
  /**
   * The start offset of the edit. Unsigned.
   */
  val start: Int,

  /**
   * The count of elements to remove. Unsigned.
   */
  val deleteCount: Int,

  /**
   * The elements to insert. Unsigned.
   */
  @Serializable(with = SemanticTokensDataSerializer::class)
  val data: List<Int>? = null,
)

@Serializable(with = SemanticTokensDeltaResult.Serializer::class)
sealed interface SemanticTokensDeltaResult {
  @Serializable
  @JvmInline
  value class Full(val value: SemanticTokens) : SemanticTokensDeltaResult

  @Serializable
  @JvmInline
  value class Delta(val value: SemanticTokensDelta) : SemanticTokensDeltaResult

  /**
   * Streams the object with no tree, with the members of both variants: an `edits` member (also `null`) makes a
   * [Delta], as the tree way did, anything else a [Full]. Encode writes the variant with its own serializer.
   */
  class Serializer : KSerializer<SemanticTokensDeltaResult> {
    private val editsSerializer = ListSerializer(SemanticTokensEdit.serializer()).nullable
    private val dataSerializer = SemanticTokensDataSerializer.nullable

    override val descriptor: SerialDescriptor = unionDescriptor("SemanticTokensDeltaResult")

    /** Every member optional and nullable: a member of one variant is absent from the other, and `null` is not skipped. */
    private val merged: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.protocol.SemanticTokensOrDelta") {
      annotations = ignoreUnknownKeys
      element("resultId", String.serializer().nullable.descriptor, isOptional = true)
      element("data", dataSerializer.descriptor, isOptional = true)
      element("edits", editsSerializer.descriptor, isOptional = true)
    }

    override fun serialize(encoder: Encoder, value: SemanticTokensDeltaResult) {
      when (value) {
        is Full -> encoder.encodeSerializableValue(Full.serializer(), value)
        is Delta -> encoder.encodeSerializableValue(Delta.serializer(), value)
      }
    }

    override fun deserialize(decoder: Decoder): SemanticTokensDeltaResult {
      var resultId: String? = null
      var data: List<Int>? = null
      var hasEdits = false
      var edits: List<SemanticTokensEdit>? = null
      decoder.decodeStructure(merged) {
        while (true) {
          when (val index = decodeElementIndex(merged)) {
            RESULT_ID -> resultId = decodeNullableSerializableElement(merged, index, String.serializer())
            DATA -> data = decodeSerializableElement(merged, index, dataSerializer)
            EDITS -> {
              hasEdits = true
              edits = decodeSerializableElement(merged, index, editsSerializer)
            }
            CompositeDecoder.DECODE_DONE -> break
            else -> throw SerializationException("Unexpected index $index")
          }
        }
      }
      return when {
        hasEdits -> Delta(SemanticTokensDelta(resultId, edits ?: throw missingFields(SemanticTokensDelta.serializer().descriptor, "edits" to null)))
        else -> Full(SemanticTokens(resultId, data ?: throw missingFields(SemanticTokens.serializer().descriptor, "data" to null)))
      }
    }

    private companion object {
      const val RESULT_ID = 0
      const val DATA = 1
      const val EDITS = 2
    }
  }

}

/**
 * Semantic token data as unboxed ints: decode fills an [IntArray] and hands it out as a read-only `List<Int>`; encode
 * writes the ints straight from that array, or from any other `List<Int>` a caller built.
 *
 * The descriptor is the one of `ListSerializer(Int.serializer())` and every element goes through `decodeInt`, so the
 * accepted input (quoted numbers, `null` data for a nullable property, failures on `null` elements) is the same as before.
 */
internal object SemanticTokensDataSerializer : KSerializer<List<Int>> {
  override val descriptor: SerialDescriptor = ListSerializer(Int.serializer()).descriptor

  override fun deserialize(decoder: Decoder): List<Int> {
    val composite = decoder.beginStructure(descriptor)
    var array: IntArray
    var size = 0
    if (composite.decodeSequentially()) {
      size = composite.decodeCollectionSize(descriptor)
      array = IntArray(size)
      for (index in 0 until size) {
        array[index] = composite.decodeIntElement(descriptor, index)
      }
    }
    else {
      val sizeHint = composite.decodeCollectionSize(descriptor)
      array = IntArray(if (sizeHint > 0) sizeHint else 16)
      while (true) {
        val index = composite.decodeElementIndex(descriptor)
        if (index == CompositeDecoder.DECODE_DONE) break
        if (size == array.size) array = array.copyOf(size * 2)
        array[size++] = composite.decodeIntElement(descriptor, index)
      }
    }
    composite.endStructure(descriptor)
    return IntArrayList(if (size == array.size) array else array.copyOf(size))
  }

  override fun serialize(encoder: Encoder, value: List<Int>) {
    val size = value.size
    val composite = encoder.beginCollection(descriptor, size)
    if (value is IntArrayList) {
      val array = value.array
      for (index in 0 until size) {
        composite.encodeIntElement(descriptor, index, array[index])
      }
    }
    else {
      var index = 0
      for (element in value) {
        composite.encodeIntElement(descriptor, index++, element)
      }
    }
    composite.endStructure(descriptor)
  }

  /** Like `IntArray.asList()`, but the encoder can reach the array. */
  private class IntArrayList(val array: IntArray) : AbstractList<Int>(), RandomAccess {
    override val size: Int get() = array.size
    override fun get(index: Int): Int = array[index]
    override fun contains(element: Int): Boolean = array.contains(element)
    override fun indexOf(element: Int): Int = array.indexOf(element)
    override fun lastIndexOf(element: Int): Int = array.lastIndexOf(element)
  }
}

object SemanticTokensRequests {
    val SemanticTokensFullRequest: RequestType<SemanticTokensParams, SemanticTokens?, Nothing?> =
        RequestType(
            "textDocument/semanticTokens/full",
            SemanticTokensParams.serializer(), SemanticTokens.serializer().nullable,
            NoValueSerializer)

    val SemanticTokensRangeRequest: RequestType<SemanticTokensRangeParams, SemanticTokens, Nothing?> =
        RequestType(
            "textDocument/semanticTokens/range", SemanticTokensRangeParams.serializer(), SemanticTokens.serializer(),
            NoValueSerializer)

    val SemanticTokensFullDeltaRequest: RequestType<SemanticTokensDeltaParams, SemanticTokensDeltaResult?, Unit> =
        RequestType(
            "textDocument/semanticTokens/full/delta",
            SemanticTokensDeltaParams.serializer(), SemanticTokensDeltaResult.serializer().nullable,
            Unit.serializer())
}
package com.jetbrains.lsp.test

import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.decodeObjectOrArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
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
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The kotlinx behaviour the wire codec relies on and the kotlinx docs do not promise, one test per assumption, so a
 * kotlinx bump fails here and not somewhere in a session test. Checked with kotlinx-serialization 1.11.0.
 */
class KotlinxAssumptionsTest {
  private val jsons = listOf("LSP.json" to LSP.json, "LSP.decodeJson" to LSP.decodeJson)

  /** `EnvelopeShape`: the envelope reader switches descriptors between calls to hide `params` / `result`. */
  @Test
  fun `decodeElementIndex honours the descriptor of each call, not the one of beginStructure`() {
    val open = buildClassSerialDescriptor("test.Open") {
      element("a", Int.serializer().descriptor)
      element("x", Int.serializer().descriptor)
    }
    val hidden = buildClassSerialDescriptor("test.Hidden") {
      element("\u0000a", Int.serializer().descriptor)
      element("x", Int.serializer().descriptor)
    }
    val reader = object : DeserializationStrategy<List<String>> {
      override val descriptor: SerialDescriptor = open

      override fun deserialize(decoder: Decoder): List<String> = decoder.decodeStructure(open) {
        val read = ArrayList<String>()
        var current = hidden
        while (true) {
          val index = decodeElementIndex(current)
          if (index == CompositeDecoder.DECODE_DONE) break
          read += "${current.getElementName(index)}=${decodeIntElement(current, index)}"
          current = open
        }
        read
      }
    }
    for ((name, json) in jsons) {
      // `a` is unknown under `hidden` and skipped; `x` is read by its `hidden` index; then `open` is used and the object ends
      assertEquals(listOf("x=2"), json.decodeFromString(reader, """{"a":1,"x":2}"""), name)
      assertEquals(listOf("x=2", "a=1"), json.decodeFromString(reader, """{"x":2,"a":1}"""), name)
    }
  }

  /** `OutgoingEnvelopeSerializer`: `params` is declared as [JsonElement] and written by the payload serializer. */
  @Test
  fun `the encoder ignores element descriptors and writes with the serializer it is given`() {
    val descriptor = buildClassSerialDescriptor("test.Envelope") {
      element("params", JsonElement.serializer().descriptor)
    }
    val writer = object : SerializationStrategy<Range> {
      override val descriptor: SerialDescriptor = descriptor

      override fun serialize(encoder: Encoder, value: Range) = encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, 0, Range.serializer(), value)
      }
    }
    assertEquals("""{"params":{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}}""",
                 LSP.json.encodeToString(writer, Range(Position(1, 2), Position(3, 4))))
  }

  /**
   * `decodeObjectOrArray`: the internal lexer of a text decoder peeks at the next token without consuming it, at the
   * top level, after a key, in a list and after whitespace; a tree decoder reads the tree.
   */
  @Test
  fun `the internal lexer peeks at the next value without consuming it`() {
    val union = object : KSerializer<String> {
      override val descriptor: SerialDescriptor = String.serializer().descriptor

      override fun serialize(encoder: Encoder, value: String) = error("decode only")

      override fun deserialize(decoder: Decoder): String =
        decodeObjectOrArray(decoder, ifObject = Range.serializer().map { "object ${it.start.line}" },
                            ifArray = ListSerializer(Int.serializer()).map { "array $it" })
    }
    val range = """{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}"""
    for ((name, json) in jsons) {
      assertEquals("array [1, 2]", json.decodeFromString(union, " \n[1,2]"), name)
      assertEquals("object 1", json.decodeFromString(union, range), name)
      assertEquals(listOf("array []", "object 1"), json.decodeFromString(ListSerializer(union), "[ [] , $range ]"), name)
      val wrapped = buildClassSerialDescriptor("test.Wrapped") { element("v", union.descriptor) }
      val wrapper = object : DeserializationStrategy<String> {
        override val descriptor: SerialDescriptor = wrapped

        override fun deserialize(decoder: Decoder): String = decoder.decodeStructure(wrapped) {
          var value = ""
          while (decodeElementIndex(wrapped) == 0) value = decodeSerializableElement(wrapped, 0, union)
          value
        }
      }
      assertEquals("array [3]", json.decodeFromString(wrapper, """{"v": [3]}"""), name)
      assertEquals("object 1", json.decodeFromJsonElement(union, json.parseToJsonElement(range)), name)
      assertEquals("array [4]", json.decodeFromJsonElement(union, json.parseToJsonElement("[4]")), name)
    }
  }

  private fun <T, R> DeserializationStrategy<T>.map(transform: (T) -> R): DeserializationStrategy<R> =
    object : DeserializationStrategy<R> {
      override val descriptor: SerialDescriptor = this@map.descriptor
      override fun deserialize(decoder: Decoder): R = transform(decoder.decodeSerializableValue(this@map))
    }

  /** The merged readers: a `null` member is read with the non-null member serializer and gives `null`. */
  @Test
  fun `decodeNullableSerializableElement gives null for a null value without calling a non-nullable serializer`() {
    var calls = 0
    val member = object : KSerializer<String> by String.serializer() {
      override fun deserialize(decoder: Decoder): String {
        calls++
        return decoder.decodeString()
      }
    }
    val descriptor = buildClassSerialDescriptor("test.Merged") {
      element("a", String.serializer().nullable.descriptor, isOptional = true)
    }
    val reader = object : DeserializationStrategy<String?> {
      override val descriptor: SerialDescriptor = descriptor

      override fun deserialize(decoder: Decoder): String? = decoder.decodeStructure(descriptor) {
        var value: String? = "absent"
        while (true) {
          val index = decodeElementIndex(descriptor)
          if (index == CompositeDecoder.DECODE_DONE) break
          value = decodeNullableSerializableElement(descriptor, index, member)
        }
        value
      }
    }
    for ((name, json) in jsons) {
      calls = 0
      assertNull(json.decodeFromString(reader, """{"a":null}"""), name)
      assertEquals(0, calls, name)
      assertEquals("x", json.decodeFromString(reader, """{"a":"x"}"""), name)
      assertEquals(1, calls, name)
    }
  }
}

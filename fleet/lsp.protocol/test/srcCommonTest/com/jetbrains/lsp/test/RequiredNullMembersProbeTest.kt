package com.jetbrains.lsp.test

import com.jetbrains.lsp.protocol.LSP
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What `RequiredNullMembers` relies on: the plugin-generated `serialize` of a class asks
 * [CompositeEncoder.shouldEncodeElementDefault] for each property with a default and writes each nullable property by
 * [CompositeEncoder.encodeNullableSerializableElement], `null` included when the answer is `true`. So a delegating
 * [CompositeEncoder] can write a `null` member as JSON `null` in streaming mode, though [LSP.json] has
 * `explicitNulls = false`. Checked with kotlinx-serialization 1.11.0.
 */
class RequiredNullMembersProbeTest {
  @Serializable
  private data class Probe(val a: Int? = null, val b: String? = null, val c: Int = 1, val d: Int? = null)

  private val serializer: KSerializer<Probe> = Probe.serializer()

  @Test
  fun `generated serialize asks shouldEncodeElementDefault and writes nullable members by the nullable element call`() {
    for (json in listOf(LSP.json, Json { encodeDefaults = false; explicitNulls = false })) {
      val calls = ArrayList<String>()
      json.encodeToString(Recording(serializer, calls, force = setOf()), Probe())
      assertEquals(
        listOf("default a", "nullable a=null", "default b", "nullable b=null", "default c", "int c=1", "default d", "nullable d=null"),
        calls,
        "encodeDefaults=${json.configuration.encodeDefaults}",
      )
    }
  }

  @Test
  fun `a delegating composite encoder writes a forced null member as null in streaming mode`() {
    val calls = ArrayList<String>()
    assertEquals("""{"a":null,"c":1}""", LSP.json.encodeToString(Recording(serializer, calls, force = setOf("a")), Probe()))
    assertEquals("""{"a":7,"b":"x","c":1}""", LSP.json.encodeToString(Recording(serializer, calls, force = setOf("a")), Probe(a = 7, b = "x")))
    // The tree encoder too.
    assertEquals("""{"a":null,"c":1}""", LSP.json.encodeToJsonElement(Recording(serializer, calls, force = setOf("a")), Probe()).toString())
    // Without the delegate the member is dropped.
    assertEquals("""{"c":1}""", LSP.json.encodeToString(serializer, Probe()))
  }

  /** Delegates to the real encoder; records the calls; writes JSON `null` for a `null` member named in [force]. */
  private class Recording<T>(
    private val generated: SerializationStrategy<T>,
    private val calls: MutableList<String>,
    private val force: Set<String>,
  ) : SerializationStrategy<T> {
    override val descriptor: SerialDescriptor = generated.descriptor

    override fun serialize(encoder: Encoder, value: T) {
      generated.serialize(object : Encoder by encoder {
        override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder =
          Composite(encoder.beginStructure(descriptor))
      }, value)
    }

    private inner class Composite(private val output: CompositeEncoder) : CompositeEncoder by output {
      override val serializersModule: SerializersModule get() = output.serializersModule

      override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean {
        calls += "default ${descriptor.getElementName(index)}"
        return true
      }

      override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int) {
        calls += "int ${descriptor.getElementName(index)}=$value"
        output.encodeIntElement(descriptor, index, value)
      }

      override fun <V : Any> encodeNullableSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<V>, value: V?) {
        val name = descriptor.getElementName(index)
        calls += "nullable $name=$value"
        if (value == null && name in force) output.encodeSerializableElement(descriptor, index, JsonNull.serializer(), JsonNull)
        else output.encodeNullableSerializableElement(descriptor, index, serializer, value)
      }
    }
  }
}

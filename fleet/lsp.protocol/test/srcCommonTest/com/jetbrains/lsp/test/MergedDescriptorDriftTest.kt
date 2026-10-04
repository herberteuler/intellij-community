package com.jetbrains.lsp.test

import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActions
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.CompletionResolveRequestType
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.InsertReplaceEdit
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.SemanticTokens
import com.jetbrains.lsp.protocol.SemanticTokensDelta
import com.jetbrains.lsp.protocol.SemanticTokensRequests
import com.jetbrains.lsp.protocol.SymbolInformation
import com.jetbrains.lsp.protocol.TextDocuments
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.Workspace
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.JsonDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A merged reader lists the members of its variants by hand in its own descriptor. A member added to a variant class and
 * not to the merged descriptor would be dropped on the wire by `ignoreUnknownKeys`, with no compile error. The merged
 * descriptors are private, so the test catches them in a real decode: a spying decoder records every descriptor passed
 * to `beginStructure`.
 */
class MergedDescriptorDriftTest {
  private class Case(
    val merged: String,
    val deserializer: DeserializationStrategy<*>,
    val sample: String,
    val variants: List<KSerializer<*>>,
  )

  private val r = """{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}"""
  private val l = """{"uri":"file:///a.kt","range":$r}"""

  private val cases = listOf(
    Case("com.jetbrains.lsp.protocol.DocumentSymbolOrInformation", TextDocuments.DocumentSymbol.resultSerializer,
         """[{"name":"a","kind":5,"range":$r,"selectionRange":$r}]""",
         listOf(DocumentSymbol.serializer(), SymbolInformation.serializer())),
    Case("com.jetbrains.lsp.protocol.WorkspaceSymbolOrInformation", Workspace.Symbol.resultSerializer,
         """[{"name":"a","kind":5,"location":$l}]""",
         listOf(WorkspaceSymbol.serializer(), SymbolInformation.serializer())),
    Case("com.jetbrains.lsp.protocol.SemanticTokensOrDelta", SemanticTokensRequests.SemanticTokensFullDeltaRequest.resultSerializer,
         """{"resultId":"1","data":[1,2,3,4,5]}""",
         listOf(SemanticTokens.serializer(), SemanticTokensDelta.serializer())),
    Case("com.jetbrains.lsp.protocol.CommandOrCodeActionMerged", CodeActions.CodeActionRequest.resultSerializer,
         """[{"title":"t","command":"c"}]""",
         listOf(Command.serializer(), CodeAction.serializer())),
    Case("com.jetbrains.lsp.protocol.CompletionItem.Edit", CompletionResolveRequestType.resultSerializer,
         """{"label":"a","textEdit":{"range":$r,"newText":"x"}}""",
         listOf(TextEdit.serializer(), InsertReplaceEdit.serializer())),
  )

  @Test
  fun `each merged descriptor has exactly the members of its variants`() {
    for (case in cases) {
      val merged = assertNotNull(descriptorsSeen(case.deserializer, case.sample).find { it.serialName == case.merged },
                                 "${case.merged} not seen in the decode")
      val expected = case.variants.flatMap { it.descriptor.elementNames }.distinct().sorted()
      assertEquals(expected, merged.elementNames.distinct().sorted(), case.merged)
    }
  }

  /** The descriptors passed to `beginStructure` while [deserializer] decodes [text] with [LSP.decodeJson], in order. */
  private fun descriptorsSeen(deserializer: DeserializationStrategy<*>, text: String): List<SerialDescriptor> {
    val seen = ArrayList<SerialDescriptor>()
    LSP.decodeJson.decodeFromString(Spying(deserializer, seen), text)
    return seen
  }

  private class Spying<T>(private val inner: DeserializationStrategy<T>, private val seen: MutableList<SerialDescriptor>) : DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor get() = inner.descriptor
    override fun deserialize(decoder: Decoder): T = inner.deserialize(spy(decoder, seen))
  }

  @OptIn(SealedSerializationApi::class)
  private class SpyDecoder(private val delegate: JsonDecoder, private val seen: MutableList<SerialDescriptor>) : JsonDecoder by delegate {
    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
      seen += descriptor
      return SpyComposite(delegate.beginStructure(descriptor), seen)
    }

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T = deserializer.deserialize(this)

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? =
      if (deserializer.descriptor.isNullable || decodeNotNullMark()) deserializer.deserialize(this) else decodeNull()

    override fun decodeInline(descriptor: SerialDescriptor): Decoder = spy(delegate.decodeInline(descriptor), seen)
  }

  private class SpyComposite(private val delegate: CompositeDecoder, private val seen: MutableList<SerialDescriptor>) : CompositeDecoder by delegate {
    override fun <T> decodeSerializableElement(descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T>, previousValue: T?): T =
      delegate.decodeSerializableElement(descriptor, index, Spying(deserializer, seen), previousValue)

    override fun <T : Any> decodeNullableSerializableElement(descriptor: SerialDescriptor, index: Int, deserializer: DeserializationStrategy<T?>, previousValue: T?): T? =
      delegate.decodeNullableSerializableElement(descriptor, index, Spying(deserializer, seen), previousValue)

    override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder =
      spy(delegate.decodeInlineElement(descriptor, index), seen)
  }

  private companion object {
    fun spy(decoder: Decoder, seen: MutableList<SerialDescriptor>): Decoder =
      if (decoder is JsonDecoder && decoder !is SpyDecoder) SpyDecoder(decoder, seen) else decoder
  }
}

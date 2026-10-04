package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.protocol.ChangeAnnotationIdentifier
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionItem.Edit
import com.jetbrains.lsp.protocol.CompletionRequestType
import com.jetbrains.lsp.protocol.CompletionResult
import com.jetbrains.lsp.protocol.FoldingRange
import com.jetbrains.lsp.protocol.FoldingRangeKind
import com.jetbrains.lsp.protocol.FoldingRangeRequestType
import com.jetbrains.lsp.protocol.InsertReplaceEdit
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.TextEdit
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The streaming `CompletionItem.Edit` serializer accepts the same input and writes the same output as the
 * `JsonContentPolymorphicSerializer` it replaced (kept below as a reference), without a tree per item.
 * The `FoldingRange.kind` serializer reads the string once and keeps the tolerant enum decoding.
 */
class EditSerializerTest {

  private val r = """{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}"""
  private val r2 = """{"start":{"line":1,"character":2},"end":{"line":1,"character":9}}"""
  private val range = Range(Position(1, 2), Position(3, 4))
  private val range2 = Range(Position(1, 2), Position(1, 9))

  /** `Json` configurations and whether they write explicit nulls. */
  private val jsons = listOf(
    LSP.json to false,
    Json(LSP.json) { explicitNulls = true } to true,
    Json(LSP.json) { encodeDefaults = false } to false,
  )

  // --- CompletionItem.Edit ---

  private val edits = listOf(
    // Text
    """{"range":$r,"newText":"a"}""",
    """{"newText":"a","range":$r}""",
    """{"range":$r,"newText":"x","annotationId":"a1"}""",
    """{"annotationId":"a1","newText":"x","range":$r}""",
    """{"range":$r,"snippet":{"kind":"snippet","value":"${'$'}{1:x}"}}""",
    """{"snippet":{"value":"s","kind":"snippet"},"newText":"ignored","range":$r}""",
    """{"newText":"ignored","snippet":{"kind":"snippet","value":"s"},"annotationId":"a2","range":$r}""",
    """{"range":$r,"newText":"t","snippet":{"kind":"snippet"}}""",
    """{"range":$r}""",
    """{"range":$r,"newText":null}""",
    """{"range":$r,"newText":5}""",
    """{"range":$r,"newText":true}""",
    """{"range":$r,"annotationId":7}""",
    """{"range":$r,"snippet":{"kind":{"x":1},"value":null}}""",
    """{"x":{"a":[1,{"b":null}]},"range":$r,"y":[1,2],"newText":"a","z":null}""",
    """{"range":$r,"replace":$r2,"newText":"a"}""",
    """{"range":$r,"newText":"a","replace":null}""",
    """{"newText":"a"}""",
    """{"range":null,"newText":"a"}""",
    """{"range":$r,"snippet":null}""",
    """{"range":$r,"snippet":"s"}""",
    """{"range":$r,"annotationId":null}""",
    """{"range":$r,"newText":[1]}""",
    """{"range":5,"newText":"a"}""",
    // InsertReplace
    """{"newText":"a","insert":$r,"replace":$r2}""",
    """{"insert":$r,"replace":$r2,"newText":"a"}""",
    """{"replace":$r2,"newText":"a","insert":$r}""",
    """{"insert":$r,"replace":$r2,"newText":"a","extra":{"x":[1,null]}}""",
    """{"insert":$r,"replace":$r2,"newText":""}""",
    """{"insert":$r,"replace":$r2,"newText":5}""",
    """{"insert":$r,"replace":$r2,"newText":"a","range":$r}""",
    """{"insert":$r,"replace":$r2,"newText":"a","range":null}""",
    """{"insert":$r,"replace":$r2,"newText":"a","snippet":{"kind":"snippet","value":"s"}}""",
    """{"insert":$r,"replace":$r2,"newText":"a","annotationId":"a1"}""",
    """{"insert":$r,"replace":$r2}""",
    """{"insert":$r,"newText":"a"}""",
    """{"replace":$r2,"newText":"a","insert":null}""",
    """{"insert":$r,"replace":null,"newText":"a"}""",
    """{"insert":$r,"replace":$r2,"newText":null}""",
    """{"insert":$r,"replace":$r2,"newText":{}}""",
    """{"insert":5,"replace":$r2,"newText":"a"}""",
    """{"insert":null}""",
    """{"insert":$r,"replace":$r2,"newText":"a","range":$r,"snippet":{"kind":"snippet","value":"s"},"annotationId":"x"}""",
    // not an object
    """[1]""",
    """"s"""",
    """5""",
    """true""",
    """null""",
    """{}""",
  )

  @Test
  fun `edit decodes like the polymorphic tree serializer`() {
    for (text in edits) {
      assertSameDecode(RefEditSerializer().nullable, Edit.serializer().nullable, text)
    }
  }

  @Test
  fun `edit picks the variant by content, not key order`() {
    assertEquals(Edit.Text(TextEdit(range, "a")), decode("""{"newText":"a","range":$r}"""))
    assertEquals(Edit.Text(TextEdit(range, "", snippet = "s")), decode("""{"newText":"n","snippet":{"value":"s"},"range":$r}"""))
    assertEquals(Edit.Text(TextEdit(range, "n", annotationId = ChangeAnnotationIdentifier("a"))), decode("""{"annotationId":"a","range":$r,"newText":"n"}"""))
    assertEquals(Edit.Text(TextEdit(range, "null")), decode("""{"range":$r,"newText":null}"""))
    assertEquals(Edit.Text(TextEdit(range, "a")), decode("""{"range":$r,"replace":$r2,"newText":"a"}"""))
    assertEquals(Edit.InsertReplace(InsertReplaceEdit("a", range, range2)), decode("""{"replace":$r2,"newText":"a","insert":$r}"""))
    assertEquals(Edit.InsertReplace(InsertReplaceEdit("a", range, range2)), decode("""{"insert":$r,"range":$r,"replace":$r2,"newText":"a"}"""))
    val missing = runCatching { decode("""{"insert":$r,"newText":"a"}""") }.exceptionOrNull()
    assertIs<SerializationException>(missing)
    assertTrue(missing.message!!.contains("Field 'replace' is required"), missing.message)
    assertIs<SerializationException>(runCatching { decode("""{"insert":$r,"replace":$r2,"newText":null}""") }.exceptionOrNull())
  }

  @Test
  fun `edit span decode builds no tree`() {
    // Each payload repeats a member, first with a value of the wrong shape: the reference decodes it (its tree keeps the
    // last occurrence), the streaming serializer reads the first one too and fails.
    val payloads = listOf(
      """{"range":1,"range":$r,"newText":"a"}""",
      """{"newText":{},"newText":"a","range":$r}""",
      """{"insert":1,"insert":$r,"replace":$r2,"newText":"a"}""",
      """{"newText":[],"newText":"a","insert":$r,"replace":$r2}""",
    )
    for (payload in payloads) {
      assertNotNull(LSP.json.decodeFromString(RefEditSerializer(), payload), payload)
      assertIs<SerializationException>(runCatching { spanEdit(payload) }.exceptionOrNull(), "span decode of $payload must stream")
      assertIs<SerializationException>(runCatching { decode(payload) }.exceptionOrNull(), "string decode of $payload must stream")
    }
  }

  @Test
  fun `edit encodes like the polymorphic tree serializer`() {
    val values = listOf(
      Edit.Text(TextEdit(range, "plain")),
      Edit.Text(TextEdit(range, "")),
      Edit.Text(TextEdit(range, "quote \" and \n and é")),
      Edit.Text(TextEdit(range, "x", annotationId = ChangeAnnotationIdentifier("a1"))),
      Edit.Text(TextEdit(range, "", snippet = "${'$'}{1:x}")),
      Edit.Text(TextEdit(range, "", snippet = "s", annotationId = ChangeAnnotationIdentifier("a2"))),
      Edit.InsertReplace(InsertReplaceEdit("a", range, range2)),
      Edit.InsertReplace(InsertReplaceEdit("", range, range)),
      Edit.emptyAtPosition(Position(0, 0)),
    )
    for (value in values) {
      for ((json, _) in jsons) {
        assertEquals(json.encodeToString(RefEditSerializer(), value), json.encodeToString(Edit.serializer(), value), "$value with ${json.configuration}")
        assertEquals(json.encodeToJsonElement(RefEditSerializer(), value), json.encodeToJsonElement(Edit.serializer(), value), "$value with ${json.configuration}")
      }
      val item = CompletionItem(label = "a", textEdit = value)
      val text = LSP.json.encodeToString(CompletionItem.serializer(), item)
      assertEquals("""{"label":"a","textEdit":${LSP.json.encodeToString(RefEditSerializer(), value)}}""", text)
      assertEquals(item, LSP.json.decodeFromString(CompletionItem.serializer(), text))
    }
  }

  @Test
  fun `edit with a malformed member of the other variant fails where the tree ignored it`() {
    // Documented deviation on malformed input: the members of both variants are decoded typed before the variant is
    // known, so a wrong-shaped member of the variant not taken now fails instead of being ignored by the tree.
    val payloads = listOf(
      """{"insert":$r,"replace":$r2,"newText":"a","range":5}""",
      """{"insert":$r,"replace":$r2,"newText":"a","snippet":null}""",
      """{"insert":$r,"replace":$r2,"newText":"a","snippet":"s"}""",
      """{"insert":$r,"replace":$r2,"newText":"a","annotationId":null}""",
      """{"range":$r,"newText":"a","replace":5}""",
    )
    for (payload in payloads) {
      assertNotNull(LSP.json.decodeFromString(RefEditSerializer(), payload), payload)
      assertIs<SerializationException>(runCatching { decode(payload) }.exceptionOrNull(), "string decode of $payload")
      assertIs<SerializationException>(runCatching { spanEdit(payload) }.exceptionOrNull(), "span decode of $payload")
    }
  }

  @Test
  fun `edit descriptor is a class that ignores unknown keys`() {
    val descriptor = Edit.serializer().descriptor
    assertEquals("com.jetbrains.lsp.protocol.CompletionItem.Edit", descriptor.serialName)
    assertEquals(StructureKind.CLASS, descriptor.kind)
    assertEquals(listOf("range", "newText", "snippet", "annotationId", "insert", "replace"), (0 until descriptor.elementsCount).map(descriptor::getElementName))
    assertEquals(descriptor, Edit.Serializer().descriptor)
    val strict = Json(LSP.json) { ignoreUnknownKeys = false }
    assertEquals(Edit.Text(TextEdit(range, "a")), strict.decodeFromString(Edit.serializer(), """{"range":$r,"newText":"a","unknown":[1]}"""))
  }

  // --- FoldingRange.kind ---

  @Test
  fun `folding range kind decodes each known name and drops the rest`() {
    val cases = listOf(
      "\"comment\"" to FoldingRangeKind.Comment,
      "\"imports\"" to FoldingRangeKind.Imports,
      "\"region\"" to FoldingRangeKind.Region,
      "\"weird\"" to null,
      "\"Comment\"" to null,
      "\"\"" to null,
      "5" to null,
      "true" to null,
      "null" to null,
    )
    for ((kind, expected) in cases) {
      val text = """{"startLine":1,"endLine":2,"kind":$kind}"""
      val value = FoldingRange(1, 2, kind = expected)
      assertEquals(value, LSP.json.decodeFromString(FoldingRange.serializer(), text), "string $text")
      assertEquals(value, LSP.json.decodeFromJsonElement(FoldingRange.serializer(), LSP.json.parseToJsonElement(text)), "tree $text")
      assertEquals(listOf(value), span(FoldingRangeRequestType.resultSerializer, "[$text]"), "span $text")
    }
    assertNull(LSP.json.decodeFromString(FoldingRange.serializer(), """{"startLine":1,"endLine":2}""").kind)
    assertIs<SerializationException>(runCatching { LSP.json.decodeFromString(FoldingRange.serializer(), """{"startLine":1,"endLine":2,"kind":{}}""") }.exceptionOrNull())
    assertIs<SerializationException>(runCatching { LSP.json.decodeFromString(FoldingRange.serializer(), """{"startLine":1,"endLine":2,"kind":[]}""") }.exceptionOrNull())
    // the capabilities value sets keep the enum's own serializer
    assertEquals(listOf(FoldingRangeKind.Comment, FoldingRangeKind.Region),
                 LSP.json.decodeFromString(ListSerializer(FoldingRangeKind.serializer()), """["comment","region"]"""))
  }

  @Test
  fun `folding range encodes like before`() {
    val values = listOf(
      FoldingRange(1, 2),
      FoldingRange(1, 2, kind = FoldingRangeKind.Comment),
      FoldingRange(1, 2, kind = FoldingRangeKind.Imports),
      FoldingRange(1, 2, startCharacter = 3, endCharacter = 4, kind = FoldingRangeKind.Region, collapsedText = "..."),
      FoldingRange(0, 0, endCharacter = 4, collapsedText = "x"),
    )
    for (value in values) {
      for ((json, explicitNulls) in jsons) {
        val expected = refFoldingRange(value, explicitNulls)
        assertEquals(expected, json.encodeToString(FoldingRange.serializer(), value), "$value with ${json.configuration}")
        assertEquals(json.parseToJsonElement(expected), json.encodeToJsonElement(FoldingRange.serializer(), value), "$value with ${json.configuration}")
        assertEquals(value, json.decodeFromString(FoldingRange.serializer(), expected))
      }
    }
  }

  @Test
  fun `folding range kind element is a plain string`() {
    val descriptor = FoldingRange.serializer().descriptor
    val kind = descriptor.getElementDescriptor(descriptor.getElementIndex("kind"))
    assertEquals(PrimitiveKind.STRING, kind.kind)
    assertEquals(SerialKind.ENUM, FoldingRangeKind.serializer().descriptor.kind)
  }

  // --- helpers ---

  private fun decode(text: String): Edit = LSP.json.decodeFromString(Edit.serializer(), text)

  private fun <T> span(serializer: DeserializationStrategy<T>, payload: String): T? =
    LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0","id":1,"result":$payload}""".encodeToByteArray()).read().decodeResult(serializer)

  /** The `textEdit` of the single item of a completion result decoded from a frame (the scanned path). */
  private fun spanEdit(edit: String): Edit? {
    val result = span(CompletionRequestType.resultSerializer, """[{"label":"a","textEdit":$edit}]""")
    return assertIs<CompletionResult.Complete>(result).items.single().textEdit
  }

  private fun <T> assertSameDecode(reference: KSerializer<T>, serializer: KSerializer<T>, payload: String) {
    val tree = runCatching { LSP.json.parseToJsonElement(payload) }
    val expected = runCatching { LSP.json.decodeFromString(reference, payload) }
    val expectedTree = tree.mapCatching { LSP.json.decodeFromJsonElement(reference, it) }
    val actual = listOf(
      Triple("span", expected, runCatching { spanEdit(payload) }),
      Triple("string", expected, runCatching { LSP.json.decodeFromString(serializer, payload) }),
      Triple("tree", expectedTree, tree.mapCatching { LSP.json.decodeFromJsonElement(serializer, it) }),
    )
    for ((mode, want, got) in actual) {
      if (want.isSuccess) {
        val value = got.getOrElse { fail("$mode: $payload failed ($it), the reference decoded ${want.getOrNull()}", it) }
        assertEquals(want.getOrNull(), value, "$mode: $payload")
      }
      else {
        assertTrue(got.isFailure, "$mode: $payload decoded ${got.getOrNull()}, the reference failed with ${want.exceptionOrNull()}")
        assertIs<SerializationException>(got.exceptionOrNull(), "$mode: $payload")
      }
    }
  }

  /** The text the generated serializer wrote when `kind` used the enum's own serializer. */
  private fun refFoldingRange(value: FoldingRange, explicitNulls: Boolean): String {
    val members = mutableListOf("\"startLine\":${value.startLine}", "\"endLine\":${value.endLine}")
    fun member(name: String, text: String?) {
      if (text != null) members += "\"$name\":$text" else if (explicitNulls) members += "\"$name\":null"
    }
    member("startCharacter", value.startCharacter?.toString())
    member("endCharacter", value.endCharacter?.toString())
    member("kind", value.kind?.let { LSP.json.encodeToString(FoldingRangeKind.serializer(), it) })
    member("collapsedText", value.collapsedText?.let { "\"$it\"" })
    return members.joinToString(",", "{", "}")
  }
}

// Reference copy of the `CompletionItem.Edit.Serializer` before S3: a `JsonContentPolymorphicSerializer` that read the
// whole edit as a tree and picked the variant by the `insert` key.
private class RefEditSerializer : JsonContentPolymorphicSerializer<Edit>(Edit::class) {
  override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Edit> {
    return when {
      element is JsonObject && element.containsKey("insert") -> Edit.InsertReplace.serializer()
      else -> Edit.Text.serializer()
    }
  }
}

package com.jetbrains.lsp.test

import com.jetbrains.lsp.protocol.ChangeAnnotationIdentifier
import com.jetbrains.lsp.protocol.CreateFile
import com.jetbrains.lsp.protocol.CreateFileOptions
import com.jetbrains.lsp.protocol.DeleteFile
import com.jetbrains.lsp.protocol.DeleteFileOptions
import com.jetbrains.lsp.protocol.DocumentUri
import com.jetbrains.lsp.protocol.FileChange
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Position
import com.jetbrains.lsp.protocol.Range
import com.jetbrains.lsp.protocol.RenameFile
import com.jetbrains.lsp.protocol.RenameFileOptions
import com.jetbrains.lsp.protocol.SemanticTokens
import com.jetbrains.lsp.protocol.SemanticTokensDelta
import com.jetbrains.lsp.protocol.SemanticTokensDeltaResult
import com.jetbrains.lsp.protocol.SemanticTokensEdit
import com.jetbrains.lsp.protocol.TextDocumentEdit
import com.jetbrains.lsp.protocol.TextDocumentIdentifier
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.URI
import com.jetbrains.lsp.protocol.WorkspaceEdit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The streaming serializers of semantic token data and of `TextEdit` / `TextDocumentEdit` / `FileChange` accept the same
 * input and write the same output as the `JsonObject` based serializers they replaced (kept below as a reference).
 */
class StreamingSerializersTest {

  private val u1 = "file:///project/a.kt"
  private val u2 = "file:///project/b.kt"
  private val r = """{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}"""
  private val range = Range(Position(1, 2), Position(3, 4))

  private val jsons = listOf(
    LSP.json,
    Json(LSP.json) { explicitNulls = true },
    Json(LSP.json) { encodeDefaults = false },
  )

  // --- semantic tokens ---

  @Test
  fun `semantic tokens empty and caller built lists encode like a plain list`() {
    val intList = ListSerializer(Int.serializer())
    val lists = listOf(emptyList(), listOf(1, 2, 3), arrayListOf(0, -1, Int.MAX_VALUE, Int.MIN_VALUE), mutableListOf(5, 6, 7, 8).subList(1, 3),
                       intArrayOf(9, 8, 7).asList(), List(1000) { it * 31 })
    for (data in lists) {
      val tokens = SemanticTokens("r", data)
      val expected = """{"resultId":"r","data":${LSP.json.encodeToString(intList, data)}}"""
      assertEquals(expected, LSP.json.encodeToString(SemanticTokens.serializer(), tokens))
      assertEquals(LSP.json.parseToJsonElement(expected), LSP.json.encodeToJsonElement(SemanticTokens.serializer(), tokens))
      assertEquals(tokens, LSP.json.decodeFromString(SemanticTokens.serializer(), expected))
      assertEquals(tokens, LSP.json.decodeFromJsonElement(SemanticTokens.serializer(), LSP.json.parseToJsonElement(expected)))
    }
  }

  @Test
  fun `large semantic tokens round-trip unboxed`() {
    val data = List(100_000) { (it * 7919) % 100_003 }
    val text = """{"resultId":null,"data":[${data.joinToString(",")}]}"""
    val decoded = LSP.json.decodeFromString(SemanticTokens.serializer(), text)
    assertEquals(data, decoded.data)
    assertEquals(data.hashCode(), decoded.data.hashCode())
    assertIs<RandomAccess>(decoded.data)
    assertEquals(data.size, decoded.data.size)
    assertEquals(data.last(), decoded.data[data.size - 1])
    assertEquals(data.indexOf(100_002), decoded.data.indexOf(100_002))
    assertTrue(data[500] in decoded.data)
    assertEquals("""{"data":[${data.joinToString(",")}]}""", LSP.json.encodeToString(SemanticTokens.serializer(), decoded))
    assertEquals(decoded, LSP.json.decodeFromJsonElement(SemanticTokens.serializer(), LSP.json.parseToJsonElement(text)))
    assertEquals(data, decoded.data.toList())
  }

  @Test
  fun `semantic tokens delta edits keep null and missing data`() {
    val text = """{"resultId":"2","edits":[{"start":0,"deleteCount":5,"data":[2,5,3,0,3]},{"start":10,"deleteCount":1,"data":null},
      {"start":12,"deleteCount":0},{"start":20,"deleteCount":2,"data":[]}]}"""
    val expected = SemanticTokensDelta("2", listOf(
      SemanticTokensEdit(0, 5, listOf(2, 5, 3, 0, 3)), SemanticTokensEdit(10, 1, null), SemanticTokensEdit(12, 0, null), SemanticTokensEdit(20, 2, emptyList()),
    ))
    assertEquals(expected, LSP.json.decodeFromString(SemanticTokensDelta.serializer(), text))
    assertEquals(expected, LSP.json.decodeFromJsonElement(SemanticTokensDelta.serializer(), LSP.json.parseToJsonElement(text)))
    val result = LSP.json.decodeFromString(SemanticTokensDeltaResult.serializer(), text)
    assertEquals(SemanticTokensDeltaResult.Delta(expected), result)
    assertEquals(
      """{"resultId":"2","edits":[{"start":0,"deleteCount":5,"data":[2,5,3,0,3]},{"start":10,"deleteCount":1},{"start":12,"deleteCount":0},{"start":20,"deleteCount":2,"data":[]}]}""",
      LSP.json.encodeToString(SemanticTokensDelta.serializer(), expected),
    )
    val full = LSP.json.decodeFromString(SemanticTokensDeltaResult.serializer(), """{"data":[1,2,3,4,5]}""")
    assertEquals(SemanticTokensDeltaResult.Full(SemanticTokens(null, listOf(1, 2, 3, 4, 5))), full)
  }

  @Test
  fun `semantic token data accepts and rejects what a plain int list does`() {
    val intList = ListSerializer(Int.serializer())
    val arrays = listOf("[]", "[1,2,3]", """["1",2,"3"]""", "[ 1 , 2 ]", "[null]", "[1.5]", "[1,]", """["a"]""", "null", "{}", "[2147483648]", "[-0]")
    for (array in arrays) {
      assertSameOutcome("SemanticTokens data $array", { LSP.json.decodeFromString(intList, array) }) {
        LSP.json.decodeFromString(SemanticTokens.serializer(), """{"data":$array}""").data
      }
      assertSameOutcome("SemanticTokensEdit data $array", { if (array == "null") null else LSP.json.decodeFromString(intList, array) }) {
        LSP.json.decodeFromString(SemanticTokensEdit.serializer(), """{"start":1,"deleteCount":2,"data":$array}""").data
      }
      assertSameOutcome("SemanticTokens data $array from tree", { LSP.json.decodeFromString(intList, array) }) {
        LSP.json.decodeFromJsonElement(SemanticTokens.serializer(), LSP.json.parseToJsonElement("""{"data":$array}""")).data
      }
    }
    assertFailsWith<SerializationException> { LSP.json.decodeFromString(SemanticTokens.serializer(), """{"resultId":"r"}""") }
  }

  // --- TextEdit ---

  private val textEdits = listOf(
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
    """{"range":$r,"snippet":{"kind":"snippet","value":"v","extra":[1,{"a":null}]}}""",
    """{"x":{"a":[1,{"b":null}]},"range":$r,"y":[1,2],"newText":"a","z":null}""",
    """{"range":$r,"newText":"a","newText":"b"}""",
    """{"newText":"a"}""",
    """{"range":$r,"snippet":null}""",
    """{"range":$r,"snippet":"s"}""",
    """{"range":$r,"annotationId":null}""",
    """{"range":null,"newText":"a"}""",
    """{"range":$r,"newText":[1]}""",
    """[1]""",
    """"s"""",
    """null""",
  )

  @Test
  fun `text edit decodes like the JsonObject serializer`() {
    for (text in textEdits) {
      assertSameDecode(OldTextEditSerializer, TextEdit.serializer(), text)
      assertSameDecode(ListSerializer(OldTextEditSerializer), ListSerializer(TextEdit.serializer()), "[$text]")
    }
  }

  @Test
  fun `text edit picks the variant by content, not key order`() {
    val snippet = LSP.json.decodeFromString(TextEdit.serializer(), """{"newText":"n","snippet":{"value":"s"},"range":$r}""")
    assertEquals(TextEdit(range, newText = "", snippet = "s"), snippet)
    val annotated = LSP.json.decodeFromString(TextEdit.serializer(), """{"annotationId":"a","range":$r,"newText":"n"}""")
    assertEquals(TextEdit(range, "n", annotationId = ChangeAnnotationIdentifier("a")), annotated)
    assertEquals(TextEdit(range, "null"), LSP.json.decodeFromString(TextEdit.serializer(), """{"range":$r,"newText":null}"""))
  }

  @Test
  fun `text edit encodes like the JsonObject serializer`() {
    val values = listOf(
      TextEdit(range, "plain"),
      TextEdit(range, ""),
      TextEdit(range, "quote \" and \n and é"),
      TextEdit(range, "x", annotationId = ChangeAnnotationIdentifier("a1")),
      TextEdit(range, "ignored", snippet = "${'$'}{1:x}"),
      TextEdit(range, "", snippet = "s", annotationId = ChangeAnnotationIdentifier("a2")),
    )
    for (value in values) {
      assertSameEncode(OldTextEditSerializer, TextEdit.serializer(), value)
    }
  }

  // --- TextDocumentEdit ---

  private val textDocumentEdits = listOf(
    """{"textDocument":{"uri":"$u1","version":3},"edits":[{"range":$r,"newText":"a"},{"range":$r,"snippet":{"kind":"snippet","value":"s"}}]}""",
    """{"edits":[{"newText":"a","range":$r}],"textDocument":{"version":null,"uri":"$u1"}}""",
    """{"textDocument":{"uri":"$u1"},"edits":[],"extra":{"a":[1,2]}}""",
    """{"extra":null,"edits":[],"textDocument":{"uri":"$u1","version":"4"}}""",
    """{"textDocument":{"uri":"$u1"}}""",
    """{"edits":[]}""",
    """{"textDocument":{"uri":"$u1"},"edits":null}""",
    """{"textDocument":null,"edits":[]}""",
    """{"textDocument":{"uri":"$u1"},"edits":[{"newText":"a"}]}""",
  )

  @Test
  fun `text document edit decodes like the JsonObject serializer`() {
    for (text in textDocumentEdits) {
      assertSameDecode(OldTextDocumentEditSerializer, TextDocumentEdit.serializer(), text)
    }
  }

  @Test
  fun `text document edit writes a null version explicitly`() {
    val unversioned = TextDocumentEdit(TextDocumentIdentifier(DocumentUri(URI(u1))), listOf(TextEdit(range, "a")))
    val encoded = LSP.json.encodeToString(TextDocumentEdit.serializer(), unversioned)
    assertEquals("""{"textDocument":{"uri":"$u1","version":null},"edits":[{"range":$r,"newText":"a"}]}""", encoded)
    assertEquals(JsonNull, LSP.json.encodeToJsonElement(TextDocumentEdit.serializer(), unversioned).jsonObject["textDocument"]!!.jsonObject["version"])
    val values = listOf(
      unversioned,
      TextDocumentEdit(TextDocumentIdentifier(DocumentUri(URI(u1)), 7), listOf(TextEdit(range, "a"), TextEdit(range, "", snippet = "s"))),
      TextDocumentEdit(TextDocumentIdentifier(DocumentUri(URI(u2)), 0), emptyList()),
    )
    for (value in values) {
      assertSameEncode(OldTextDocumentEditSerializer, TextDocumentEdit.serializer(), value)
    }
  }

  // --- FileChange ---

  private val fileChanges = listOf(
    """{"textDocument":{"uri":"$u1","version":1},"edits":[{"range":$r,"newText":"a"}]}""",
    """{"edits":[],"textDocument":{"uri":"$u1"}}""",
    """{"kind":"create","uri":"$u1"}""",
    """{"uri":"$u1","kind":"create","options":{"overwrite":true,"ignoreIfExists":false},"annotationId":"a"}""",
    """{"options":{"overwrite":true,"extra":1},"annotationId":"a","uri":"$u1","kind":"create"}""",
    """{"kind":"rename","oldUri":"$u1","newUri":"$u2"}""",
    """{"options":{"ignoreIfExists":true},"kind":"rename","newUri":"$u2","oldUri":"$u1"}""",
    """{"oldUri":"$u1","newUri":"$u2","annotationId":null,"kind":"rename"}""",
    """{"kind":"delete","uri":"$u1","options":{"recursive":true,"ignoreIfNotExists":true}}""",
    """{"uri":"$u1","options":null,"annotationId":null,"kind":"delete"}""",
    """{"kind":"create","uri":"$u1","textDocument":{"x":1},"edits":"junk"}""",
    """{"textDocument":{"uri":"$u1","version":1},"edits":[],"uri":5,"oldUri":[1],"options":[1],"annotationId":{}}""",
    """{"x":[{"kind":"create"}],"kind":"delete","y":null,"uri":"$u1"}""",
    """{"kind":"unknown","uri":"$u1"}""",
    """{"kind":null,"uri":"$u1"}""",
    """{"kind":"create"}""",
    """{"kind":"rename","oldUri":"$u1"}""",
    """{"kind":"create","uri":"$u1","options":5}""",
    """{"kind":"create","uri":null}""",
    """{"kind":{"a":1},"uri":"$u1"}""",
    """{"uri":"$u1"}""",
    """{"textDocument":{"uri":"$u1"}}""",
  )

  @Test
  fun `file change decodes like the JsonObject serializer`() {
    for (text in fileChanges) {
      assertSameDecode(OldFileChangeSerializer, FileChange.serializer(), text)
    }
  }

  @Test
  fun `file change picks the variant by kind in any position`() {
    val uri = DocumentUri(URI(u1))
    val first = LSP.json.decodeFromString(FileChange.serializer(), """{"kind":"create","uri":"$u1","options":{"overwrite":true}}""")
    val last = LSP.json.decodeFromString(FileChange.serializer(), """{"options":{"overwrite":true},"uri":"$u1","kind":"create"}""")
    assertEquals(CreateFile(uri, CreateFileOptions(overwrite = true)), first)
    assertEquals(first, last)
    assertIs<RenameFile>(LSP.json.decodeFromString(FileChange.serializer(), """{"oldUri":"$u1","kind":"rename","newUri":"$u2"}"""))
    assertIs<DeleteFile>(LSP.json.decodeFromString(FileChange.serializer(), """{"uri":"$u1","kind":"delete"}"""))
    assertIs<TextDocumentEdit>(LSP.json.decodeFromString(FileChange.serializer(), """{"edits":[],"textDocument":{"uri":"$u1"}}"""))
  }

  @Test
  fun `file change encodes like the JsonObject serializer`() {
    val uri = DocumentUri(URI(u1))
    val other = DocumentUri(URI(u2))
    val annotation = ChangeAnnotationIdentifier("a")
    val values = listOf(
      TextDocumentEdit(TextDocumentIdentifier(uri), listOf(TextEdit(range, "a", annotationId = annotation))),
      TextDocumentEdit(TextDocumentIdentifier(uri, 2), emptyList()),
      CreateFile(uri),
      CreateFile(uri, CreateFileOptions(overwrite = true), annotation),
      CreateFile(uri, CreateFileOptions()),
      RenameFile(uri, other),
      RenameFile(uri, other, RenameFileOptions(ignoreIfExists = true), annotation),
      DeleteFile(uri),
      DeleteFile(uri, DeleteFileOptions(recursive = true, ignoreIfNotExists = false), annotation),
    )
    for (value in values) {
      assertSameEncode(OldFileChangeSerializer, FileChange.serializer(), value)
    }
  }

  @Test
  fun `workspace edit round-trips all change kinds`() {
    val uri = DocumentUri(URI(u1))
    val edit = WorkspaceEdit(
      changes = mapOf(uri to listOf(TextEdit(range, "a"), TextEdit(range, "", snippet = "s"))),
      documentChanges = listOf(
        TextDocumentEdit(TextDocumentIdentifier(uri), listOf(TextEdit(range, "b"))),
        CreateFile(uri), RenameFile(uri, DocumentUri(URI(u2))), DeleteFile(uri),
      ),
    )
    val text = LSP.json.encodeToString(WorkspaceEdit.serializer(), edit)
    assertEquals(edit, LSP.json.decodeFromString(WorkspaceEdit.serializer(), text))
    assertEquals(edit, LSP.json.decodeFromJsonElement(WorkspaceEdit.serializer(), LSP.json.encodeToJsonElement(WorkspaceEdit.serializer(), edit)))
    assertEquals(LSP.json.parseToJsonElement(text), LSP.json.encodeToJsonElement(WorkspaceEdit.serializer(), edit))
  }

  // --- helpers ---

  /** Decodes [text] with the old serializer and with the new one (from a string and from a tree): same value, or all fail. */
  private fun <T> assertSameDecode(old: KSerializer<T>, new: KSerializer<T>, text: String) {
    val expected = runCatching { LSP.json.decodeFromString(old, text) }
    assertSameOutcome("$text (string)", { expected.getOrThrow() }) { LSP.json.decodeFromString(new, text) }
    val tree = runCatching { LSP.json.parseToJsonElement(text) }.getOrNull() ?: return
    assertSameOutcome("$text (tree)", { LSP.json.decodeFromJsonElement(old, tree) }) { LSP.json.decodeFromJsonElement(new, tree) }
  }

  private fun <T> assertSameOutcome(case: String, expected: () -> T, actual: () -> T) {
    val expectedResult = runCatching(expected)
    val actualResult = runCatching(actual)
    if (expectedResult.isSuccess) {
      val value = actualResult.getOrElse { fail("$case: expected ${expectedResult.getOrNull()}, but failed with $it") }
      assertEquals(expectedResult.getOrNull(), value, case)
    }
    else {
      assertTrue(actualResult.isFailure, "$case: expected a failure (${expectedResult.exceptionOrNull()}), got ${actualResult.getOrNull()}")
    }
  }

  /** Same text from the streaming encoder and the same tree from the tree encoder, for several `Json` configurations. */
  private fun <T> assertSameEncode(old: KSerializer<T>, new: KSerializer<T>, value: T) {
    for (json in jsons) {
      assertEquals(json.encodeToString(old, value), json.encodeToString(new, value), "$value with ${json.configuration}")
      assertEquals(json.encodeToJsonElement(old, value), json.encodeToJsonElement(new, value), "$value with ${json.configuration}")
      val text = json.encodeToString(new, value)
      assertEquals(json.decodeFromString(old, text), json.decodeFromString(new, text))
    }
  }
}

// Reference copies of the pre-S4 serializers (LSP.kt), which went through `JsonObject` both ways.

private object OldTextEditSerializer : KSerializer<TextEdit> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OldTextEdit")

  override fun serialize(encoder: Encoder, value: TextEdit) {
    require(encoder is JsonEncoder) { "TextEdit can only be serialized to JSON" }
    val json = encoder.json
    val jsonObject = buildJsonObject {
      put("range", json.encodeToJsonElement(Range.serializer(), value.range))
      if (value.snippet != null) {
        put("snippet", buildJsonObject {
          put("kind", JsonPrimitive("snippet"))
          put("value", JsonPrimitive(value.snippet))
        })
      }
      else {
        put("newText", JsonPrimitive(value.newText))
      }
      if (value.annotationId != null) {
        put("annotationId", json.encodeToJsonElement(ChangeAnnotationIdentifier.serializer(), value.annotationId!!))
      }
    }
    encoder.encodeJsonElement(jsonObject)
  }

  override fun deserialize(decoder: Decoder): TextEdit {
    require(decoder is JsonDecoder) { "TextEdit can only be deserialized from JSON" }
    val json = decoder.json
    val jsonObject = decoder.decodeJsonElement().jsonObject
    val range = json.decodeFromJsonElement(Range.serializer(), jsonObject.getValue("range"))
    val snippet = jsonObject["snippet"]?.jsonObject?.get("value")?.jsonPrimitive?.content
    val newText = if (snippet != null) "" else jsonObject["newText"]?.jsonPrimitive?.content ?: ""
    val annotationId = jsonObject["annotationId"]?.let {
      json.decodeFromJsonElement(ChangeAnnotationIdentifier.serializer(), it)
    }
    return TextEdit(range = range, newText = newText, snippet = snippet, annotationId = annotationId)
  }
}

private object OldTextDocumentEditSerializer : KSerializer<TextDocumentEdit> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OldTextDocumentEdit")

  override fun serialize(encoder: Encoder, value: TextDocumentEdit) {
    require(encoder is JsonEncoder) { "TextDocumentEdit can only be serialized to JSON" }
    val json = encoder.json
    val textDocument = json.encodeToJsonElement(TextDocumentIdentifier.serializer(), value.textDocument).jsonObject
    val textDocumentWithVersion = JsonObject(
      textDocument + ("version" to (value.textDocument.version?.let { JsonPrimitive(it) } ?: JsonNull))
    )
    val jsonObject = buildJsonObject {
      put("textDocument", textDocumentWithVersion)
      put("edits", json.encodeToJsonElement(ListSerializer(OldTextEditSerializer), value.edits))
    }
    encoder.encodeJsonElement(jsonObject)
  }

  override fun deserialize(decoder: Decoder): TextDocumentEdit {
    require(decoder is JsonDecoder) { "TextDocumentEdit can only be deserialized from JSON" }
    val json = decoder.json
    val jsonObject = decoder.decodeJsonElement().jsonObject
    val textDocument = json.decodeFromJsonElement(TextDocumentIdentifier.serializer(), jsonObject.getValue("textDocument"))
    val edits = json.decodeFromJsonElement(ListSerializer(OldTextEditSerializer), jsonObject.getValue("edits"))
    return TextDocumentEdit(textDocument = textDocument, edits = edits)
  }
}

private object OldFileChangeSerializer : KSerializer<FileChange> {
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("OldFileChange")

  override fun serialize(encoder: Encoder, value: FileChange) {
    require(encoder is JsonEncoder) { "FileChange can only be serialized to JSON" }
    val json = encoder.json
    val jsonElement = when (value) {
      is TextDocumentEdit -> json.encodeToJsonElement(OldTextDocumentEditSerializer, value)
      is CreateFile -> JsonObject(json.encodeToJsonElement(CreateFile.serializer(), value).jsonObject + ("kind" to JsonPrimitive("create")))
      is RenameFile -> JsonObject(json.encodeToJsonElement(RenameFile.serializer(), value).jsonObject + ("kind" to JsonPrimitive("rename")))
      is DeleteFile -> JsonObject(json.encodeToJsonElement(DeleteFile.serializer(), value).jsonObject + ("kind" to JsonPrimitive("delete")))
    }
    encoder.encodeJsonElement(jsonElement)
  }

  override fun deserialize(decoder: Decoder): FileChange {
    require(decoder is JsonDecoder) { "FileChange can only be deserialized from JSON" }
    val jsonElement = decoder.decodeJsonElement()
    require(jsonElement is JsonObject) { "Expected JsonObject for FileChange" }
    val json = decoder.json
    return when (val kind = jsonElement["kind"]?.jsonPrimitive?.content) {
      "create" -> json.decodeFromJsonElement(CreateFile.serializer(), jsonElement)
      "rename" -> json.decodeFromJsonElement(RenameFile.serializer(), jsonElement)
      "delete" -> json.decodeFromJsonElement(DeleteFile.serializer(), jsonElement)
      null -> json.decodeFromJsonElement(OldTextDocumentEditSerializer, jsonElement)
      else -> throw SerializationException("Unknown FileChange kind: $kind")
    }
  }
}


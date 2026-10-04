package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActions
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.CommandOrCodeAction
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionList
import com.jetbrains.lsp.protocol.CompletionRequestType
import com.jetbrains.lsp.protocol.CompletionResult
import com.jetbrains.lsp.protocol.DocumentSymbolResult
import com.jetbrains.lsp.protocol.InlineCompletionRequestType
import com.jetbrains.lsp.protocol.InlineCompletionResult
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.SemanticTokens
import com.jetbrains.lsp.protocol.SemanticTokensDelta
import com.jetbrains.lsp.protocol.SemanticTokensDeltaResult
import com.jetbrains.lsp.protocol.SemanticTokensEdit
import com.jetbrains.lsp.protocol.TextDocuments
import com.jetbrains.lsp.protocol.Workspace
import com.jetbrains.lsp.protocol.WorkspaceSymbolResult
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The wire decode of the top-level union results picks the variant with no tree of the whole result (the completion
 * unions peek at the next token, the others stream the members of both variants), and decodes the same values (or
 * fails the same way) as the `JsonContentPolymorphicSerializer`s they replaced (kept below as a reference), also through
 * `decodeFromString` and `decodeFromJsonElement`. Encode is unchanged. [MergedUnionSerializersTest] has the rest.
 */
class UnionSerializersTest {

  private val r = """{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}"""
  private val l = """{"uri":"file:///a.kt","range":$r}"""

  // --- corpora: every variant, empty, null, key orders, unknown keys, lenient and escaped keys, wrong shapes, malformed ---

  private val completionCorpus = listOf(
    "null", "[]", "[ ]", """{"isIncomplete":false,"items":[]}""",
    """[{"label":"a"}]""",
    """[ {"label" : "a"} , {"label":"b","deprecated":true,"textEdit":{"range":$r,"newText":"x"}} ]""",
    """{"items":[{"label":"a","documentation":{"kind":"markdown","value":"*d*"}}],"isIncomplete":true}""",
    """{"isIncomplete":true,"items":[],"unknown":{"a":[1,"]",{"b":"}"}]}}""",
    """{"isIncomplete":true,"itemDefaults":{"editRange":$r},"items":[{"label":"a","textEdit":{"newText":"x","insert":$r,"replace":$r}}]}""",
    """{isIncomplete:false,items:[{label:a}]}""",
    """{"isIncomplete":false,"it\u0065ms":[{"label":"\"quoted\" \\ label"}]}""",
    """{"isIncomplete":true}""", """{"items":[]}""", "{}",
    "\"str\"", "42", "true", "[1]", "[null]", """[{"label":null}]""", """[{"label":"a","kind":"x"}]""",
    """{"isIncomplete":false,"items":[}""", """[{"label":"a"},]""", "[",
  )

  private val inlineCompletionCorpus = listOf(
    "null", "[]", """{"items":[]}""", """[{"insertText":"x"}]""",
    """{"items":[{"insertText":"x","range":$r,"command":{"title":"t","command":"c"}}],"extra":[1]}""",
    """{"extra":{"items":1},"items":[{"filterText":"f","insertText":"y"}]}""",
    "{}", "\"s\"", "7", "[{}]", "[null]", """{"items":[""",
  )

  private fun ds(name: String = "a", extra: String = "") = """{"name":"$name","kind":5,"range":$r,"selectionRange":$r$extra}"""
  private fun si(name: String = "a", extra: String = "") = """{"name":"$name","kind":5,"location":$l$extra}"""

  private val documentSymbolCorpus = listOf(
    "null", "[]", "[${ds()}]", "[${si()}]", "[${ds()},${ds("b", ""","children":[${ds("c")}]""")}]", "[${si()},${si("b")}]",
    """[{"location":$l,"name":"a","kind":5}]""", """[{"name":"a","location":$l,"kind":13,"containerName":"C","deprecated":false}]""",
    "[${si()},${ds()}]", "[${ds()},${si()}]",
    "[${ds("a", ""","detail":"location","x":{"location":1},"children":[]""")}]",
    "[${ds("a", ""","children":[${si()}]""")}]",
    """[{name:"a",kind:5,location:$l}]""", """[{"loc\u0061tion":$l,"name":"a","kind":5}]""",
    """[{"name":"a","kind":5,"location":$l,"location":$l}]""",
    "[${ds("a", ""","unknown":[{"location":2}]""")}]",
    """[{"detail":"}]\"{","name":"a","kind":5,"location":$l}]""",
    """[{"x":{"s":"{["},"name":"a","kind":5,"location":$l}]""",
    "[1,${si()}]", "[null]", "{}", "\"s\"", "[{}]", "[${ds()}", "[${si()},]",
  )

  private fun ws(extra: String = "") = """{"name":"a","kind":5,"location":$l$extra}"""

  private val workspaceSymbolCorpus = listOf(
    "null", "[]", "[${ws()}]", """[{"name":"a","kind":5,"location":{"uri":"file:///a.kt"}}]""",
    "[${ws(""","deprecated":false""")}]", """[{"deprecated":true,"name":"a","kind":5,"location":$l}]""",
    "[${ws(""","deprecated":false""")},${ws()}]", "[${ws()},${ws(""","deprecated":false""")}]",
    "[${ws(""","data":{"deprecated":true}""")}]", "[${ws(""","containerName":"deprecated"""")}]",
    """[{name:"a",kind:5,location:$l,deprecated:false}]""", """[{"name":"a","kind":5,"location":$l,"depr\u0065cated":null}]""",
    """[{"name":"a","kind":5,"location":{"uri":"file:///a.kt"},"deprecated":false}]""",
    "[1]", "[null]", "{}", "\"s\"", "[{}]", "[${ws()},",
  )

  private val semanticTokensDeltaCorpus = listOf(
    "null", """{"resultId":"1","data":[1,2,3]}""", """{"data":[]}""", """{"resultId":"2","edits":[{"start":0,"deleteCount":1,"data":[3]}]}""",
    """{"edits":[]}""", """{"data":[1],"edits":[]}""", """{"edits":[{"deleteCount":2,"start":1}],"resultId":null}""",
    """{"resultId":"x","data":[1],"extra":{"edits":1}}""", """{edits:[],resultId:r}""", """{"\u0065dits":[]}""",
    """{"edits":null}""", """{"edits":[{"start":0}]}""", "{}", "[]", "\"s\"", "1", """{"data":[1,}""",
  )

  private val cmd = """{"title":"t","command":"c","arguments":[1,{"a":"b"}]}"""
  private val ca = """{"title":"t","kind":"quickfix","isPreferred":true,"diagnostics":[{"range":$r,"message":"m"}]}"""
  private val caCommand = """{"title":"t","command":{"title":"x","command":"y"}}"""
  private val caEdit = """{"title":"t","edit":{"changes":{"file:///a.kt":[{"range":$r,"newText":"x"}]}},"data":{"command":"z"}}"""

  private val codeActionCorpus = listOf(
    "null", "[]", "[$cmd]", "[$ca]", "[$cmd,$ca,$caCommand,$caEdit]", "[ $caEdit , $cmd ]",
    """[{"command":"c","title":"t"}]""", """[{"title":"t","command":null}]""", """[{"title":"t"}]""",
    """[{title:"t",command:"c"}]""", """[{"title":"t","comm\u0061nd":"c"}]""", """[{"title":"t","command":"c","unknown":{"command":1}}]""",
    """[{"title":"t","command":1}]""", """[{title:"t",command:c}]""", """[{"command":"c"}]""", """[{"title":"t","disabled":{}}]""",
    """[{"title":"t]},{\"","command":"c","arguments":["}"]},{"title":"u","isPreferred":true}]""",
    """[{"title":"t","data":{"x":"{["},"command":"c"},{"title":"u","data":["{"],"isPreferred":true}]""",
    "[null]", "[1]", "[\"s\"]", "[[]]", "{}", "\"s\"", "[$cmd", "[$cmd,]",
  )

  // --- the decode matches the reference, in the wire decode, decodeFromString and decodeFromJsonElement ---

  @Test
  fun `completion result decodes like the tree serializer`() {
    assertSameDecode(CompletionRequestType.resultSerializer, RefCompletionResult().nullable, completionCorpus)
  }

  @Test
  fun `inline completion result decodes like the tree serializer`() {
    assertSameDecode(InlineCompletionRequestType.resultSerializer, RefInlineCompletionResult().nullable, inlineCompletionCorpus)
  }

  @Test
  fun `document symbol result decodes like the tree serializer`() {
    assertSameDecode(TextDocuments.DocumentSymbol.resultSerializer, RefDocumentSymbolResult().nullable, documentSymbolCorpus)
  }

  @Test
  fun `workspace symbol result decodes like the tree serializer`() {
    assertSameDecode(Workspace.Symbol.resultSerializer, RefWorkspaceSymbolResult().nullable, workspaceSymbolCorpus)
  }

  @Test
  fun `semantic tokens delta result decodes like the tree serializer`() {
    assertSameDecode(SemanticTokensDeltaResult.serializer().nullable, RefSemanticTokensDeltaResult().nullable, semanticTokensDeltaCorpus)
  }

  @Test
  fun `code action list decodes like the tree serializer`() {
    assertSameDecode(CodeActions.CodeActionRequest.resultSerializer, ListSerializer(RefCommandOrCodeAction()).nullable, codeActionCorpus)
  }

  @Test
  fun `each variant is picked`() {
    assertIs<CompletionResult.Complete>(wire(CompletionRequestType.resultSerializer, "[]"))
    assertIs<CompletionResult.MaybeIncomplete>(wire(CompletionRequestType.resultSerializer, """{"isIncomplete":false,"items":[]}"""))
    assertIs<InlineCompletionResult.Items>(wire(InlineCompletionRequestType.resultSerializer, "[]"))
    assertIs<InlineCompletionResult.ItemList>(wire(InlineCompletionRequestType.resultSerializer, """{"items":[]}"""))
    assertIs<DocumentSymbolResult.DocumentSymbols>(wire(TextDocuments.DocumentSymbol.resultSerializer, "[]"))
    assertIs<DocumentSymbolResult.SymbolInformations>(wire(TextDocuments.DocumentSymbol.resultSerializer, "[${si()}]"))
    assertIs<WorkspaceSymbolResult.WorkspaceSymbols>(wire(Workspace.Symbol.resultSerializer, "[${ws()}]"))
    assertIs<WorkspaceSymbolResult.SymbolInformations>(wire(Workspace.Symbol.resultSerializer, "[${ws(""","deprecated":true""")}]"))
    assertIs<SemanticTokensDeltaResult.Full>(wire(SemanticTokensDeltaResult.serializer(), """{"data":[]}"""))
    assertIs<SemanticTokensDeltaResult.Delta>(wire(SemanticTokensDeltaResult.serializer(), """{"edits":[]}"""))
    val actions = wire(CodeActions.CodeActionRequest.resultSerializer, "[$cmd,$caCommand]")!!
    assertIs<CommandOrCodeAction.Command>(actions[0])
    assertIs<CommandOrCodeAction.CodeAction>(actions[1])
  }

  // --- no tree: a streaming decode reads every occurrence of a repeated member, a tree keeps only the last one ---

  @Test
  fun `wire decode of each variant builds no tree`() {
    // Each payload repeats a member, first with a value of the wrong shape: the reference decodes it (its tree keeps the
    // last occurrence), a variant that streams from the text reads the first one too and fails.
    val bad = "{}"
    val cases = listOf(
      Triple(CompletionRequestType.resultSerializer, RefCompletionResult().nullable, """{"isIncomplete":false,"items":$bad,"items":[]}"""),
      Triple(CompletionRequestType.resultSerializer, RefCompletionResult().nullable, """[{"label":$bad,"label":"a"}]"""),
      Triple(InlineCompletionRequestType.resultSerializer, RefInlineCompletionResult().nullable, """{"items":$bad,"items":[]}"""),
      Triple(InlineCompletionRequestType.resultSerializer, RefInlineCompletionResult().nullable, """[{"insertText":"x","range":1,"range":$r}]"""),
      Triple(TextDocuments.DocumentSymbol.resultSerializer, RefDocumentSymbolResult().nullable, "[${ds("a", ""","name":$bad,"name":"b"""")}]"),
      Triple(TextDocuments.DocumentSymbol.resultSerializer, RefDocumentSymbolResult().nullable, "[${si("a", ""","name":$bad,"name":"b"""")}]"),
      Triple(Workspace.Symbol.resultSerializer, RefWorkspaceSymbolResult().nullable, "[${ws(""","name":$bad,"name":"b"""")}]"),
      Triple(Workspace.Symbol.resultSerializer, RefWorkspaceSymbolResult().nullable, "[${ws(""","name":$bad,"name":"b","deprecated":true""")}]"),
      Triple(SemanticTokensDeltaResult.serializer().nullable, RefSemanticTokensDeltaResult().nullable, """{"data":$bad,"data":[1]}"""),
      Triple(SemanticTokensDeltaResult.serializer().nullable, RefSemanticTokensDeltaResult().nullable, """{"edits":$bad,"edits":[]}"""),
      Triple(CodeActions.CodeActionRequest.resultSerializer, ListSerializer(RefCommandOrCodeAction()).nullable,
             """[{"title":$bad,"title":"t","command":"c"}]"""),
      Triple(CodeActions.CodeActionRequest.resultSerializer, ListSerializer(RefCommandOrCodeAction()).nullable,
             """[$cmd,{"title":$bad,"title":"t","isPreferred":true}]"""),
    )
    for ((serializer, reference, payload) in cases) {
      val expected = LSP.json.decodeFromString(reference, payload)
      assertTrue(expected != null, payload)
      @Suppress("UNCHECKED_CAST")
      val streamed = runCatching { wire(serializer as KSerializer<Any?>, payload) }
      assertIs<SerializationException>(streamed.exceptionOrNull(), "wire decode of $payload must stream, got ${streamed.getOrNull()}")
    }
  }

  // --- the rest of the serializer is unchanged ---

  @Suppress("DEPRECATION")
  @Test
  fun `encode is byte identical to the tree serializer`() {
    val items = listOf(CompletionItem(label = "a"), CompletionItem(label = "b", deprecated = true))
    assertSameEncode(CompletionResult.serializer(), RefCompletionResult(),
                     listOf(CompletionResult.Complete(items), CompletionResult.MaybeIncomplete(CompletionList(true, items = items))))
    assertSameEncode(SemanticTokensDeltaResult.serializer(), RefSemanticTokensDeltaResult(),
                     listOf(SemanticTokensDeltaResult.Full(SemanticTokens("1", listOf(1, 2))),
                            SemanticTokensDeltaResult.Delta(SemanticTokensDelta("2", listOf(SemanticTokensEdit(0, 1, listOf(3)))))))
    assertSameEncode(CommandOrCodeAction.serializer(), RefCommandOrCodeAction(),
                     listOf(CommandOrCodeAction.Command(Command("t", "c", null)),
                            CommandOrCodeAction.CodeAction(CodeAction("t", isPreferred = true, command = Command("x", "y")))))
    for (payload in documentSymbolCorpus.filter { it.startsWith("[") }) {
      val value = runCatching { LSP.json.decodeFromString(RefDocumentSymbolResult(), payload) }.getOrNull() ?: continue
      assertSameEncode(DocumentSymbolResult.serializer(), RefDocumentSymbolResult(), listOf(value))
    }
    for (payload in workspaceSymbolCorpus.filter { it.startsWith("[") }) {
      val value = runCatching { LSP.json.decodeFromString(RefWorkspaceSymbolResult(), payload) }.getOrNull() ?: continue
      assertSameEncode(WorkspaceSymbolResult.serializer(), RefWorkspaceSymbolResult(), listOf(value))
    }
  }

  @Test
  fun `descriptors keep the name, kind and equality of the tree serializer`() {
    val pairs = listOf(
      CompletionResult.serializer() to RefCompletionResult(),
      InlineCompletionResult.serializer() to RefInlineCompletionResult(),
      DocumentSymbolResult.serializer() to RefDocumentSymbolResult(),
      WorkspaceSymbolResult.serializer() to RefWorkspaceSymbolResult(),
      SemanticTokensDeltaResult.serializer() to RefSemanticTokensDeltaResult(),
      CommandOrCodeAction.serializer() to RefCommandOrCodeAction(),
    )
    for ((serializer, reference) in pairs) {
      assertEquals(reference.descriptor.serialName, serializer.descriptor.serialName)
      assertEquals(reference.descriptor.kind, serializer.descriptor.kind)
      assertEquals(reference.descriptor.elementsCount, serializer.descriptor.elementsCount)
      assertEquals(serializer.descriptor, serializer.descriptor)
      assertEquals(serializer.nullable.descriptor, serializer.nullable.descriptor)
      assertTrue(serializer.nullable.descriptor.isNullable)
    }
    // two instances of a class serializer have equal descriptors, like before
    assertEquals(CompletionResult.Serializer().descriptor, CompletionResult.Serializer().descriptor)
    assertEquals(CompletionResult.Serializer().descriptor.hashCode(), CompletionResult.Serializer().descriptor.hashCode())
  }

  // --- helpers ---

  private fun <T> wire(serializer: DeserializationStrategy<T>, payload: String): T? =
    LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0","id":1,"result":$payload}""".encodeToByteArray()).read().decodeResult(serializer)

  private fun <T> assertSameDecode(serializer: KSerializer<T>, reference: KSerializer<T>, corpus: List<String>) {
    for (payload in corpus) {
      val tree = runCatching { LSP.json.parseToJsonElement(payload) }
      val expected = runCatching { LSP.json.decodeFromString(reference, payload) }
      val expectedTree = tree.mapCatching { LSP.json.decodeFromJsonElement(reference, it) }
      val actual = listOf(
        Triple("wire", expected, runCatching { wire(serializer, payload) }),
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
          val error = got.exceptionOrNull()!!
          // a well-formed payload fails in the serializer; a malformed one already in the parse, as the reference
          if (tree.isSuccess) {
            assertTrue(error is SerializationException || error::class == want.exceptionOrNull()!!::class,
                       "$mode: $payload failed with $error, the reference with ${want.exceptionOrNull()}")
          }
        }
      }
    }
  }

  private fun <T> assertSameEncode(serializer: KSerializer<T>, reference: KSerializer<T>, values: List<T>) {
    for (value in values) {
      assertEquals(LSP.json.encodeToString(reference, value), LSP.json.encodeToString(serializer, value))
      assertEquals(LSP.json.encodeToJsonElement(reference, value), LSP.json.encodeToJsonElement(serializer, value))
    }
  }

  // --- reference copies of the tree serializers before S5d ---

  private class RefCompletionResult : JsonContentPolymorphicSerializer<CompletionResult>(CompletionResult::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CompletionResult> {
      return when {
        element is JsonArray -> CompletionResult.Complete.serializer()
        else -> CompletionResult.MaybeIncomplete.serializer()
      }
    }
  }

  private class RefInlineCompletionResult : JsonContentPolymorphicSerializer<InlineCompletionResult>(InlineCompletionResult::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<InlineCompletionResult> {
      return when (element) {
        is JsonArray -> InlineCompletionResult.Items.serializer()
        else -> InlineCompletionResult.ItemList.serializer()
      }
    }
  }

  private class RefDocumentSymbolResult : JsonContentPolymorphicSerializer<DocumentSymbolResult>(DocumentSymbolResult::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<DocumentSymbolResult> {
      return when (element) {
        is JsonArray -> {
          if (element.isNotEmpty() && element[0].let { it is JsonObject && it.containsKey("location") }) {
            DocumentSymbolResult.SymbolInformations.serializer()
          }
          else {
            DocumentSymbolResult.DocumentSymbols.serializer()
          }
        }
        else -> throw SerializationException("Expected an array of DocumentSymbol or SymbolInformation.")
      }
    }
  }

  private class RefWorkspaceSymbolResult : JsonContentPolymorphicSerializer<WorkspaceSymbolResult>(WorkspaceSymbolResult::class) {
    private fun isSymbolInformation(element: JsonElement) = element is JsonObject && element.containsKey("deprecated")

    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<WorkspaceSymbolResult> {
      return when {
        element is JsonArray -> {
          if (element.isNotEmpty() && isSymbolInformation(element[0])) {
            WorkspaceSymbolResult.SymbolInformations.serializer()
          }
          else {
            WorkspaceSymbolResult.WorkspaceSymbols.serializer()
          }
        }
        else -> throw SerializationException("Expected an array of either WorkspaceSymbol or SymbolInformation.")
      }
    }
  }

  private class RefSemanticTokensDeltaResult : JsonContentPolymorphicSerializer<SemanticTokensDeltaResult>(SemanticTokensDeltaResult::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SemanticTokensDeltaResult> {
      return if (element is JsonObject && element.containsKey("edits")) SemanticTokensDeltaResult.Delta.serializer()
      else SemanticTokensDeltaResult.Full.serializer()
    }
  }

  private class RefCommandOrCodeAction : JsonContentPolymorphicSerializer<CommandOrCodeAction>(CommandOrCodeAction::class) {
    private fun isCommand(json: JsonObject) = json["command"]?.let { command -> command is JsonPrimitive && command.isString }
                                              ?: false

    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CommandOrCodeAction> {
      return when (element) {
        is JsonObject -> if (isCommand(element)) CommandOrCodeAction.Command.serializer() else CommandOrCodeAction.CodeAction.serializer()
        else -> throw SerializationException("Expected either Command or CodeAction, got $element")
      }
    }
  }
}

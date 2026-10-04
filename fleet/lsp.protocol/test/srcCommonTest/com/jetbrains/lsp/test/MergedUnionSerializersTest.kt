package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.implementation.withLsp
import com.jetbrains.lsp.protocol.CodeActionParams
import com.jetbrains.lsp.protocol.CodeActions
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.CommandOrCodeAction
import com.jetbrains.lsp.protocol.CompletionParams
import com.jetbrains.lsp.protocol.CompletionRequestType
import com.jetbrains.lsp.protocol.CompletionResult
import com.jetbrains.lsp.protocol.DocumentSymbolParams
import com.jetbrains.lsp.protocol.DocumentSymbolResult
import com.jetbrains.lsp.protocol.InlineCompletionParams
import com.jetbrains.lsp.protocol.InlineCompletionRequestType
import com.jetbrains.lsp.protocol.InlineCompletionResult
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.RequestType
import com.jetbrains.lsp.protocol.SemanticTokensDeltaParams
import com.jetbrains.lsp.protocol.SemanticTokensDeltaResult
import com.jetbrains.lsp.protocol.SemanticTokensRequests
import com.jetbrains.lsp.protocol.TextDocuments
import com.jetbrains.lsp.protocol.Workspace
import com.jetbrains.lsp.protocol.WorkspaceSymbolParams
import com.jetbrains.lsp.protocol.WorkspaceSymbolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The streaming union serializers ([DocumentSymbolResult], [WorkspaceSymbolResult], [SemanticTokensDeltaResult],
 * [CommandOrCodeAction]) read each object once with the members of both variants and build the variant at the end, with
 * no tree; the completion unions are tried variant by variant. [UnionSerializersTest] holds the corpora against the old
 * tree serializers; this test holds what only the new way does.
 */
@OptIn(ExperimentalSerializationApi::class)
class MergedUnionSerializersTest {
  private val r = """{"start":{"line":1,"character":2},"end":{"line":3,"character":4}}"""
  private val l = """{"uri":"file:///a.kt","range":$r}"""
  private fun ds(extra: String = "") = """{"name":"a","kind":5,"range":$r,"selectionRange":$r$extra}"""
  private fun si(extra: String = "") = """{"name":"a","kind":5,"location":$l$extra}"""

  // --- no tree: every occurrence of a repeated member is read, a tree keeps only the last one ---

  @Test
  fun `a repeated member with a bad first value fails, in the wire decode and in decodeFromString`() {
    val bad = "{}"
    val cases = listOf(
      Triple(TextDocuments.DocumentSymbol.resultSerializer, RefDocumentSymbolResult(), "[${ds(""","detail":$bad,"detail":"d"""")}]"),
      Triple(TextDocuments.DocumentSymbol.resultSerializer, RefDocumentSymbolResult(), "[${si(""","location":$bad,"location":$l""")}]"),
      Triple(Workspace.Symbol.resultSerializer, RefWorkspaceSymbolResult(), "[${si(""","kind":$bad,"kind":5""")}]"),
      Triple(Workspace.Symbol.resultSerializer, RefWorkspaceSymbolResult(), "[${si(""","deprecated":$bad,"deprecated":false""")}]"),
      Triple(SemanticTokensRequests.SemanticTokensFullDeltaRequest.resultSerializer, RefSemanticTokensDeltaResult(),
             """{"resultId":$bad,"resultId":"1","data":[1]}"""),
      Triple(SemanticTokensRequests.SemanticTokensFullDeltaRequest.resultSerializer, RefSemanticTokensDeltaResult(),
             """{"edits":[],"resultId":$bad,"resultId":"1"}"""),
      Triple(CodeActions.CodeActionRequest.resultSerializer, null, """[{"title":"t","command":"c","arguments":$bad,"arguments":[]}]"""),
      Triple(CodeActions.CodeActionRequest.resultSerializer, null, """[{"title":"t","isPreferred":$bad,"isPreferred":true}]"""),
    )
    for ((serializer, reference, payload) in cases) {
      if (reference != null) assertNotNull(LSP.json.decodeFromString(reference, payload), "the tree decodes $payload")
      @Suppress("UNCHECKED_CAST")
      val s = serializer as KSerializer<Any?>
      assertIs<SerializationException>(runCatching { wire(s, payload) }.exceptionOrNull(), "wire decode of $payload must stream")
      assertIs<SerializationException>(runCatching { LSP.json.decodeFromString(s, payload) }.exceptionOrNull(),
                                       "string decode of $payload must stream")
    }
  }

  // --- the variant is built at the end of the object ---

  @Test
  fun `a string command makes a command, an object or null a code action, wherever the member comes`() {
    val action = """{"title":"x","command":"y"}"""
    val cases = listOf(
      """{"title":"t","command":"c"}""" to CommandOrCodeAction.Command(Command("t", "c")),
      """{"command":"c","title":"t","arguments":[1]}""" to CommandOrCodeAction.Command(Command("t", "c", listOf(LSP.json.parseToJsonElement("1")))),
      """{"title":"t","arguments":[1],"isPreferred":true,"command":"c"}""" to
        CommandOrCodeAction.Command(Command("t", "c", listOf(LSP.json.parseToJsonElement("1")))),
      """{"title":"t","command":$action}""" to CommandOrCodeAction.CodeAction(com.jetbrains.lsp.protocol.CodeAction("t", command = Command("x", "y"))),
      """{"command":$action,"isPreferred":true,"arguments":[1],"title":"t"}""" to
        CommandOrCodeAction.CodeAction(com.jetbrains.lsp.protocol.CodeAction("t", isPreferred = true, command = Command("x", "y"))),
      """{"title":"t","command":null}""" to CommandOrCodeAction.CodeAction(com.jetbrains.lsp.protocol.CodeAction("t")),
      """{"title":"t","command":"c","isPreferred":true}""" to CommandOrCodeAction.Command(Command("t", "c")),
    )
    for ((text, expected) in cases) {
      assertEquals(listOf(expected), wire(CodeActions.CodeActionRequest.resultSerializer, "[$text]"), text)
      assertEquals(expected, LSP.json.decodeFromString(CommandOrCodeAction.serializer(), text), text)
    }
    // not a string: a code action, whose command then fails
    for (text in listOf("""{"title":"t","command":1}""", """{title:"t",command:c}""", """{"title":"t","command":["c"]}""")) {
      assertIs<SerializationException>(runCatching { wire(CodeActions.CodeActionRequest.resultSerializer, "[$text]") }.exceptionOrNull(), text)
    }
    assertEquals(
      "Field 'title' is required for type with serial name 'com.jetbrains.lsp.protocol.Command', but it was missing",
      missing { wire(CodeActions.CodeActionRequest.resultSerializer, """[{"command":"c"}]""") },
    )
  }

  @Test
  fun `the first element picks the variant of a list, and a mixed list fails with the missing fields`() {
    assertEquals(
      "Field 'location' is required for type with serial name 'com.jetbrains.lsp.protocol.SymbolInformation', but it was missing",
      missing { wire(TextDocuments.DocumentSymbol.resultSerializer, "[${si()},${ds()}]") },
    )
    assertEquals(
      "Fields [range, selectionRange] are required for type with serial name 'com.jetbrains.lsp.protocol.DocumentSymbol', but they were missing",
      missing { wire(TextDocuments.DocumentSymbol.resultSerializer, "[${ds()},${si()}]") },
    )
    // `location` of a workspace symbol may lack its range, the one of a symbol information may not
    val partial = """{"name":"a","kind":5,"location":{"uri":"file:///a.kt"}}"""
    val ws = missing { wire(Workspace.Symbol.resultSerializer, "[${si(""","deprecated":false""")},$partial]") }
    assertTrue(ws.startsWith("Field 'range' is required"), ws)
    assertIs<WorkspaceSymbolResult.WorkspaceSymbols>(wire(Workspace.Symbol.resultSerializer, "[$partial,${si(""","deprecated":false""")}]"))
    // a `null` picker member still picks
    assertIs<DocumentSymbolResult.SymbolInformations>(runCatching { wire(TextDocuments.DocumentSymbol.resultSerializer, "[${si()}]") }.getOrThrow())
    val nullLocation = missing { wire(TextDocuments.DocumentSymbol.resultSerializer, "[${ds(""","location":null""")}]") }
    assertTrue(nullLocation.startsWith("Field 'location' is required"), nullLocation)
    assertIs<WorkspaceSymbolResult.SymbolInformations>(wire(Workspace.Symbol.resultSerializer, "[${si(""","deprecated":null""")}]"))
    val nullEdits = missing { wire(SemanticTokensRequests.SemanticTokensFullDeltaRequest.resultSerializer, """{"data":[1],"edits":null}""") }
    assertTrue(nullEdits.startsWith("Field 'edits' is required"), nullEdits)
  }

  // --- in a session: the union payload is decoded typed in the envelope pass ---

  @Test
  fun `a union result decodes in the envelope pass, a completion list or items by the peeked token`() = session {
    val doc = """{"textDocument":{"uri":"file:///a.kt"}}"""
    val position = """{"textDocument":{"uri":"file:///a.kt"},"position":{"line":1,"character":2}}"""
    val items = """[{"label":"a"},{"label":"b"}]"""
    // an object is a completion list, an array is items: one pass each
    val list = respond(CompletionRequestType, LSP.json.decodeFromString(CompletionParams.serializer(), position),
                       """{"isIncomplete":true,"items":$items}""")
    assertIs<CompletionResult.MaybeIncomplete>(list)
    val complete = respond(CompletionRequestType, LSP.json.decodeFromString(CompletionParams.serializer(), position), items)
    assertEquals(listOf("a", "b"), assertIs<CompletionResult.Complete>(complete).items.map { it.label })
    assertIs<InlineCompletionResult.ItemList>(
      respond(InlineCompletionRequestType, inlineParams(position), """{"items":[{"insertText":"x"}]}"""))
    assertIs<InlineCompletionResult.Items>(respond(InlineCompletionRequestType, inlineParams(position), """[{"insertText":"x"}]"""))
    // an array of bad items fails in the items variant alone, nothing else is tried
    val failure = assertFailsWith<Throwable> {
      respond(CompletionRequestType, LSP.json.decodeFromString(CompletionParams.serializer(), position), "[1]")
    }
    val cause = assertIs<SerializationException>(failure.cause, "$failure")
    assertTrue("at path: \$.result[0]" in cause.message!!, "the error path names the item: ${cause.message}")
    assertEquals(0, cause.suppressedExceptions.size, "no other variant tried")

    assertIs<DocumentSymbolResult.SymbolInformations>(
      respond(TextDocuments.DocumentSymbol, LSP.json.decodeFromString(DocumentSymbolParams.serializer(), doc), "[${si()}]"))
    assertIs<WorkspaceSymbolResult.WorkspaceSymbols>(
      respond(Workspace.Symbol, WorkspaceSymbolParams(query = "a"), "[${si()}]"))
    assertIs<SemanticTokensDeltaResult.Delta>(
      respond(SemanticTokensRequests.SemanticTokensFullDeltaRequest,
              LSP.json.decodeFromString(SemanticTokensDeltaParams.serializer(), """{"textDocument":{"uri":"file:///a.kt"},"previousResultId":"1"}"""),
              """{"resultId":"2","edits":[]}"""))
    val actions = respond(CodeActions.CodeActionRequest,
                          LSP.json.decodeFromString(CodeActionParams.serializer(), """{"textDocument":{"uri":"file:///a.kt"},"range":$r,"context":{"diagnostics":[]}}"""),
                          """[{"title":"t","command":"c"},{"title":"u","command":{"title":"x","command":"y"}}]""")
    assertIs<CommandOrCodeAction.Command>(actions!![0])
    assertIs<CommandOrCodeAction.CodeAction>(actions[1])
    assertNull(respond(TextDocuments.DocumentSymbol, LSP.json.decodeFromString(DocumentSymbolParams.serializer(), doc), "null"))
  }

  // --- helpers ---

  private fun inlineParams(position: String): InlineCompletionParams =
    LSP.json.decodeFromString(InlineCompletionParams.serializer(), position.dropLast(1) + ""","context":{"triggerKind":1}}""")

  private fun <T> wire(serializer: DeserializationStrategy<T>, payload: String): T? =
    LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0","id":1,"result":$payload}""".encodeToByteArray()).read().decodeResult(serializer)

  /**
   * The message of a union member that is really missing. Not a [MissingFieldException], which `decodeOrPlain` would
   * retry as a nullable property with no `null` default.
   */
  private fun missing(block: () -> Any?): String {
    val x = assertFailsWith<SerializationException> { block() }
    assertTrue(x !is MissingFieldException, "no plain retry: $x")
    return x.message!!
  }

  private class Session(
    val toServer: Channel<LspWireBody>,
    val fromServer: Channel<LspWireOutgoing>,
    val client: LspClient,
    scope: CoroutineScope,
  ) : CoroutineScope by scope {
    /**
     * Sends [type] from the client, answers it with [result] after `id`, and checks that the caller got the value a
     * read of the response gives for the request's result serializer.
     */
    suspend fun <P, R> respond(type: RequestType<P, R, *>, params: P, result: String): R {
      val call = async { runCatching { client.request(type, params) } }
      val id = fromServer.receive().json().jsonObject["id"]!!.jsonPrimitive.content
      val response = LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0","id":$id,"result":$result}""".encodeToByteArray())
      toServer.send(response)
      val value = call.await().getOrThrow()
      assertEquals(value, response.read().decodeResult(type.resultSerializer), "the value of $result")
      return value
    }
  }

  private fun session(test: suspend Session.() -> Unit) = runTest {
    val toServer = Channel<LspWireBody>(Channel.UNLIMITED)
    val fromServer = Channel<LspWireOutgoing>(Channel.UNLIMITED)
    val client = CompletableDeferred<LspClient>()
    val server = launch {
      withLsp(toServer, fromServer, lspHandlers {}) { client.complete(it); awaitCancellation() }
    }
    Session(toServer, fromServer, client.await(), this).test()
    server.cancelAndJoin()
  }

  // --- reference copies of the tree serializers (see UnionSerializersTest) ---

  private class RefDocumentSymbolResult : JsonContentPolymorphicSerializer<DocumentSymbolResult>(DocumentSymbolResult::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<DocumentSymbolResult> =
      if (element is JsonArray && element.isNotEmpty() && element[0].let { it is JsonObject && it.containsKey("location") })
        DocumentSymbolResult.SymbolInformations.serializer()
      else DocumentSymbolResult.DocumentSymbols.serializer()
  }

  private class RefWorkspaceSymbolResult : JsonContentPolymorphicSerializer<WorkspaceSymbolResult>(WorkspaceSymbolResult::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<WorkspaceSymbolResult> =
      if (element is JsonArray && element.isNotEmpty() && element[0].let { it is JsonObject && it.containsKey("deprecated") })
        WorkspaceSymbolResult.SymbolInformations.serializer()
      else WorkspaceSymbolResult.WorkspaceSymbols.serializer()
  }

  private class RefSemanticTokensDeltaResult : JsonContentPolymorphicSerializer<SemanticTokensDeltaResult>(SemanticTokensDeltaResult::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<SemanticTokensDeltaResult> =
      if (element is JsonObject && element.containsKey("edits")) SemanticTokensDeltaResult.Delta.serializer()
      else SemanticTokensDeltaResult.Full.serializer()
  }
}

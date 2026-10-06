package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.implementation.withLsp
import com.jetbrains.lsp.protocol.ExitNotificationType
import com.jetbrains.lsp.protocol.Workspace
import com.jetbrains.lsp.protocol.WorkspaceFolder
import com.jetbrains.lsp.protocol.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Methods without params ([NoValueSerializer][com.jetbrains.lsp.protocol.NoValueSerializer]) take any params value:
 * absent, `null` or `{}`. A `Unit` params type would fail on wasm for the first two, where the `null` to `Unit` cast is
 * checked.
 */
class NoValueParamsTest {
  private val paramsVariants = listOf("", ""","params":null""", ""","params":{}""")

  @Test
  fun `workspaceFolders is answered with params absent, null or empty`() = runTest {
    val folders = listOf(WorkspaceFolder(URI("file:///w"), "w"))
    val handlers = lspHandlers {
      request(Workspace.WorkspaceFolders) { folders }
    }
    val toClient = Channel<LspWireBody>(Channel.UNLIMITED)
    val fromClient = Channel<LspWireOutgoing>(Channel.UNLIMITED)
    val session = launch { withLsp(toClient, fromClient, handlers) { awaitCancellation() } }
    for ((id, params) in paramsVariants.withIndex()) {
      toClient.send(body("""{"jsonrpc":"2.0","id":$id,"method":"workspace/workspaceFolders"$params}"""))
      val answer = fromClient.receive().json().jsonObject
      assertEquals("""[{"uri":"file:///w","name":"w"}]""", answer["result"].toString(), "params: '$params', answer: $answer")
    }
    session.cancel()
  }

  @Test
  fun `exit is handled with params absent, null or empty`() = runTest {
    for (params in paramsVariants) {
      val handled = CompletableDeferred<Unit>()
      val handlers = lspHandlers {
        notification(ExitNotificationType) { handled.complete(Unit) }
      }
      val toClient = Channel<LspWireBody>(Channel.UNLIMITED)
      val fromClient = Channel<LspWireOutgoing>(Channel.UNLIMITED)
      toClient.send(body("""{"jsonrpc":"2.0","method":"exit"$params}"""))
      // exit ends the session loop; the body waits for the handler
      withLsp(toClient, fromClient, handlers) { handled.await() }
      assertTrue(handled.isCompleted, "params: '$params'")
    }
  }

  private fun body(json: String): LspWireBody = LspWireCodec.decodeFrameBody(json.encodeToByteArray())
}

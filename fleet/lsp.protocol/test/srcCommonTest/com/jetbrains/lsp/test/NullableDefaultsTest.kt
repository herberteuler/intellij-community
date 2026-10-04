package com.jetbrains.lsp.test

import com.jetbrains.lsp.protocol.ApplyEditRequests
import com.jetbrains.lsp.protocol.CallHierarchyRequests
import com.jetbrains.lsp.protocol.CodeAction
import com.jetbrains.lsp.protocol.CodeActions
import com.jetbrains.lsp.protocol.CodeLenses
import com.jetbrains.lsp.protocol.Command
import com.jetbrains.lsp.protocol.Commands
import com.jetbrains.lsp.protocol.CompletionItem
import com.jetbrains.lsp.protocol.CompletionList
import com.jetbrains.lsp.protocol.CompletionRequestType
import com.jetbrains.lsp.protocol.CompletionResolveRequestType
import com.jetbrains.lsp.protocol.DeclarationRequestType
import com.jetbrains.lsp.protocol.DefinitionRequestType
import com.jetbrains.lsp.protocol.Diagnostic
import com.jetbrains.lsp.protocol.Diagnostics
import com.jetbrains.lsp.protocol.DidChangeTextDocumentParams
import com.jetbrains.lsp.protocol.DocumentColors
import com.jetbrains.lsp.protocol.DocumentLinks
import com.jetbrains.lsp.protocol.DocumentSymbol
import com.jetbrains.lsp.protocol.DocumentSymbolRequest
import com.jetbrains.lsp.protocol.DocumentSync
import com.jetbrains.lsp.protocol.ExitNotificationType
import com.jetbrains.lsp.protocol.FoldingRangeRequestType
import com.jetbrains.lsp.protocol.FormattingRequestType
import com.jetbrains.lsp.protocol.HoverRequestType
import com.jetbrains.lsp.protocol.Implementation
import com.jetbrains.lsp.protocol.Initialize
import com.jetbrains.lsp.protocol.Initialized
import com.jetbrains.lsp.protocol.InlayHints
import com.jetbrains.lsp.protocol.InlineCompletionItem
import com.jetbrains.lsp.protocol.InlineCompletionList
import com.jetbrains.lsp.protocol.InlineCompletionRequestType
import com.jetbrains.lsp.protocol.InsertReplaceEdit
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.Location
import com.jetbrains.lsp.protocol.LocationLink
import com.jetbrains.lsp.protocol.LogMessageNotificationType
import com.jetbrains.lsp.protocol.LogTraceNotificationType
import com.jetbrains.lsp.protocol.MarkupContent
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.OnTypeFormattingRequestType
import com.jetbrains.lsp.protocol.PrepareRenameRequestType
import com.jetbrains.lsp.protocol.RangeFormattingRequestType
import com.jetbrains.lsp.protocol.RangesFormattingRequestType
import com.jetbrains.lsp.protocol.ReferenceRequestType
import com.jetbrains.lsp.protocol.RenameRequestType
import com.jetbrains.lsp.protocol.RequestType
import com.jetbrains.lsp.protocol.SelectionRangeRequestType
import com.jetbrains.lsp.protocol.SemanticTokens
import com.jetbrains.lsp.protocol.SemanticTokensDelta
import com.jetbrains.lsp.protocol.SemanticTokensRequests
import com.jetbrains.lsp.protocol.ServerCapabilities
import com.jetbrains.lsp.protocol.SetTraceNotificationType
import com.jetbrains.lsp.protocol.ShowDocument
import com.jetbrains.lsp.protocol.ShowMessageNotificationType
import com.jetbrains.lsp.protocol.Shutdown
import com.jetbrains.lsp.protocol.SignatureHelpRequest
import com.jetbrains.lsp.protocol.SymbolInformation
import com.jetbrains.lsp.protocol.Telemetry
import com.jetbrains.lsp.protocol.TextDocumentClientCapabilities
import com.jetbrains.lsp.protocol.TextDocuments
import com.jetbrains.lsp.protocol.TextEdit
import com.jetbrains.lsp.protocol.TypeDefinitionRequestType
import com.jetbrains.lsp.protocol.TypeHierarchyRequests
import com.jetbrains.lsp.protocol.Window
import com.jetbrains.lsp.protocol.Workspace
import com.jetbrains.lsp.protocol.WorkspaceEdit
import com.jetbrains.lsp.protocol.WorkspaceSymbol
import com.jetbrains.lsp.protocol.WorkspaceSymbolRequests
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [NullableDefaults] over every request and notification type of this module: `LSP.decodeJson` decodes a missing
 * nullable member as `null` only when it has a `null` default. The walker itself is checked on small types first.
 */
class NullableDefaultsTest {
  @Serializable
  data class Leaf(val required: String, val bad: String?, val good: String? = null)

  @Serializable
  @JvmInline
  value class Wrapped(val leaf: Leaf)

  @Serializable
  data class Tree(
    val list: List<Leaf?> = emptyList(),
    val map: Map<String, Wrapped> = emptyMap(),
    val parent: Tree? = null,
    val nullableList: List<Leaf>? = null,
  )

  @Serializable
  sealed interface Shape {
    @Serializable
    data class Circle(val radius: Int?) : Shape
  }

  @Test
  fun `the walker finds a nullable member with no default through lists, maps, inline values and nullable members`() {
    val roots = listOf("tree" to Tree.serializer().descriptor,
                       "nullable list" to ListSerializer(Tree.serializer().nullable).descriptor,
                       "map" to MapSerializer(String.serializer(), Leaf.serializer()).descriptor)
    val violations = NullableDefaults.violations(roots)
    assertEquals(listOf("${Leaf.serializer().descriptor.serialName}.bad"), violations.map { it.member })
    assertEquals("tree > list > 0 > bad", violations.single().path)
  }

  @Test
  fun `the walker goes into the subclasses of a sealed type`() {
    assertEquals(listOf("${Shape.Circle.serializer().descriptor.serialName}.radius"),
                 NullableDefaults.violations(listOf("shape" to Shape.serializer().descriptor)).map { it.member })
  }

  @Serializable
  data class Node(val next: Node? = null, val children: List<Node> = emptyList())

  @Test
  fun `the walker ends on a recursive type`() {
    assertEquals(emptyList(), NullableDefaults.violations(listOf("node" to Node.serializer().descriptor)))
  }

  @Test
  fun `the assertion lists the violations and skips the allowed ones`() {
    val type = NotificationType("test/leaf", Leaf.serializer())
    val error = assertFailsWith<AssertionError> {
      NullableDefaults.assertNullableMembersDefaultToNull(emptyList(), listOf(type))
    }
    assertTrue(error.message!!.contains("${Leaf.serializer().descriptor.serialName}.bad  (test/leaf params > bad)"), error.message)
    NullableDefaults.assertNullableMembersDefaultToNull(emptyList(), listOf(type),
                                                        allowed = setOf("${Leaf.serializer().descriptor.serialName}.bad"))
  }

  @Test
  fun `every nullable member of the protocol types defaults to null`() {
    NullableDefaults.assertNullableMembersDefaultToNull(protocolRequests, protocolNotifications, unionVariants)
  }

  @Test
  fun `the walk of the protocol types reaches the deep members`() {
    val classes = NullableDefaults.classesUnder(NullableDefaults.rootsOf(protocolRequests, protocolNotifications) +
                                                unionVariants.map { it.serialName to it })
    val deep = listOf(TextDocumentClientCapabilities.serializer(), ServerCapabilities.serializer(), WorkspaceEdit.serializer(),
                      Diagnostic.serializer(), CompletionItem.serializer(), DidChangeTextDocumentParams.serializer())
    for (serializer in deep) assertTrue(serializer.descriptor.serialName in classes, serializer.descriptor.serialName)
    assertTrue(classes.size > 150, "${classes.size} classes")
  }

  companion object {
    /** Every [RequestType] of this module. A new one goes here. */
    val protocolRequests: List<RequestType<*, *, *>> = listOf(
      ApplyEditRequests.ApplyEdit,
      CallHierarchyRequests.PrepareCallHierarchyRequestType,
      CallHierarchyRequests.IncomingCallsRequestType,
      CallHierarchyRequests.OutgoingCallsRequestType,
      CodeActions.CodeActionRequest,
      CodeActions.ResolveCodeAction,
      CodeLenses.CodeLensRequestType,
      CodeLenses.ResolveCodeLens,
      CompletionRequestType,
      CompletionResolveRequestType,
      DeclarationRequestType,
      DefinitionRequestType,
      TypeDefinitionRequestType,
      Diagnostics.DocumentDiagnosticRequestType,
      Diagnostics.DocumentCompilationErrorsRequestType,
      Diagnostics.Refresh,
      DocumentColors.DocumentColor,
      DocumentLinks.DocumentLinkRequestType,
      DocumentLinks.ResolveDocumentLink,
      DocumentSync.WillSaveWaitUntil,
      Commands.ExecuteCommand,
      FoldingRangeRequestType,
      FormattingRequestType,
      RangeFormattingRequestType,
      OnTypeFormattingRequestType,
      RangesFormattingRequestType,
      HoverRequestType,
      Implementation.ImplementationRequest,
      Initialize,
      InlayHints.InlayHintRequestType,
      InlayHints.ResolveInlayHint,
      InlineCompletionRequestType,
      LSP.RegisterCapabilityRequestType,
      LSP.UnregisterCapabilityRequestType,
      SelectionRangeRequestType,
      SemanticTokensRequests.SemanticTokensFullRequest,
      SemanticTokensRequests.SemanticTokensRangeRequest,
      SemanticTokensRequests.SemanticTokensFullDeltaRequest,
      ShowDocument,
      Shutdown,
      SignatureHelpRequest,
      TextDocuments.DocumentSymbol,
      TextDocuments.DocumentHighlightRequestType,
      TypeHierarchyRequests.PrepareTypeHierarchyRequestType,
      TypeHierarchyRequests.SupertypesRequestType,
      TypeHierarchyRequests.SubtypesRequestType,
      Window.ShowMessageRequest,
      Window.CreateProgress,
      Workspace.WorkspaceFolders,
      Workspace.Configuration,
      Workspace.WillRenameFiles,
      Workspace.WillCreateFiles,
      Workspace.WillDeleteFiles,
      Workspace.RefreshCodeLenses,
      Workspace.RefreshInlayHints,
      Workspace.RefreshSemanticTokens,
      Workspace.RefreshFoldingRanges,
      Workspace.TextDocumentContent,
      Workspace.RefreshTextDocumentContent,
      Workspace.Symbol,
      Workspace.ResolveSymbol,
      ReferenceRequestType,
      PrepareRenameRequestType,
      RenameRequestType,
      WorkspaceSymbolRequests.WorkspaceSymbolRequest,
      WorkspaceSymbolRequests.WorkspaceSymbolResolveRequest,
      DocumentSymbolRequest,
    )

    /** Every [NotificationType] of this module. A new one goes here. */
    val protocolNotifications: List<NotificationType<*>> = listOf(
      Diagnostics.PublishDiagnosticsNotificationType,
      DocumentSync.DidOpen,
      DocumentSync.DidChange,
      DocumentSync.WillSave,
      DocumentSync.DidSave,
      DocumentSync.DidClose,
      Initialized,
      ExitNotificationType,
      LSP.ProgressNotificationType,
      LSP.CancelNotificationType,
      LogMessageNotificationType,
      ShowMessageNotificationType,
      LogTraceNotificationType,
      SetTraceNotificationType,
      Telemetry.Event,
      Window.CancelProgress,
      Workspace.DidChangeWorkspaceFolders,
      Workspace.DidChangeConfiguration,
      Workspace.DidRenameFiles,
      Workspace.DidCreateFiles,
      Workspace.DidDeleteFiles,
      Workspace.DidChangeWatchedFiles,
    )

    /** The variants of the hand-written unions, whose descriptors have no elements to walk into. */
    val unionVariants = listOf(
      Command.serializer().descriptor,
      CodeAction.serializer().descriptor,
      CompletionList.serializer().descriptor,
      CompletionItem.serializer().descriptor,
      DocumentSymbol.serializer().descriptor,
      SymbolInformation.serializer().descriptor,
      WorkspaceSymbol.serializer().descriptor,
      SemanticTokens.serializer().descriptor,
      SemanticTokensDelta.serializer().descriptor,
      Location.serializer().descriptor,
      LocationLink.serializer().descriptor,
      TextEdit.serializer().descriptor,
      InsertReplaceEdit.serializer().descriptor,
      MarkupContent.serializer().descriptor,
      InlineCompletionList.serializer().descriptor,
      InlineCompletionItem.serializer().descriptor,
    )
  }
}

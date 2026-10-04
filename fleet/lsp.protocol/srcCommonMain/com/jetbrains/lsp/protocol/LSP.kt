package com.jetbrains.lsp.protocol

import fleet.util.isValidUriString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.modules.SerializersModule
import org.jetbrains.annotations.Nls
import kotlin.jvm.JvmInline

/**
 * URI following the URI specification, so special spaces (like spaces) are encoded.
 *
 * It may not correspond to URIs which are used inside IntelliJ
 */
@Serializable
@JvmInline
value class URI(val uri: String) {
    init {
        /**
         * We need to have consistent URIs as they are used as keys in the analyzer
         */
        require(uri.isValidUriString()) { "Invalid URI: $uri" }
    }

    object Schemas {
        const val FILE: String = "file"
        const val JRT: String = "jrt"
        const val JAR: String = "jar"
        const val ZIP: String = "zip"
        const val UNTITLED: String = "untitled"
    }
}


@Serializable
data class RegularExpressionsClientCapabilities(
    val engine: String,
    val version: String? = null,
)

@Serializable
data class Position(
    /**
     * Line position in a document (zero-based).
     */
    val line: Int,

    /**
     * Character offset on a line in a document (zero-based). The meaning of this
     * offset is determined by the negotiated `PositionEncodingKind`.
     *
     * If the character value is greater than the line length it defaults back
     * to the line length.
     */
    val character: Int,
) : Comparable<Position> {

    override fun toString(): String {
        return "$line:$character"
    }

    override fun compareTo(other: Position): Int {
        return compareValuesBy(this, other, { it.line }, { it.character })
    }

    companion object {
        val ZERO: Position = Position(0, 0)

        /**
         * To avoid computing the line size, we use a very large number that will hopefully be reached by the line size.
         *
         * We do not use `Int.MAX_VALUE` directly to avoid possible overflows in operations on the client side.
         */
        const val EOL_INDEX: Int = Int.MAX_VALUE / 4

        fun lineStart(line: Int): Position = Position(line, 0)

        fun lineEnd(line: Int): Position = Position(line, EOL_INDEX)
    }
}

fun Position.offsetCharacter(offset: Int): Position = Position(line, character + offset)

operator fun Position.plus(other: Position): Position = Position(line + other.line, character + other.character)

operator fun Position.minus(other: Position): Position = Position(line - other.line, character - other.character)

@Serializable
enum class PositionEncodingKind {
    /**
     * Character offsets count UTF-8 code units (e.g., bytes).
     */
    @SerialName("utf-8")
    UTF8,

    /**
     * Character offsets count UTF-16 code units.
     *
     * This is the default and must always be supported
     * by servers.
     */
    @SerialName("utf-16")
    UTF16,

    /**
     * Character offsets count UTF-32 code units.
     *
     * Implementation note: these are the same as Unicode code points,
     * so this `PositionEncodingKind` may also be used for an
     * encoding-agnostic representation of character offsets.
     */
    @SerialName("utf-32")
    UTF32
}

/**
 * @see <a href="https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#range">Range (LSP spec)</a>
 */
@Serializable
data class Range(
    /**
     * The range's start position.
     */
    val start: Position,

    /**
     * The range's end position.
     */
    val end: Position,
) {
    override fun toString(): String {
        return "[$start, $end]"
    }

    /**
     * Extends the range so it includes also all remaining characters in the last line including the line break
     */
    fun toTheLineEndWithLineBreak(): Range = Range(start, Position(end.line + 1, 0))

    operator fun contains(position: Position): Boolean = position in start..end

    companion object {
        val BEGINNING: Range = Range(Position.ZERO, Position.ZERO)

        fun empty(position: Position): Range = Range(position, position)

        fun fromPositionTillLineEnd(from: Position): Range = Range(from, Position.lineEnd(from.line))

        fun fromLineStartTillPosition(till: Position): Range = Range(Position.lineStart(till.line), till)

        fun fullLine(line: Int): Range = Range(Position.lineStart(line), Position.lineEnd(line))
    }
}

fun Range.intersects(other: Range): Boolean =
    start <= other.end && end >= other.start

fun Range.isSingleLine(): Boolean =
  start.line == end.line

/**
 * Checks whether current selection range is empty
 */
fun Range.isEmpty(): Boolean =
  start == end

@Serializable
@JvmInline
value class DocumentUri(val uri: URI)

@Serializable
data class TextDocumentItem(
    /**
     * The text document's URI.
     */
    val uri: DocumentUri,

    /**
     * The text document's language identifier.
     */
    val languageId: String,

    /**
     * The version number of this document (it will increase after each
     * change, including undo/redo).
     */
    val version: Int,

    /**
     * The content of the opened text document.
     */
    val text: String,
)

@Serializable
data class TextDocumentIdentifier(
    /**
     * The text document's URI.
     */
    val uri: DocumentUri,
    /**
     * The version number of this document.
     *
     * The version number of a document will increase after each change,
     * including undo/redo. The number doesn't need to be consecutive.
     */
    val version: Int? = null,
)

interface TextDocumentPositionParams {
    /**
     * The text document.
     */
    val textDocument: TextDocumentIdentifier

    /**
     * The position inside the text document.
     */
    val position: Position
}

/**
 * A document filter denotes a document through properties like language, scheme or pattern.
 * An example is a filter that applies to TypeScript files on disk.
 * Another example is a filter that applies to JSON files with name package.json:
 *
 * ```json
 * { language: 'typescript', scheme: 'file' }
 * { language: 'json', pattern: '** /package.json' }
 * ```
 *
 * Please note that for a document filter to be valid at least one of the properties for language, scheme, or pattern must be set.
 * To keep the type definition simple all properties are marked as optional.
 */
@Serializable
data class DocumentFilter(
    /**
     * A language id, like `typescript`.
     */
    val language: String? = null,

    /**
     * A Uri scheme, like `file` or `untitled`.
     */
    val scheme: String? = null,

    /**
     * A glob pattern, like `*.{ts,js}`.
     *
     * Glob patterns can have the following syntax:
     * - `*` to match one or more characters in a path segment
     * - `?` to match on one character in a path segment
     * - `**` to match any number of path segments, including none
     * - `{}` to group sub patterns into an OR expression
     *   matches all TypeScript and JavaScript files)
     * - `[]` to declare a range of characters to match in a path segment
     *   (e.g., `example.[0-9]` to match on `example.0`, `example.1`, …)
     * - `[!...]` to negate a range of characters to match in a path segment
     *   (e.g., `example.[!0-9]` to match on `example.a`, `example.b`, but
     *   not `example.0`)
     */
    val pattern: String? = null,
) {
    init {
        require(language != null || scheme != null || pattern != null) {
            "DocumentFilter must have at least one property set (language, scheme or pattern)"
        }
    }
}

@Serializable
@JvmInline
value class DocumentSelector(val filters: List<DocumentFilter>) {
    constructor(vararg filters: DocumentFilter) : this(filters.toList())
}

@Serializable(with = TextEditSerializer::class)
data class TextEdit(
    /**
     * The range of the text document to be manipulated. To insert
     * text into a document create a range where start == end.
     */
    val range: Range,

    /**
     * The string to be inserted. For delete operations use an
     * empty string.
     */
    val newText: String = "",

    /**
     * The snippet text (if SnippetTextEdit is supported).
     * If it's present, the newText field must be ignored.
     */
    val snippet: String? = null,

    /**
     * The actual annotation identifier.
     */
    val annotationId: ChangeAnnotationIdentifier? = null,
)

@Serializable
@JvmInline
value class ChangeAnnotationIdentifier(val id: String)

/**
 * Additional information that describes document changes.
 *
 * @since 3.16.0
 */
@Serializable
data class ChangeAnnotation(
    /**
     * A human-readable string describing the actual change. The string
     * is rendered prominently in the user interface.
     */
    val label: String,

    /**
     * A flag which indicates that user confirmation is needed
     * before applying the change.
     */
    val needsConfirmation: Boolean? = null,

    /**
     * A human-readable string which is rendered less prominently in
     * the user interface.
     */
    val description: String? = null,
)

@Serializable(with = TextDocumentEditSerializer::class)
data class TextDocumentEdit(
    /**
     * The text document to change.
     */
    val textDocument: TextDocumentIdentifier,

    /**
     * The edits to be applied.
     *
     * @since 3.16.0 - support for AnnotatedTextEdit. This is guarded by the
     * client capability `workspace.workspaceEdit.changeAnnotationSupport`
     */
    val edits: List<TextEdit>,
): FileChange

/**
 * One element of a result that is `Location[] | LocationLink[]`, e.g. of `textDocument/definition`.
 *
 * An object with a `targetUri` member is a [LocationLink], any other object a [Location]. One answer must not mix the two
 * kinds, also across partial results.
 */
@Serializable(with = LocationOrLink.Serializer::class)
sealed interface LocationOrLink {
    class Serializer : JsonContentPolymorphicSerializer<LocationOrLink>(LocationOrLink::class) {
        override fun selectDeserializer(element: JsonElement): DeserializationStrategy<LocationOrLink> {
            return when (element) {
                is JsonObject -> if (element.containsKey("targetUri")) LocationLink.serializer() else Location.serializer()
                else -> throw SerializationException("Expected either Location or LocationLink, got $element")
            }
        }
    }
}

@Serializable
data class Location(
    val uri: DocumentUri,
    val range: Range,
) : LocationOrLink

@Serializable
data class LocationLink(
    /**
     * Span of the origin of this link.
     *
     * Used as the underlined span for mouse interaction. Defaults to the word
     * range at the mouse position.
     */
    val originSelectionRange: Range? = null,

    /**
     * The target resource identifier of this link.
     */
    val targetUri: DocumentUri,

    /**
     * The full target range of this link. If the target for example is a symbol
     * then target range is the range enclosing this symbol not including
     * leading/trailing whitespace but everything else like comments. This
     * information is typically used to highlight the range in the editor.
     */
    val targetRange: Range,

    /**
     * The range that should be selected and revealed when this link is being
     * followed, e.g the name of a function. Must be contained by the
     * `targetRange`. See also `DocumentSymbol#range`
     */
    val targetSelectionRange: Range,
) : LocationOrLink

@Serializable
data class Command(
    /**
     * Title of the command, like `save`.
     */
    val title: @Nls String,

    /**
     * The identifier of the actual command handler.
     */
    val command: String,

    /**
     * Arguments that the command handler should be
     * invoked with.
     */
    val arguments: List<JsonElement>? = null,
)

@Serializable
enum class MarkupKind {
    /**
     * Plain text is supported as a content format
     */
    @SerialName("plaintext")
    PlainText,

    /**
     * Markdown is supported as a content format
     */
    @SerialName("markdown")
    Markdown
}

/**
 * Describes the content type that a client supports in various
 * result literals like `Hover`, `ParameterInfo` or `CompletionItem`.
 *
 * Please note that `MarkupKinds` must not start with a `$`. This kinds
 * are reserved for internal usage.
 */
@Serializable
enum class MarkupKindType {
    /**
     * Plain text is supported as a content format
     */
    @SerialName("plaintext")

    PlaintText,

    /**
     * Markdown is supported as a content format
     */
    @SerialName("markdown")
    Markdown
}

/**
 * A `MarkupContent` literal represents a string value which content is
 * interpreted base on its kind flag. Currently the protocol supports
 * `plaintext` and `markdown` as markup kinds.
 *
 * If the kind is `markdown` then the value can contain fenced code blocks like
 * in GitHub issues.
 *
 * Here is an example how such a string can be constructed using
 * JavaScript / TypeScript:
 * ```typescript
 * let markdown: MarkdownContent = {
 * 	kind: MarkupKind.Markdown,
 * 	value: [
 * 		'# Header',
 * 		'Some text',
 * 		'```typescript',
 * 		'someCode();',
 * 		'```'
 * 	].join('\n')
 * };
 * ```
 *
 * *Please Note* that clients might sanitize the return markdown. A client could
 * decide to remove HTML from the markdown to avoid script execution.
 */
@Serializable
data class MarkupContent(
    /**
     * The type of the Markup
     */
    val kind: MarkupKindType,

    /**
     * The content itself
     */
    val value: String,
)

@Serializable
data class MarkdownClientCapabilities(
    /**
     * The name of the parser.
     */
    val parser: String,

    /**
     * The version of the parser.
     */
    val version: String? = null,

    /**
     * A list of HTML tags that the client allows / supports in Markdown.
     *
     * @since 3.17.0
     */
    val allowedTags: List<String>? = null,
)

@Serializable
data class CreateFileOptions(
    /**
     * Overwrite existing file. Overwrite wins over `ignoreIfExists`
     */
    val overwrite: Boolean? = null,

    /**
     * Ignore if exists.
     */
    val ignoreIfExists: Boolean? = null,
)

@Serializable(with = FileChangeSerializer::class)
sealed interface FileChange

@Serializable
data class CreateFile(
    /**
     * The resource to create.
     */
    val uri: DocumentUri,

    /**
     * Additional options
     */
    val options: CreateFileOptions? = null,

    /**
     * An optional annotation identifier describing the operation.
     *
     * @since 3.16.0
     */
    val annotationId: ChangeAnnotationIdentifier? = null,
) : FileChange

@Serializable
data class RenameFileOptions(
    /**
     * Overwrite target if existing. Overwrite wins over `ignoreIfExists`
     */
    val overwrite: Boolean? = null,

    /**
     * Ignores if target exists.
     */
    val ignoreIfExists: Boolean? = null,
)

@Serializable
data class RenameFile(
    /**
     * The old (existing) location.
     */
    val oldUri: DocumentUri,

    /**
     * The new location.
     */
    val newUri: DocumentUri,

    /**
     * Rename options.
     */
    val options: RenameFileOptions? = null,

    /**
     * An optional annotation identifier describing the operation.
     *
     * @since 3.16.0
     */
    val annotationId: ChangeAnnotationIdentifier? = null,
) : FileChange

@Serializable
data class DeleteFileOptions(
    /**
     * Delete the content recursively if a folder is denoted.
     */
    val recursive: Boolean? = null,

    /**
     * Ignore the operation if the file doesn't exist.
     */
    val ignoreIfNotExists: Boolean? = null,
)

@Serializable
data class DeleteFile(
    /**
     * The file to delete.
     */
    val uri: DocumentUri,

    /**
     * Delete options.
     */
    val options: DeleteFileOptions? = null,

    /**
     * An optional annotation identifier describing the operation.
     *
     * @since 3.16.0
     */
    val annotationId: ChangeAnnotationIdentifier? = null,
) : FileChange

@Serializable
data class WorkspaceEdit(
  /**
     * Holds changes to existing resources.
     */
    val changes: Map<DocumentUri, List<TextEdit>>? = null,

  /**
     * Depending on the client capability `workspace.workspaceEdit.resourceOperations`,
     * document changes are either an array of `TextDocumentEdit`s to express changes
     * to different text documents where each text document edit addresses a specific
     * version of a text document, or it can contain `TextDocumentEdit`s mixed with
     * create, rename and delete file / folder operations.
     *
     * Whether a client supports versioned document edits is expressed via
     * `workspace.workspaceEdit.documentChanges` client capability.
     *
     * If a client neither supports `documentChanges` nor
     * `workspace.workspaceEdit.resourceOperations`, only plain `TextEdit`s
     * using the `changes` property are supported.
     */
    val documentChanges: List<FileChange>? = null,

  /**
     * A map of change annotations that can be referenced in `AnnotatedTextEdit`s
     * or create, rename and delete file / folder operations.
     *
     * Whether clients honor this property depends on the client capability
     * `workspace.changeAnnotationSupport`.
     *
     * @since 3.16.0
     */
    val changeAnnotations: Map<ChangeAnnotationIdentifier, ChangeAnnotation>? = null,
)

@Serializable
data class WorkspaceEditClientCapabilities(
    /**
     * The client supports versioned document changes in `WorkspaceEdit`s
     */
    val documentChanges: Boolean? = null,

    /**
     * The resource operations the client supports. Clients should at least
     * support 'create', 'rename' and 'delete' files and folders.
     *
     * @since 3.13.0
     */
    val resourceOperations: List<ResourceOperationKind>? = null,

    /**
     * The failure handling strategy of a client if applying the workspace edit
     * fails.
     *
     * @since 3.13.0
     */
    val failureHandling: FailureHandlingKind? = null,

    /**
     * Whether the client normalizes line endings to the client specific
     * setting. If set to `true`, the client will normalize line ending characters
     * in a workspace edit to the client-specific newline character(s).
     *
     * @since 3.16.0
     */
    val normalizesLineEndings: Boolean? = null,

    /**
     * Whether the client in general supports change annotations on text edits,
     * create file, rename file, and delete file changes.
     *
     * @since 3.16.0
     */
    val changeAnnotationSupport: ChangeAnnotationSupport? = null,

    /**
     * Whether the client supports snippets as text edits.
     *
     * @since 3.18.0
     */
    val snippetEditSupport: Boolean? = null,
)

@Serializable
data class ChangeAnnotationSupport(
    /**
     * Whether the client groups edits with equal labels into tree nodes,
     * for instance, all edits labeled with "Changes in Strings" would
     * be a tree node.
     */
    val groupsOnLabel: Boolean? = null,
)

@Serializable
enum class ResourceOperationKind {
    @SerialName("create")
    Create,

    @SerialName("rename")
    Rename,

    @SerialName("delete")
    Delete
}

@Serializable
enum class FailureHandlingKind {
    /**
     * Applying the workspace change is simply aborted if one of the changes
     * provided fails. All operations executed before the failing operation
     * stay executed.
     */
    @SerialName("abort")
    Abort,

    /**
     * All operations are executed transactional. That means they either all
     * succeed or no changes at all are applied to the workspace.
     */
    @SerialName("transactional")
    Transactional,

    /**
     * If the workspace edit contains only textual file changes they are
     * executed transactional. If resource changes (create, rename or delete
     * file) are part of the change the failure handling strategy is abort.
     */
    @SerialName("textOnlyTransactional")
    TextOnlyTransactional,

    /**
     * The client tries to undo the operations already executed. But there is no
     * guarantee that this is succeeding.
     */
    @SerialName("undo")
    Undo
}

// todo: use 'kind' as descriminant in serialization
@Serializable
sealed interface WorkDoneProgress {
    @SerialName("begin")
    @Serializable
    data class Begin(
        val title: String,
        val cancellable: Boolean? = null,
        val message: String? = null,
        val percentage: Int? = null,
    ) : WorkDoneProgress {
        @Deprecated("Use `WorkDoneProgress.serializer()` instead to have a proper `kind` field set", level = DeprecationLevel.ERROR)
        companion object
    }

    @SerialName("report")
    @Serializable
    data class Report(
        val cancellable: Boolean? = null,
        val message: String? = null,
        val percentage: Int? = null,
    ) : WorkDoneProgress {
        @Deprecated("Use `WorkDoneProgress.serializer()` instead to have a proper `kind` field set", level = DeprecationLevel.ERROR)
        private companion object
    }

    @SerialName("end")
    @Serializable
    data class End(
        val message: String? = null,
    ) : WorkDoneProgress {
        @Deprecated("Use `WorkDoneProgress.serializer()` instead to have a proper `kind` field set", level = DeprecationLevel.ERROR)
        private companion object
    }
}

/**
 * @see <a href="https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workDoneProgressParams">workDoneProgressParams (LSP spec)</a>
 */
interface WorkDoneProgressParams {
    /**
     * An optional token that a server can use to report work done progress.
     */
    val workDoneToken: ProgressToken?
}

interface WorkDoneProgressOptions {
    val workDoneProgress: Boolean?
}

interface PartialResultParams {
    /**
     * An optional token that a server can use to report partial results (e.g., streaming) to the client.
     */
    val partialResultToken: ProgressToken?
}

@Serializable
enum class TraceValue {
    @SerialName("off")
    Off,

    @SerialName("messages")
    Messages,

    @SerialName("verbose")
    Verbose
}

@Serializable
data class ClientInfo(
    /**
     * The name of the client as defined by the client.
     */
    val name: String,

    /**
     * The client's version as defined by the client.
     */
    val version: String? = null,
)

@Serializable
data class StaleRequestSupport(
    /**
     * The client will actively cancel the request.
     */
    val cancel: Boolean,

    /**
     * The list of requests for which the client will retry the request if
     * it receives a response with error code `ContentModified`.
     */
    val retryOnContentModified: List<String>,
)

@Serializable
data class WorkspaceFolder(
    /**
     * The associated URI for this workspace folder.
     */
    val uri: URI,

    /**
     * The name of the workspace folder. Used to refer to this
     * workspace folder in the user interface.
     */
    val name: String,
)

@Serializable
data class Registration(
    val id: String,
    val method: String,
    val registerOptions: JsonElement? = null,
)

@Serializable
data class RegistrationParams(
    val registrations: List<Registration>,
)

/**
 * Static registration options to be returned in the initialize request.
 */

interface StaticRegistrationOptions {
    /**
     * The id used to register the request. The id can be used to deregister
     * the request again. See also Registration#id.
     */
    val id: String?
}

/**
 * General text document registration options.
 */
interface TextDocumentRegistrationOptions {
    /**
     * A document selector to identify the scope of the registration. If set to
     * null the document selector provided on the client side will be used.
     */
    val documentSelector: DocumentSelector?
}

@Serializable
data class Unregistration(
    /**
     * The id used to unregister the request or notification. Usually an id
     * provided during the register request.
     */
    val id: String,

    /**
     * The method / capability to unregister for.
     */
    val method: String,
)

@Serializable
data class UnregistrationParams(
    /**
     * This should correctly be named `unregistrations`. However changing this
     * is a breaking change and needs to wait until we deliver a 4.x version
     * of the specification.
     */
    @SerialName("unregisterations")
    val unregistrations: List<Unregistration>,
)

object LSP {
    val json: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
        classDiscriminator = "kind"
    }

    /**
     * [json] for DECODING only. With `explicitNulls = true` kotlinx skips its per-object bookkeeping of absent nullable
     * members, which is a good part of the decode cost on sparse objects. Every nullable property of the protocol types
     * defaults to `null`, so a missing member decodes as with [json]. Types outside this module may lack that default: the
     * wire codec decodes again with [json] after a missing-field error. Never encode with it: it would write `"x":null`.
     */
    val decodeJson: Json = Json(json) {
        explicitNulls = true
    }

    /**
     * [json] with `prettyPrint`. For human-readable logs only, never the wire.
     */
    val prettyJson: Json = Json(json) {
        prettyPrint = true
    }

    val ProgressNotificationType: NotificationType<ProgressParams> =
        NotificationType("$/progress", ProgressParams.serializer())

    val CancelNotificationType: NotificationType<CancelParams> =
        NotificationType("$/cancelRequest", CancelParams.serializer())

    val RegisterCapabilityRequestType: RequestType<RegistrationParams, Unit, Unit> =
        RequestType("client/registerCapability", RegistrationParams.serializer(), Unit.serializer(), Unit.serializer())

    val UnregisterCapabilityRequestType: RequestType<UnregistrationParams, Unit, Unit> =
      RequestType("client/unregisterCapability", UnregistrationParams.serializer(), Unit.serializer(), Unit.serializer())
}

/**
 * Streams a [FileChange] without a `JsonObject` of the whole change.
 *
 * The variant is chosen like before: no `kind` means [TextDocumentEdit]; `create`, `rename`, `delete` mean the file
 * operations; any other `kind` (also `null`) fails. `kind` may come after other keys, so the small fields of the file
 * operations (`uri`, `oldUri`, `newUri`, `options`, `annotationId`) are kept as [JsonElement]s and decoded when the
 * variant is known. `textDocument` and `edits` are decoded in place unless a file-operation `kind` came first (then they
 * are skipped, as before).
 */
@Serializer(forClass = FileChange::class)
object FileChangeSerializer : KSerializer<FileChange> {
  private const val KIND = 0
  private const val TEXT_DOCUMENT = 1
  private const val EDITS = 2
  private const val URI_FIELD = 3
  private const val OLD_URI = 4
  private const val NEW_URI = 5
  private const val OPTIONS = 6
  private const val ANNOTATION_ID = 7

  private val editsSerializer = ListSerializer(TextEditSerializer)

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.protocol.FileChange") {
    annotations = ignoreUnknownKeys
    element("kind", String.serializer().nullable.descriptor, isOptional = true)
    element("textDocument", TextDocumentIdentifier.serializer().nullable.descriptor, isOptional = true)
    element("edits", editsSerializer.nullable.descriptor, isOptional = true)
    element("uri", DocumentUri.serializer().nullable.descriptor, isOptional = true)
    element("oldUri", DocumentUri.serializer().nullable.descriptor, isOptional = true)
    element("newUri", DocumentUri.serializer().nullable.descriptor, isOptional = true)
    element("options", JsonElement.serializer().nullable.descriptor, isOptional = true)
    element("annotationId", ChangeAnnotationIdentifier.serializer().nullable.descriptor, isOptional = true)
  }

  override fun serialize(encoder: Encoder, value: FileChange) {
    require(encoder is JsonEncoder) { "FileChange can only be serialized to JSON" }
    // Same members and order as the generated serializers of the file operations, then `kind`.
    when (value) {
      is TextDocumentEdit -> encoder.encodeSerializableValue(TextDocumentEditSerializer, value)
      is CreateFile -> encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, URI_FIELD, DocumentUri.serializer(), value.uri)
        encodeOptionalElement(OPTIONS, CreateFileOptions.serializer(), value.options)
        encodeOptionalElement(ANNOTATION_ID, ChangeAnnotationIdentifier.serializer(), value.annotationId)
        encodeStringElement(descriptor, KIND, "create")
      }
      is RenameFile -> encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, OLD_URI, DocumentUri.serializer(), value.oldUri)
        encodeSerializableElement(descriptor, NEW_URI, DocumentUri.serializer(), value.newUri)
        encodeOptionalElement(OPTIONS, RenameFileOptions.serializer(), value.options)
        encodeOptionalElement(ANNOTATION_ID, ChangeAnnotationIdentifier.serializer(), value.annotationId)
        encodeStringElement(descriptor, KIND, "rename")
      }
      is DeleteFile -> encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, URI_FIELD, DocumentUri.serializer(), value.uri)
        encodeOptionalElement(OPTIONS, DeleteFileOptions.serializer(), value.options)
        encodeOptionalElement(ANNOTATION_ID, ChangeAnnotationIdentifier.serializer(), value.annotationId)
        encodeStringElement(descriptor, KIND, "delete")
      }
    }
  }

  /** Mirrors a generated serializer: a `null` is written only if defaults are encoded, and then only with explicit nulls. */
  @OptIn(ExperimentalSerializationApi::class)
  private fun <T : Any> CompositeEncoder.encodeOptionalElement(index: Int, serializer: KSerializer<T>, value: T?) {
    if (value != null || shouldEncodeElementDefault(descriptor, index)) {
      encodeNullableSerializableElement(descriptor, index, serializer, value)
    }
  }

  override fun deserialize(decoder: Decoder): FileChange {
    require(decoder is JsonDecoder) { "FileChange can only be deserialized from JSON" }
    var kind: String? = null
    var kindSeen = false
    var textDocument: TextDocumentIdentifier? = null
    var edits: List<TextEdit>? = null
    var uri: JsonElement? = null
    var oldUri: JsonElement? = null
    var newUri: JsonElement? = null
    var options: JsonElement? = null
    var annotationId: JsonElement? = null
    decoder.decodeStructure(descriptor) {
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          // The old code read `kind` as `jsonPrimitive.content`, so a JSON `null` became the unknown kind "null".
          KIND -> {
            kind = decodeSerializableElement(descriptor, KIND, String.serializer().nullable) ?: "null"
            kindSeen = true
          }
          TEXT_DOCUMENT -> {
            if (kindSeen) decodeSerializableElement(descriptor, index, JsonElement.serializer())
            else textDocument = decodeSerializableElement(descriptor, index, TextDocumentIdentifier.serializer())
          }
          EDITS -> {
            if (kindSeen) decodeSerializableElement(descriptor, index, JsonElement.serializer())
            else edits = decodeSerializableElement(descriptor, index, editsSerializer)
          }
          URI_FIELD -> uri = decodeSerializableElement(descriptor, index, JsonElement.serializer())
          OLD_URI -> oldUri = decodeSerializableElement(descriptor, index, JsonElement.serializer())
          NEW_URI -> newUri = decodeSerializableElement(descriptor, index, JsonElement.serializer())
          OPTIONS -> options = decodeSerializableElement(descriptor, index, JsonElement.serializer())
          ANNOTATION_ID -> annotationId = decodeSerializableElement(descriptor, index, JsonElement.serializer())
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
    }

    val json = decoder.json
    fun <T> JsonElement?.required(serializer: KSerializer<T>, field: String, serialName: String): T =
      json.decodeFromJsonElement(serializer, this ?: missingField(field, serialName))
    fun <T : Any> JsonElement?.optional(serializer: KSerializer<T>): T? =
      this?.let { json.decodeFromJsonElement(serializer.nullable, it) }

    return when (kind) {
      "create" -> CreateFile(
        uri = uri.required(DocumentUri.serializer(), "uri", "CreateFile"),
        options = options.optional(CreateFileOptions.serializer()),
        annotationId = annotationId.optional(ChangeAnnotationIdentifier.serializer()),
      )
      "rename" -> RenameFile(
        oldUri = oldUri.required(DocumentUri.serializer(), "oldUri", "RenameFile"),
        newUri = newUri.required(DocumentUri.serializer(), "newUri", "RenameFile"),
        options = options.optional(RenameFileOptions.serializer()),
        annotationId = annotationId.optional(ChangeAnnotationIdentifier.serializer()),
      )
      "delete" -> DeleteFile(
        uri = uri.required(DocumentUri.serializer(), "uri", "DeleteFile"),
        options = options.optional(DeleteFileOptions.serializer()),
        annotationId = annotationId.optional(ChangeAnnotationIdentifier.serializer()),
      )
      null -> TextDocumentEdit(
        textDocument = textDocument ?: missingField("textDocument", "TextDocumentEdit"),
        edits = edits ?: missingField("edits", "TextDocumentEdit"),
      )
      else -> throw SerializationException("Unknown FileChange kind: $kind")
    }
  }
}

/**
 * Streams a [TextEdit] (plain, annotated or snippet) without a `JsonObject`.
 *
 * Decode keeps the old rules: a `snippet` object with a `value` wins and makes `newText` empty (key order does not
 * matter), a missing `newText` is empty, and a JSON `null` in `newText` or `snippet.value` becomes the string "null"
 * (the old code read them as `jsonPrimitive.content`). `snippet: null` and `annotationId: null` fail, as before.
 */
@Serializer(forClass = TextEdit::class)
object TextEditSerializer : KSerializer<TextEdit> {
  private const val RANGE = 0
  private const val NEW_TEXT = 1
  private const val SNIPPET = 2
  private const val ANNOTATION_ID = 3

  // Nullable and optional elements: the decoder hands us every present value as is (no input coercion, no implicit null).
  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.protocol.TextEdit") {
    annotations = ignoreUnknownKeys
    element("range", Range.serializer().descriptor)
    element("newText", String.serializer().nullable.descriptor, isOptional = true)
    element("snippet", SnippetValueSerializer.descriptor.nullable, isOptional = true)
    element("annotationId", ChangeAnnotationIdentifier.serializer().nullable.descriptor, isOptional = true)
  }

  override fun serialize(encoder: Encoder, value: TextEdit) {
    require(encoder is JsonEncoder) { "TextEdit can only be serialized to JSON" }
    encoder.encodeStructure(descriptor) {
      encodeSerializableElement(descriptor, RANGE, Range.serializer(), value.range)
      val snippet = value.snippet
      if (snippet != null) {
        encodeSerializableElement(descriptor, SNIPPET, SnippetValueSerializer, snippet)
      }
      else {
        encodeStringElement(descriptor, NEW_TEXT, value.newText)
      }
      val annotationId = value.annotationId
      if (annotationId != null) {
        encodeSerializableElement(descriptor, ANNOTATION_ID, ChangeAnnotationIdentifier.serializer(), annotationId)
      }
    }
  }

  override fun deserialize(decoder: Decoder): TextEdit {
    require(decoder is JsonDecoder) { "TextEdit can only be deserialized from JSON" }
    var range: Range? = null
    var newText: String? = null
    var snippet: String? = null
    var annotationId: ChangeAnnotationIdentifier? = null
    decoder.decodeStructure(descriptor) {
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          RANGE -> range = decodeSerializableElement(descriptor, RANGE, Range.serializer())
          NEW_TEXT -> newText = decodeSerializableElement(descriptor, NEW_TEXT, String.serializer().nullable) ?: "null"
          SNIPPET -> snippet = decodeSerializableElement(descriptor, SNIPPET, SnippetValueSerializer)
          ANNOTATION_ID -> annotationId = decodeSerializableElement(descriptor, ANNOTATION_ID, ChangeAnnotationIdentifier.serializer())
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
    }
    return TextEdit(
      range = range ?: missingField("range", "TextEdit"),
      newText = if (snippet != null) "" else newText ?: "",
      snippet = snippet,
      annotationId = annotationId,
    )
  }
}

/**
 * The `snippet` member of a snippet [TextEdit]: `{"kind":"snippet","value":...}`. Decode reads only `value` (`null` if it
 * is missing); `kind` is ignored whatever its shape.
 */
internal object SnippetValueSerializer : SerializationStrategy<String>, DeserializationStrategy<String?> {
  private const val KIND = 0
  private const val VALUE = 1

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.protocol.StringValue") {
    annotations = ignoreUnknownKeys
    element("kind", JsonElement.serializer().nullable.descriptor, isOptional = true)
    element("value", String.serializer().nullable.descriptor, isOptional = true)
  }

  override fun serialize(encoder: Encoder, value: String) {
    encoder.encodeStructure(descriptor) {
      encodeStringElement(descriptor, KIND, "snippet")
      encodeStringElement(descriptor, VALUE, value)
    }
  }

  override fun deserialize(decoder: Decoder): String? {
    var value: String? = null
    decoder.decodeStructure(descriptor) {
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          KIND -> decodeSerializableElement(descriptor, KIND, JsonElement.serializer())
          VALUE -> value = decodeSerializableElement(descriptor, VALUE, String.serializer().nullable) ?: "null"
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
    }
    return value
  }
}

/**
 * Streams a [TextDocumentEdit] without a `JsonObject`. Keys may come in any order; unknown keys are ignored.
 */
@Serializer(forClass = TextDocumentEdit::class)
object TextDocumentEditSerializer : KSerializer<TextDocumentEdit> {
  private const val TEXT_DOCUMENT = 0
  private const val EDITS = 1

  private val editsSerializer = ListSerializer(TextEditSerializer)

  /** An OptionalVersionedTextDocumentIdentifier: `version` (integer | null) is required, a `null` one is written. */
  private val versionedTextDocument = RequiredNullMembers(TextDocumentIdentifier.serializer(), "version")

  override val descriptor: SerialDescriptor = buildClassSerialDescriptor("com.jetbrains.lsp.protocol.TextDocumentEdit") {
    annotations = ignoreUnknownKeys
    element("textDocument", TextDocumentIdentifier.serializer().descriptor)
    element("edits", editsSerializer.descriptor)
  }

  override fun serialize(encoder: Encoder, value: TextDocumentEdit) {
    require(encoder is JsonEncoder) { "TextDocumentEdit can only be serialized to JSON" }
    encoder.encodeStructure(descriptor) {
      encodeSerializableElement(descriptor, TEXT_DOCUMENT, versionedTextDocument, value.textDocument)
      encodeSerializableElement(descriptor, EDITS, editsSerializer, value.edits)
    }
  }

  override fun deserialize(decoder: Decoder): TextDocumentEdit {
    require(decoder is JsonDecoder) { "TextDocumentEdit can only be deserialized from JSON" }
    var textDocument: TextDocumentIdentifier? = null
    var edits: List<TextEdit>? = null
    decoder.decodeStructure(descriptor) {
      while (true) {
        when (val index = decodeElementIndex(descriptor)) {
          TEXT_DOCUMENT -> textDocument = decodeSerializableElement(descriptor, TEXT_DOCUMENT, TextDocumentIdentifier.serializer())
          EDITS -> edits = decodeSerializableElement(descriptor, EDITS, editsSerializer)
          CompositeDecoder.DECODE_DONE -> break
          else -> throw SerializationException("Unexpected index $index")
        }
      }
    }
    return TextDocumentEdit(
      textDocument = textDocument ?: missingField("textDocument", "TextDocumentEdit"),
      edits = edits ?: missingField("edits", "TextDocumentEdit"),
    )
  }
}

/**
 * The [generated] serializer of a class with the members [names] written as JSON `null` when `null`: the spec marks them
 * required and nullable (`x: T | null`, no `?`), and [LSP.json] (`explicitNulls = false`) would drop them. E.g. the
 * `version` of an OptionalVersionedTextDocumentIdentifier, which VS Code needs to accept a workspace edit change.
 *
 * Streaming, no tree: [generated] writes into a [CompositeEncoder] that answers `true` to `shouldEncodeElementDefault`
 * for [names] (else a `null` default is skipped before any call) and writes a `null` of [names] as `JsonNull` (the
 * nullable element call of [LSP.json] drops it); every other call goes to the real encoder unchanged. Only the members of
 * this class: nested values are written by the real encoder. Decode is [generated]. Pinned by `RequiredNullMembersProbeTest`.
 */
internal class RequiredNullMembers<T>(private val generated: KSerializer<T>, vararg names: String) : KSerializer<T> {
  override val descriptor: SerialDescriptor = generated.descriptor

  private val required = BooleanArray(descriptor.elementsCount).also { required ->
    for (name in names) {
      val index = descriptor.getElementIndex(name)
      require(index != CompositeDecoder.UNKNOWN_NAME) { "${descriptor.serialName} has no member $name" }
      required[index] = true
    }
  }

  override fun serialize(encoder: Encoder, value: T) {
    if (encoder !is JsonEncoder) return generated.serialize(encoder, value)
    generated.serialize(RequiredNullsEncoder(encoder, required), value)
  }

  override fun deserialize(decoder: Decoder): T = generated.deserialize(decoder)
}

/** The [Encoder] and the [CompositeEncoder] of one [RequiredNullMembers] value: one object per value. */
@OptIn(ExperimentalSerializationApi::class)
private class RequiredNullsEncoder(private val encoder: Encoder, private val required: BooleanArray) : Encoder by encoder, CompositeEncoder {
  private lateinit var output: CompositeEncoder

  override val serializersModule: SerializersModule get() = encoder.serializersModule

  override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
    output = encoder.beginStructure(descriptor)
    return this
  }

  override fun endStructure(descriptor: SerialDescriptor): Unit = output.endStructure(descriptor)

  override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean =
    required[index] || output.shouldEncodeElementDefault(descriptor, index)

  override fun <V : Any> encodeNullableSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<V>, value: V?) {
    if (value == null && required[index]) output.encodeSerializableElement(descriptor, index, JsonNull.serializer(), JsonNull)
    else output.encodeNullableSerializableElement(descriptor, index, serializer, value)
  }

  override fun <V> encodeSerializableElement(descriptor: SerialDescriptor, index: Int, serializer: SerializationStrategy<V>, value: V): Unit =
    output.encodeSerializableElement(descriptor, index, serializer, value)

  override fun encodeBooleanElement(descriptor: SerialDescriptor, index: Int, value: Boolean): Unit = output.encodeBooleanElement(descriptor, index, value)
  override fun encodeByteElement(descriptor: SerialDescriptor, index: Int, value: Byte): Unit = output.encodeByteElement(descriptor, index, value)
  override fun encodeShortElement(descriptor: SerialDescriptor, index: Int, value: Short): Unit = output.encodeShortElement(descriptor, index, value)
  override fun encodeCharElement(descriptor: SerialDescriptor, index: Int, value: Char): Unit = output.encodeCharElement(descriptor, index, value)
  override fun encodeIntElement(descriptor: SerialDescriptor, index: Int, value: Int): Unit = output.encodeIntElement(descriptor, index, value)
  override fun encodeLongElement(descriptor: SerialDescriptor, index: Int, value: Long): Unit = output.encodeLongElement(descriptor, index, value)
  override fun encodeFloatElement(descriptor: SerialDescriptor, index: Int, value: Float): Unit = output.encodeFloatElement(descriptor, index, value)
  override fun encodeDoubleElement(descriptor: SerialDescriptor, index: Int, value: Double): Unit = output.encodeDoubleElement(descriptor, index, value)
  override fun encodeStringElement(descriptor: SerialDescriptor, index: Int, value: String): Unit = output.encodeStringElement(descriptor, index, value)
  override fun encodeInlineElement(descriptor: SerialDescriptor, index: Int): Encoder = output.encodeInlineElement(descriptor, index)
}

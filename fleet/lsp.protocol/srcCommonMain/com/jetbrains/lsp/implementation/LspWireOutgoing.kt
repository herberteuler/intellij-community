package com.jetbrains.lsp.implementation

import com.jetbrains.lsp.protocol.LSP
import fleet.util.decodeToStringUtf8
import kotlinx.io.Sink
import kotlinx.serialization.json.JsonElement
import org.jetbrains.annotations.ApiStatus

/**
 * An outgoing JSON-RPC message made by [LspWireCodec], held as the two parts of its frame: `[header][body]`. [header] is
 * the `Content-Length` line and the blank line, [body] is the UTF-8 of the message. The frame writer writes the parts as
 * they are ([writeTo]); nothing copies them into one array on the way to the wire. [frame] concatenates them on demand
 * (tests, traffic logs); [json] gives the message as a tree (the body is parsed on the first call, then cached; the
 * `JsonElement` channel API sends that).
 *
 * NOT A STABLE API, see [LspWireCodec].
 */
@ApiStatus.Internal
class LspWireOutgoing private constructor(
  private val header: ByteArray,
  private val body: ByteArray,
) {
  private val tree: Lazy<JsonElement> = lazy(LazyThreadSafetyMode.PUBLICATION) {
    LSP.json.decodeFromString(JsonElement.serializer(), body.decodeToStringUtf8())
  }

  /** The frame, header and body, as one array: the parts concatenated. */
  fun frame(): ByteArray {
    val frame = header.copyOf(header.size + body.size)
    body.copyInto(frame, header.size)
    return frame
  }

  /** The message as a JSON tree, parsed from the frame body. */
  fun json(): JsonElement = tree.value

  override fun toString(): String = json().toString()

  /** Writes the frame parts to [sink] in wire order, each as it is. */
  internal fun writeTo(sink: Sink) {
    sink.write(header)
    sink.write(body)
  }

  internal companion object {
    /** The message of [body]; its header counts that body in bytes. */
    fun ofBody(body: ByteArray): LspWireOutgoing = LspWireOutgoing(LspWireCodec.contentLengthHeader(body.size), body)
  }
}

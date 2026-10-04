package com.jetbrains.lsp.implementation

import com.jetbrains.lsp.protocol.StringOrInt
import kotlinx.serialization.DeserializationStrategy

/**
 * The payload serializers of a session, for the envelope pass of [LspWireBody.read]: with
 * them the payload decodes typed in the same kotlinx pass as the envelope. `null` means not known: the payload is
 * skipped and decoded on demand by a second pass. Each lookup is a map get; it runs on the read loop.
 */
internal interface LspPayloadResolver {
  /** The params serializer of [method]; [isRequest] when an `id` came before. */
  fun paramsSerializer(method: String, isRequest: Boolean): DeserializationStrategy<*>?

  /** The result serializer of the pending outgoing request [id]. */
  fun resultSerializer(id: StringOrInt): DeserializationStrategy<*>?
}

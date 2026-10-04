package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.EnvelopeShape
import com.jetbrains.lsp.implementation.OutgoingEnvelopeSerializer
import com.jetbrains.lsp.protocol.NotificationMessage
import com.jetbrains.lsp.protocol.RequestMessage
import com.jetbrains.lsp.protocol.ResponseMessage
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The envelope reader and writer list the JSON-RPC members by hand. A member added to a message class and not to them
 * would be dropped on the wire, with no compile error. The test module is a friend of `fleet.lsp.protocol`, so it reads
 * the internal descriptors directly.
 */
class EnvelopeDescriptorDriftTest {
  private val messageMembers = listOf(RequestMessage.serializer(), ResponseMessage.serializer(), NotificationMessage.serializer())
    .flatMap { it.descriptor.elementNames }.toSortedSet()

  @Test
  fun `envelope reader has exactly the members of the JSON-RPC messages`() {
    val d = EnvelopeShape.BOTH.descriptor
    assertEquals(messageMembers, d.elementNames.toSortedSet())
    assertIndices(d, mapOf(
      EnvelopeShape.JSONRPC to "jsonrpc",
      EnvelopeShape.ID to "id",
      EnvelopeShape.METHOD to "method",
      EnvelopeShape.ERROR to "error",
      EnvelopeShape.PARAMS to "params",
      EnvelopeShape.RESULT to "result",
    ))
  }

  @Test
  fun `envelope writer has exactly the members of the JSON-RPC messages`() {
    val d = OutgoingEnvelopeSerializer.descriptor
    assertEquals(messageMembers, d.elementNames.toSortedSet())
    assertIndices(d, mapOf(
      OutgoingEnvelopeSerializer.JSONRPC to "jsonrpc",
      OutgoingEnvelopeSerializer.ID to "id",
      OutgoingEnvelopeSerializer.METHOD to "method",
      OutgoingEnvelopeSerializer.PARAMS to "params",
      OutgoingEnvelopeSerializer.RESULT to "result",
      OutgoingEnvelopeSerializer.ERROR to "error",
    ))
  }

  private fun assertIndices(d: SerialDescriptor, expected: Map<Int, String>) {
    assertEquals(expected, expected.keys.associateWith { d.getElementName(it) }, d.serialName)
  }
}

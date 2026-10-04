package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.ByteReader
import com.jetbrains.lsp.implementation.ByteWriter
import com.jetbrains.lsp.implementation.LspConnection
import com.jetbrains.lsp.implementation.withBaseProtocolFraming
import com.jetbrains.lsp.implementation.withLspFraming
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.test.runTest
import kotlinx.io.Sink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End of input inside a frame is a clean end: a valid stream cut at any byte delivers exactly the frames that end at
 * or before the cut, then closes the incoming channel with no error.
 */
class FrameTruncationTest {

  private val bodies = listOf(
    """{"jsonrpc":"2.0","method":"a/é","params":{"text":"日本 😀 \"q\""}}""",
    """{"jsonrpc":"2.0","id":17,"method":"x/echo","params":"ü"}""",
    """{"jsonrpc":"2.0","id":"q\"1","result":{"items":[1,2,3],"label":"€"}}""",
  )

  private val frames = listOf(
    "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\nContent-Length: ${size(bodies[0])}\r\n\r\n${bodies[0]}",
    "Content-Length: ${size(bodies[1])}\r\n\r\n${bodies[1]}",
    "Content-Length: ${size(bodies[2])}\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n${bodies[2]}",
  ).map { it.encodeToByteArray() }

  private val stream = frames.reduce { a, b -> a + b }

  /** The offset right after each frame. */
  private val frameEnds = frames.runningFold(0) { end, frame -> end + frame.size }.drop(1)

  @Test
  fun `a stream cut at every byte delivers the whole frames before the cut, then ends cleanly`() {
    runTest {
      val failures = mutableListOf<String>()
      for (cut in 0..stream.size) {
        val prefix = stream.copyOfRange(0, cut)
        val expected = bodies.take(frameEnds.count { it <= cut }).map(::parse)
        for ((readName, chunks) in listOf(
          "whole" to arrayOf(prefix),
          "1-byte" to Array(prefix.size) { byteArrayOf(prefix[it]) },
        )) {
          check(failures, cut, "base $readName", expected) { readBase(TruncationConnection(ChunkedByteReader(*chunks))) }
          check(failures, cut, "wire $readName", expected) { readWire(TruncationConnection(ChunkedByteReader(*chunks))) }
        }
        check(failures, cut, "base ktor", expected) { readBase(TruncationConnection(closedChannelReader(prefix))) }
        check(failures, cut, "wire ktor", expected) { readWire(TruncationConnection(closedChannelReader(prefix))) }
      }
      val kinds = failures.groupBy { it.substringAfter(": ") }
        .entries.joinToString("\n") { (kind, runs) -> "${runs.size} runs: $kind; first ${runs.take(3).map { it.substringBefore(": ") }}" }
      val cuts = failures.map { it.split(" ")[1] }.distinct().size
      assertTrue(failures.isEmpty(), "${failures.size} failing runs at $cuts of ${stream.size + 1} cut positions:\n$kinds")
    }
  }

  @Test
  fun `a whole body that is not JSON still fails, it is not a truncation`() {
    runTest {
      val raw = "Content-Length: 3\r\n\r\n{x}".encodeToByteArray()
      assertFailsWith<IllegalStateException> { readBase(TruncationConnection(ChunkedByteReader(raw))) }
      assertFailsWith<IllegalStateException> { readWire(TruncationConnection(ChunkedByteReader(raw))) }
    }
  }

  private suspend fun check(
    failures: MutableList<String>,
    cut: Int,
    name: String,
    expected: List<JsonElement>,
    read: suspend () -> List<JsonElement>,
  ) {
    val actual = try {
      read()
    }
    catch (e: Throwable) {
      failures.add("cut $cut $name: ${region(cut)}, ${e::class.simpleName} ${e.message?.take(20)}")
      return
    }
    if (actual != expected) failures.add("cut $cut $name: ${region(cut)}, wrong frames")
  }

  private suspend fun readBase(connection: LspConnection): List<JsonElement> {
    val received = mutableListOf<JsonElement>()
    val exitSignal = CompletableDeferred<Unit>()
    withBaseProtocolFraming(connection, exitSignal) { incoming, _ -> incoming.consumeEach { received.add(it) } }
    assertTrue(exitSignal.isCompleted, "exit signal")
    return received
  }

  private suspend fun readWire(connection: LspConnection): List<JsonElement> {
    val received = mutableListOf<JsonElement>()
    val exitSignal = CompletableDeferred<Unit>()
    // `kind` runs the envelope pass, where a body that is not JSON fails (as the `withLsp` loop reads it)
    withLspFraming(connection, exitSignal) { incoming, _ -> incoming.consumeEach { it.read(); received.add(it.json()) } }
    assertTrue(exitSignal.isCompleted, "exit signal")
    return received
  }

  private suspend fun closedChannelReader(bytes: ByteArray): ByteReader {
    val channel = ByteChannel()
    channel.writeByteArray(bytes)
    channel.flushAndClose()
    return ByteChannelReader(channel)
  }

  /** Where [cut] falls: in the headers or the body of which frame. */
  private fun region(cut: Int): String {
    val index = frameEnds.indexOfFirst { cut < it }
    if (index < 0 || cut == frameEnds.getOrElse(index - 1) { 0 }) return "between frames"
    val bodyStart = frameEnds[index] - size(bodies[index])
    return if (cut < bodyStart) "frame $index headers" else "frame $index body"
  }

  private fun size(json: String): Int = json.encodeToByteArray().size

  private fun parse(json: String): JsonElement = Json.parseToJsonElement(json)
}

private class TruncationConnection(override val input: ByteReader) : LspConnection {
  override val output: ByteWriter = DiscardingWriter(ByteChannel())
  override fun isAlive(): Boolean = true
  override fun close() {}
}

@OptIn(InternalAPI::class)
private class DiscardingWriter(private val channel: ByteChannel) : ByteWriter {
  override val isClosedForWrite: Boolean get() = channel.isClosedForWrite
  override val closedCause: Throwable? get() = channel.closedCause
  override val writeBuffer: Sink get() = channel.writeBuffer

  override suspend fun flush(): Unit = channel.flush()
  override suspend fun flushAndClose(): Unit = channel.flushAndClose()
  override fun cancel(cause: Throwable?): Unit = channel.cancel(cause)
}

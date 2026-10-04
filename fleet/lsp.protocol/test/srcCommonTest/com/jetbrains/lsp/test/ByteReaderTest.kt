package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.ByteReader
import com.jetbrains.lsp.implementation.readByteArray
import com.jetbrains.lsp.implementation.readUTF8Line
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.writeByte
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val LF = 0x0A.toByte()
private const val CR = 0x0D.toByte()

class ByteReaderTest {

  @Test
  fun `readUTF8Line reads an LF-terminated line`() {
    runTest {
      val message = "Hello, world!"
      val channel = ByteChannel()
      val reader = ByteChannelReader(channel)

      val line = async { reader.readUTF8Line() }
      channel.writeLine(message, LF)
      channel.flushAndClose()

      assertEquals(message, line.await())
    }
  }

  @Test
  fun `readUTF8Line reads an LF-terminated line sent byte by byte`() {
    runTest {
      val message = "Hello, world!"
      val channel = ByteChannel()
      val reader = ByteChannelReader(channel)

      val line = async { reader.readUTF8Line() }
      message.encodeToByteArray().forEach { byte ->
        channel.writeByte(byte)
        channel.flush()
      }
      channel.writeByte(LF)
      channel.flushAndClose()

      assertEquals(message, line.await())
    }
  }

  @Test
  fun `readUTF8Line reads a CRLF-terminated line sent byte by byte`() {
    runTest {
      val message = "Hello, world!"
      val channel = ByteChannel()
      val reader = ByteChannelReader(channel)

      val line = async { reader.readUTF8Line() }
      message.encodeToByteArray().forEach { byte ->
        channel.writeByte(byte)
        channel.flush()
      }
      channel.writeBytes(CR, LF)
      channel.flushAndClose()

      assertEquals(message, line.await())
    }
  }

  @Test
  fun `readUTF8Line reads several LF-terminated lines`() {
    runTest {
      val messages = listOf(
        "Hello", "world", "how", "are", "you", "doing"
      )
      val channel = ByteChannel()
      val reader = ByteChannelReader(channel)

      val lines = flow {
        while (!reader.isClosedForRead) {
          emit(reader.readUTF8Line())
        }
      }

      for (message in messages) {
        channel.writeLine(message, LF)
        channel.flush()
      }
      channel.close()

      lines.withIndex().collect { (i, line) ->
        assertEquals(messages[i], line)
      }
    }
  }

  private suspend fun ByteWriteChannel.writeBytes(vararg bytes: Byte) {
    for (byte in bytes) {
      writeByte(byte)
    }
  }

  private suspend fun ByteWriteChannel.writeLine(s: String, vararg terminators: Byte = byteArrayOf(LF)) {
    writeStringUtf8(s)
    for (terminator in terminators) {
      writeByte(terminator)
    }
  }
}

@OptIn(InternalAPI::class)
internal class ByteChannelReader(val underlying: ByteChannel) : ByteReader {
  override val closedCause: Throwable?
    get() = underlying.closedCause
  override val isClosedForRead: Boolean
    get() = underlying.isClosedForRead
  override val readBuffer: Source
    get() = underlying.readBuffer

  override suspend fun awaitContent(min: Int): Boolean {
    return underlying.awaitContent()
  }

  override fun cancel(cause: Throwable?) {
    underlying.cancel(cause)
  }
}
class ChunkedByteReaderTest {

  @Test
  fun `readUTF8Line joins a line split across reads, also inside a multi-byte character`() {
    runTest {
      val line = "Content-Type: é 日本 😀"
      val bytes = (line + "\r\n").encodeToByteArray()
      // split every line at every position: inside the name, inside a UTF-8 sequence, between CR and LF
      for (at in 1 until bytes.size) {
        val reader = ChunkedByteReader(bytes.copyOfRange(0, at), bytes.copyOfRange(at, bytes.size), "next\n".encodeToByteArray())
        assertEquals(line, reader.readUTF8Line(), "split at $at")
        assertEquals("next", reader.readUTF8Line(), "split at $at")
      }
    }
  }

  @Test
  fun `readUTF8Line reads several lines from one read and leaves the rest buffered`() {
    runTest {
      val reader = ChunkedByteReader("a\r\nb\n\r\nbody".encodeToByteArray())
      assertEquals("a", reader.readUTF8Line())
      assertEquals("b", reader.readUTF8Line())
      assertEquals("", reader.readUTF8Line())
      assertEquals("body", reader.readByteArray(4).decodeToString())
    }
  }

  @Test
  fun `readUTF8Line returns the unterminated rest at the end of input, then null`() {
    runTest {
      val reader = ChunkedByteReader("ab".encodeToByteArray(), "cé".encodeToByteArray())
      assertEquals("abcé", reader.readUTF8Line())
      assertEquals(null, reader.readUTF8Line())
    }
  }

  @Test
  fun `readUTF8Line rejects a CR not followed by LF, also at a read boundary`() {
    runTest {
      assertFailsWith<IOException> { ChunkedByteReader("a\rb\n".encodeToByteArray()).readUTF8Line() }
      assertFailsWith<IOException> { ChunkedByteReader("a\r".encodeToByteArray(), "b\n".encodeToByteArray()).readUTF8Line() }
    }
  }

  @Test
  fun `readUTF8Line rejects a CR at the end of input with an IOException`() {
    runTest {
      assertFailsWith<IOException> { ChunkedByteReader("a\r".encodeToByteArray()).readUTF8Line() }
      assertFailsWith<IOException> { ChunkedByteReader("a".encodeToByteArray(), "\r".encodeToByteArray()).readUTF8Line() }
      assertFailsWith<IOException> { ChunkedByteReader("\r".encodeToByteArray()).readUTF8Line() }
    }
  }

  @Test
  fun `readByteArray reads a body split across reads`() {
    runTest {
      val body = "{\"é\":\"日本 😀\"}".encodeToByteArray()
      for (at in 1 until body.size) {
        val reader = ChunkedByteReader(body.copyOfRange(0, at), body.copyOfRange(at, body.size) + "tail".encodeToByteArray())
        assertContentEquals(body, reader.readByteArray(body.size), "split at $at")
        assertEquals("tail", reader.readUTF8Line())
      }
    }
  }

  @Test
  fun `readByteArray returns the bytes read so far when the input ends early`() {
    runTest {
      val reader = ChunkedByteReader("ab".encodeToByteArray(), "c".encodeToByteArray())
      assertContentEquals("abc".encodeToByteArray(), reader.readByteArray(10))
    }
  }

  @Test
  fun `readByteArray does not allocate a huge count before the bytes arrive`() {
    runTest {
      val reader = ChunkedByteReader("ab".encodeToByteArray(), "c".encodeToByteArray())
      assertContentEquals("abc".encodeToByteArray(), reader.readByteArray(Int.MAX_VALUE))
    }
  }

  @Test
  fun `readByteArray reads a body larger than the upfront allocation`() {
    runTest {
      // above the 16 MiB allocated upfront, so the array grows twice
      val body = ByteArray((40 shl 20) + 3) { it.toByte() }
      val at = (20 shl 20) + 1
      val reader = ChunkedByteReader(body.copyOfRange(0, at), body.copyOfRange(at, body.size) + "tail".encodeToByteArray())
      assertContentEquals(body, reader.readByteArray(body.size))
      assertEquals("tail", reader.readUTF8Line())
    }
  }
}

/**
 * A [ByteReader] with the semantics of the JVM `inputStreamByteReader`: each [awaitContent] adds the next chunk (one
 * blocking read), and the reader is closed once a read finds no more input.
 */
internal class ChunkedByteReader(vararg chunks: ByteArray) : ByteReader {
  private val pending = ArrayDeque(chunks.toList())
  private val buffer = Buffer()
  private var closed = false

  override val closedCause: Throwable? get() = null
  override val isClosedForRead: Boolean get() = closed
  override val readBuffer: Source get() = buffer

  override suspend fun awaitContent(min: Int): Boolean {
    if (closed) return false
    val chunk = pending.removeFirstOrNull() ?: run {
      closed = true
      return false
    }
    buffer.write(chunk)
    return buffer.size >= min
  }

  override fun cancel(cause: Throwable?) {
    closed = true
  }
}

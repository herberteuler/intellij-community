package com.jetbrains.lsp.implementation

import kotlinx.coroutines.channels.Channel
import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlin.concurrent.Volatile

/**
 * Two in-memory connections wired to each other: what one writes, the other reads. Run [withLsp] or [serveLsp] over
 * each end to get a session with no process or socket. Closing an end closes both directions, so the peer's input ends.
 */
fun inMemoryLspConnections(): Pair<LspConnection, LspConnection> {
  val firstToSecond = BytePipe()
  val secondToFirst = BytePipe()
  return PipeConnection(secondToFirst.reader, firstToSecond.writer) to PipeConnection(firstToSecond.reader, secondToFirst.writer)
}

private class PipeConnection(override val input: ByteReader, override val output: ByteWriter) : LspConnection {
  override fun close() {
    input.cancel(null)
    output.cancel(null)
  }

  override fun isAlive(): Boolean = !input.isClosedForRead || !output.isClosedForWrite
}

/** A one-way byte pipe between a coroutine writer and a coroutine reader. */
private class BytePipe {
  private val chunks = Channel<ByteArray>(Channel.UNLIMITED)

  val reader: ByteReader = object : ByteReader {
    private val buffer = Buffer()

    @Volatile
    private var closed = false

    override val closedCause: Throwable? get() = null
    override val isClosedForRead: Boolean get() = closed && buffer.exhausted()
    override val readBuffer: Source get() = buffer

    override suspend fun awaitContent(min: Int): Boolean {
      while (buffer.size < min) {
        val chunk = chunks.receiveCatching().getOrNull()
        if (chunk == null) {
          closed = true
          return false
        }
        buffer.write(chunk)
      }
      return true
    }

    override fun cancel(cause: Throwable?) {
      closed = true
      chunks.close()
    }
  }

  val writer: ByteWriter = object : ByteWriter {
    private val buffer = Buffer()

    override val isClosedForWrite: Boolean get() = chunks.isClosedForSend
    override val closedCause: Throwable? get() = null
    override val writeBuffer: Sink get() = buffer

    override suspend fun flush() {
      val bytes = buffer.readByteArray()
      if (bytes.isEmpty()) return
      if (chunks.trySend(bytes).isFailure) throw IOException("the pipe is closed")
    }

    override suspend fun flushAndClose() {
      try {
        flush()
      }
      finally {
        chunks.close()
      }
    }

    override fun cancel(cause: Throwable?) {
      chunks.close()
    }
  }
}

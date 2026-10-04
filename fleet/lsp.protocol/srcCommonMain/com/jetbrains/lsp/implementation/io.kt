package com.jetbrains.lsp.implementation

import kotlinx.io.Buffer
import kotlinx.io.IOException
import kotlinx.io.InternalIoApi
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.indexOf
import kotlinx.io.readString

/** Replicates the ByteReadChannel interface from ktor utils with the same semantics. */
interface ByteReader {
  val closedCause: Throwable?
  val isClosedForRead: Boolean
  val readBuffer: Source

  suspend fun awaitContent(min: Int = 1): Boolean
  fun cancel(cause: Throwable?)
}

/** Replicates the ByteWriteChannel interface from ktor utils with the same semantics. */
interface ByteWriter {
  val isClosedForWrite: Boolean
  val closedCause: Throwable?
  val writeBuffer: Sink

  suspend fun flush()
  suspend fun flushAndClose()
  fun cancel(cause: Throwable?)
}

fun ByteWriter.writeByteArray(array: ByteArray) {
  writeBuffer.write(array)
}

fun ByteReader.cancel() {
  cancel(IOException("Channel was cancelled"))
}

private const val CR = 0x0D.toByte()
private const val LF = 0x0A.toByte()

/**
 * Reads a line ended by LF or CRLF and returns it without the ending, or the unended rest at the end of input, or
 * `null` at the end of input. A CR not followed by LF, also a CR at the end of input, throws [IOException].
 *
 * The semantics are those of ktor's `ByteReadChannel#readUTF8LineTo`. See [readLineWithEnd] for how the bytes are read.
 */
suspend fun ByteReader.readUTF8Line(): String? {
  val line = readLineWithEnd() ?: return null
  if (line.end == LineEnd.CR_AT_END_OF_INPUT) throw IOException("Unexpected end of input after <CR>")
  return line.text
}

/** How a line read by [readLineWithEnd] ended. */
internal enum class LineEnd {
  /** LF or CRLF. */
  NEWLINE,

  /** The input ended inside the line: the text is the unended rest. */
  END_OF_INPUT,

  /** The input ended right after a CR: the text is what came before the CR. */
  CR_AT_END_OF_INPUT,

  /** The line is longer than the limit of [readLineWithEnd]: the text is empty, the gathered bytes are dropped, the rest of the line stays unread. */
  TOO_LONG,
}

internal class Line(val text: String, val end: LineEnd)

/**
 * Reads a line and says how it ended, or returns `null` at the end of input. A CR followed by a byte other than LF
 * throws [IOException]; the end of input only is reported, the caller decides what it means. A line of more than
 * [maxLength] bytes (the ending not counted) is [LineEnd.TOO_LONG], reported once its first `maxLength + 1` bytes are
 * read: no more of it is gathered or scanned.
 *
 * The buffered bytes are scanned for the ending in place; only a line split across reads is gathered in a temporary
 * [Buffer]. The read buffer is always consumed before [ByteReader.awaitContent] is called, as the byte-by-byte version
 * did, since some readers only await more input once their buffer is exhausted.
 */
@OptIn(InternalIoApi::class)
internal suspend fun ByteReader.readLineWithEnd(maxLength: Int = Int.MAX_VALUE): Line? {
  var split: Buffer? = null
  while (!isClosedForRead) {
    if (!readBuffer.exhausted()) {
      val buffer = readBuffer.buffer
      val size = buffer.size
      // the ending may sit at index `room` at most: past it the line has more than maxLength bytes
      val room = maxLength.toLong() - (split?.size ?: 0L)
      val scan = minOf(size, room + 1)
      val lf = buffer.indexOf(LF, 0, scan)
      val cr = buffer.indexOf(CR, 0, if (lf >= 0) lf else scan)
      val end = if (cr >= 0) cr else lf
      if (end >= 0) {
        val text = when (val head = split) {
          null -> buffer.readString(end)
          else -> {
            head.write(buffer, end)
            head.readString()
          }
        }
        buffer.skip(1)
        if (cr >= 0) {
          // Check if LF follows CR after awaiting.
          if (readBuffer.exhausted()) awaitContent()
          if (readBuffer.exhausted()) return Line(text, LineEnd.CR_AT_END_OF_INPUT)
          if (readBuffer.buffer[0] == LF) {
            readBuffer.buffer.skip(1)
          }
          else {
            throw IOException("Unexpected line ending <CR>")
          }
        }
        return Line(text, LineEnd.NEWLINE)
      }
      if (size > room) return Line("", LineEnd.TOO_LONG)
      (split ?: Buffer().also { split = it }).write(buffer, size)
    }

    awaitContent()
  }

  return split?.takeIf { it.size > 0 }?.let { Line(it.readString(), LineEnd.END_OF_INPUT) }
}

/** [ByteReader.readByteArray] allocates up to this many bytes before they arrive; a larger array grows as they do. */
internal const val READ_AHEAD_ALLOCATION: Int = 16 shl 20

/**
 * Reads [count] bytes straight into one array, or fewer when the input ends first: the caller decides what a short
 * array means. Up to [READ_AHEAD_ALLOCATION] bytes the array is allocated at once; above, it doubles as bytes arrive, so
 * a peer that only declares a huge count (a `Content-Length` header) cannot make it allocate that much.
 */
suspend fun ByteReader.readByteArray(count: Int): ByteArray {
  require(count >= 0) { "negative count: $count" }
  var result = ByteArray(minOf(count, READ_AHEAD_ALLOCATION))
  var read = 0
  while (read != count) {
    if (readBuffer.exhausted()) awaitContent()
    if (isClosedForRead) break

    if (read == result.size) result = result.copyOf(minOf(count.toLong(), result.size * 2L).toInt())
    val n = readBuffer.readAtMostTo(result, read, result.size)
    if (n > 0) read += n
  }
  return if (read == result.size) result else result.copyOf(read)
}

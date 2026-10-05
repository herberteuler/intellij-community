package com.jetbrains.lsp.test

import com.jetbrains.lsp.implementation.LspClient
import com.jetbrains.lsp.implementation.LspException
import com.jetbrains.lsp.implementation.LspHandlers
import com.jetbrains.lsp.implementation.LspResultDecodeException
import com.jetbrains.lsp.implementation.LspWireBody
import com.jetbrains.lsp.implementation.LspWireCodec
import com.jetbrains.lsp.implementation.LspWireOutgoing
import com.jetbrains.lsp.implementation.NotificationDispatch
import com.jetbrains.lsp.implementation.lspHandlers
import com.jetbrains.lsp.implementation.withLsp
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.RequestType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Off-loop work in `withLsp` (S6): a result after `id` decodes on the read loop (envelope pass), one before `id` in the
 * caller's coroutine; a result that does not decode throws
 * [LspResultDecodeException], and [NotificationDispatch.Sequential] runs notification handlers on one ordered worker.
 * The peer is the test itself, over wire channels.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OffLoopDispatchTest {
    private val intRequest = RequestType("test/int", Unit.serializer(), Int.serializer(), Int.serializer())
    private val note = NotificationType("test/note", Int.serializer())

    // region result decode

    @Test
    fun `a result after id decodes on the read loop`() = runTest {
        val marker = CallerMarker()
        val probe = RequestType("test/probe", Unit.serializer(), ProbeIntSerializer(marker), Int.serializer())
        withPeer(LspHandlers.EMPTY) { peer, client ->
            val caller = async(MarkingDispatcher(dispatcher(), marker)) { client.request(probe, Unit) }
            peer.answer(result = "7")
            assertEquals(7, caller.await())
            assertEquals(listOf(false), marker.decodedOnCaller, "decoded on the read loop, in the envelope pass")
        }
    }

    @Test
    fun `a result before id decodes in the coroutine that called request`() = runTest {
        val marker = CallerMarker()
        val probe = RequestType("test/probe", Unit.serializer(), ProbeIntSerializer(marker), Int.serializer())
        withPeer(LspHandlers.EMPTY) { peer, client ->
            val caller = async(MarkingDispatcher(dispatcher(), marker)) { client.request(probe, Unit) }
            peer.send("""{"jsonrpc":"2.0","result":7,"id":${peer.receiveRequestId()}}""")
            assertEquals(7, caller.await())
            assertEquals(listOf(true), marker.decodedOnCaller, "decoded on the caller's dispatcher")
        }
    }

    @Test
    fun `a result that does not decode throws in the caller and the session goes on`() = runTest {
        withPeer(LspHandlers.EMPTY) { peer, client ->
            val bad = async { runCatching { client.request(intRequest, Unit) } }
            peer.answer(result = "\"not an int\"")
            val failure = assertFailsWith<LspResultDecodeException> { bad.await().getOrThrow() }
            assertEquals(intRequest.method, failure.method)
            assertEquals(ErrorCodes.RequestFailed, failure.errorCode)
            assertTrue(failure.cause != null, "carries the decode failure")
            assertTrue(failure.message!!.contains(intRequest.method), failure.message)

            val good = async { client.request(intRequest, Unit) }
            peer.answer(result = "5")
            assertEquals(5, good.await())
        }
    }

    @Test
    fun `an error with data that does not decode keeps the server error and suppresses the data failure`() = runTest {
        withPeer(LspHandlers.EMPTY) { peer, client ->
            val bad = async { runCatching { client.request(intRequest, Unit) } }
            peer.answer(error = """{"code":-32001,"message":"boom","data":"not an int"}""")
            val failure = assertFailsWith<LspException> { bad.await().getOrThrow() }
            assertFalse(failure is LspResultDecodeException)
            assertEquals(-32001, failure.errorCode)
            assertEquals("boom", failure.message)
            assertNull(failure.payload)
            assertEquals(1, failure.suppressedExceptions.size, "the data failure travels as suppressed")

            val good = async { runCatching { client.request(intRequest, Unit) } }
            peer.answer(error = """{"code":-32002,"message":"bang","data":3}""")
            val decoded = assertFailsWith<LspException> { good.await().getOrThrow() }
            assertEquals(-32002, decoded.errorCode)
            assertEquals(3, decoded.payload)
            assertTrue(decoded.suppressedExceptions.isEmpty())
        }
    }

    // endregion

    // region notification dispatch

    @Test
    fun `Sequential - a slow notification handler does not delay a later response`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val handlers = lspHandlers { notification(note) { gate.await() } }
        withPeer(handlers, NotificationDispatch.Sequential) { peer, client ->
            val response = async { client.request(intRequest, Unit) }
            val id = peer.receiveRequestId()
            peer.notify(1)
            peer.answer(id, result = "42")
            testScheduler.advanceUntilIdle()
            assertTrue(response.isCompleted, "the response came while the handler was busy")
            assertEquals(42, response.await())
            gate.complete(Unit)
        }
    }

    @Test
    fun `Inline - a slow notification handler delays a later response`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val handlers = lspHandlers { notification(note) { gate.await() } }
        withPeer(handlers, NotificationDispatch.Inline) { peer, client ->
            val response = async { client.request(intRequest, Unit) }
            val id = peer.receiveRequestId()
            peer.notify(1)
            peer.answer(id, result = "42")
            testScheduler.advanceUntilIdle()
            assertFalse(response.isCompleted, "the loop waits for the handler")
            gate.complete(Unit)
            assertEquals(42, response.await())
        }
    }

    @Test
    fun `Sequential keeps the wire order across many notifications and responses`() = runTest {
        val count = 500
        val handled = mutableListOf<Int>()
        val done = CompletableDeferred<Unit>()
        val handlers = lspHandlers {
            notification(note) { n ->
                repeat(n % 4) { yield() }
                handled += n
                if (handled.size == count) done.complete(Unit)
            }
        }
        withPeer(handlers, NotificationDispatch.Sequential) { peer, client ->
            for (n in 0 until count) {
                peer.notify(n)
                if (n % 50 == 0) {
                    val response = async { client.request(intRequest, Unit) }
                    peer.answer(result = "$n")
                    assertEquals(n, response.await())
                }
            }
            done.await()
            assertEquals((0 until count).toList(), handled)
        }
    }

    @Test
    fun `Sequential - a failing notification handler does not stop the next ones`() = runTest {
        val handled = mutableListOf<Int>()
        val handlers = lspHandlers {
            notification(note) { n ->
                if (n == 1) error("handler failure on purpose")
                handled += n
            }
        }
        withPeer(handlers, NotificationDispatch.Sequential) { peer, client ->
            peer.notify(1)
            peer.notify(2)
            val response = async { client.request(intRequest, Unit) }
            peer.answer(result = "0")
            response.await()
            testScheduler.advanceUntilIdle()
            assertEquals(listOf(2), handled)
        }
    }

    @Test
    fun `Sequential - cancelRequest cancels at once while the notification worker is busy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val handlerCancelled = CompletableDeferred<Unit>()
        val hang = RequestType("test/hang", Unit.serializer(), Unit.serializer(), Unit.serializer())
        val handlers = lspHandlers {
            notification(note) { gate.await() }
            request(hang) {
                try {
                    awaitCancellation()
                } catch (e: CancellationException) {
                    handlerCancelled.complete(Unit)
                    throw e
                }
            }
        }
        withPeer(handlers, NotificationDispatch.Sequential) { peer, _ ->
            // `{}`, not `null`: on wasm a null params value fails the handler's cast to Unit
            peer.send("""{"jsonrpc":"2.0","id":77,"method":"test/hang","params":{}}""")
            peer.notify(1)
            peer.send("""{"jsonrpc":"2.0","method":"${'$'}/cancelRequest","params":{"id":77}}""")
            val answer = peer.fromClient.receive().json().jsonObject
            assertEquals(77, answer["id"]!!.jsonPrimitive.int)
            assertEquals(ErrorCodes.RequestCancelled, answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            assertTrue(handlerCancelled.isCompleted)
            assertFalse(gate.isCompleted, "the worker is still busy")
            gate.complete(Unit)
        }
    }

    @Test
    fun `Sequential - a full queue stops the reader until the handler frees room, losing and reordering nothing`() = runTest {
        // the queue capacity in withLsp.kt (NOTIFICATION_QUEUE_CAPACITY)
        val capacity = 1024
        val extra = 10
        val total = capacity + 2 + extra
        val gate = CompletableDeferred<Unit>()
        val handled = mutableListOf<Int>()
        val done = CompletableDeferred<Unit>()
        val handlers = lspHandlers {
            notification(note) { n ->
                if (n == 1) gate.await()
                handled += n
                if (handled.size == total) done.complete(Unit)
            }
        }
        // rendezvous: a send completes only when the read loop takes the message, so `read` counts the loop's reads
        val toClient = Channel<LspWireBody>()
        val fromClient = Channel<LspWireOutgoing>(Channel.UNLIMITED)
        var read = 0
        val client = launch { withLsp(toClient, fromClient, handlers, NotificationDispatch.Sequential) { awaitCancellation() } }
        val feeder = launch {
            for (n in 1..total) {
                toClient.send(notificationJson(n))
                read++
            }
        }
        testScheduler.advanceUntilIdle()
        // 1 in the blocked handler, `capacity` queued, 1 taken by the loop that waits for room
        assertEquals(capacity + 2, read, "the reader stops once the queue is full")
        assertEquals(emptyList(), handled)
        gate.complete(Unit)
        done.await()
        feeder.join()
        assertEquals(total, read)
        assertEquals((1..total).toList(), handled)
        client.cancelAndJoin()
    }

    @Test
    fun `Sequential - when incoming ends, queued notifications are handled and the session ends after them`() =
        queuedAtIncomingEnd(NotificationDispatch.Sequential)

    @Test
    fun `Inline - when incoming ends, every notification was handled before the session ends`() =
        queuedAtIncomingEnd(NotificationDispatch.Inline)

    private fun queuedAtIncomingEnd(dispatch: NotificationDispatch) = runTest {
        val gate = CompletableDeferred<Unit>()
        val handled = mutableListOf<Int>()
        val sessionJob = CompletableDeferred<Job>()
        val handlers = lspHandlers {
            notification(note) { n ->
                sessionJob.complete(coroutineContext.job)
                if (n == 1) gate.await()
                handled += n
            }
        }
        val toClient = Channel<LspWireBody>(Channel.UNLIMITED)
        val fromClient = Channel<LspWireOutgoing>(Channel.UNLIMITED)
        val client = launch { withLsp(toClient, fromClient, handlers, dispatch) { awaitCancellation() } }
        for (n in 1..5) toClient.send(notificationJson(n))
        toClient.close()
        testScheduler.advanceUntilIdle()
        val session = sessionJob.await()
        assertEquals(emptyList(), handled)
        assertFalse(session.isCompleted, "the session waits for the handler")
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals((1..5).toList(), handled)
        assertTrue(session.isCompleted, "the session ended after the last handler")
        client.cancelAndJoin()
    }

    @Test
    fun `Sequential - when the session is cancelled, the busy worker is cancelled and the queue dropped`() =
        queuedAtCancel(NotificationDispatch.Sequential)

    @Test
    fun `Inline - when the session is cancelled, the busy handler is cancelled and later messages are not read`() =
        queuedAtCancel(NotificationDispatch.Inline)

    private fun queuedAtCancel(dispatch: NotificationDispatch) = runTest {
        val handled = mutableListOf<Int>()
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val handlers = lspHandlers {
            notification(note) { n ->
                if (n == 1) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        cancelled.complete(Unit)
                        throw e
                    }
                }
                handled += n
            }
        }
        val toClient = Channel<LspWireBody>(Channel.UNLIMITED)
        val fromClient = Channel<LspWireOutgoing>(Channel.UNLIMITED)
        val bodyDone = CompletableDeferred<Unit>()
        val client = launch { withLsp(toClient, fromClient, handlers, dispatch) { bodyDone.await() } }
        for (n in 1..5) toClient.send(notificationJson(n))
        started.await()
        testScheduler.advanceUntilIdle()
        bodyDone.complete(Unit)
        client.join()
        assertTrue(cancelled.isCompleted, "the busy handler was cancelled")
        assertEquals(emptyList(), handled)
    }

    // endregion

    // region harness

    private class Peer(val toClient: Channel<LspWireBody>, val fromClient: Channel<LspWireOutgoing>) {
        suspend fun send(json: String) = toClient.send(LspWireCodec.decodeFrameBody(json.encodeToByteArray()))

        suspend fun notify(n: Int) = toClient.send(notificationJson(n))

        suspend fun receiveRequestId(): Int = fromClient.receive().json().jsonObject["id"]!!.jsonPrimitive.int

        /** Answers [id], or the next request the client sends. */
        suspend fun answer(id: Int? = null, result: String? = null, error: String? = null) {
            val requestId = id ?: receiveRequestId()
            val member = if (error != null) "\"error\":$error" else "\"result\":$result"
            send("""{"jsonrpc":"2.0","id":$requestId,$member}""")
        }
    }

    private suspend fun TestScope.withPeer(
        handlers: LspHandlers,
        dispatch: NotificationDispatch = NotificationDispatch.Inline,
        test: suspend CoroutineScope.(Peer, LspClient) -> Unit,
    ) {
        val peer = Peer(Channel(Channel.UNLIMITED), Channel(Channel.UNLIMITED))
        val lspClient = CompletableDeferred<LspClient>()
        val session = launch { withLsp(peer.toClient, peer.fromClient, handlers, dispatch) { lspClient.complete(it); awaitCancellation() } }
        try {
            test(peer, lspClient.await())
        } finally {
            session.cancelAndJoin()
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun TestScope.dispatcher(): CoroutineDispatcher = coroutineContext[CoroutineDispatcher]!!

    /** Whether a [MarkingDispatcher] task runs now, and where each probe decode ran. */
    private class CallerMarker {
        var active = false
        val decodedOnCaller = mutableListOf<Boolean>()
    }

    /** Runs its tasks on [delegate], with [marker] active while one runs: the caller's coroutine. */
    private class MarkingDispatcher(private val delegate: CoroutineDispatcher, private val marker: CallerMarker) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context, Runnable {
                marker.active = true
                try {
                    block.run()
                } finally {
                    marker.active = false
                }
            })
        }
    }

    /** An Int that records whether it was decoded on the caller's dispatcher. */
    private class ProbeIntSerializer(private val marker: CallerMarker) : KSerializer<Int> {
        override val descriptor = PrimitiveSerialDescriptor("ProbeInt", PrimitiveKind.INT)

        override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

        override fun deserialize(decoder: Decoder): Int {
            marker.decodedOnCaller += marker.active
            return decoder.decodeInt()
        }
    }

    private companion object {
        fun notificationJson(n: Int): LspWireBody = LspWireCodec.decodeFrameBody("""{"jsonrpc":"2.0","method":"test/note","params":$n}""".encodeToByteArray())
    }

    // endregion
}

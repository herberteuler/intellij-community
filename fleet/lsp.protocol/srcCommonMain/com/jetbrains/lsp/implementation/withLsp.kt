package com.jetbrains.lsp.implementation

import com.jetbrains.lsp.protocol.CancelParams
import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.ExitNotificationType
import com.jetbrains.lsp.protocol.LSP
import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.RequestType
import com.jetbrains.lsp.protocol.ResponseError
import com.jetbrains.lsp.protocol.Shutdown
import com.jetbrains.lsp.protocol.StringOrInt
import fleet.multiplatform.shims.MultiplatformConcurrentHashMap
import fleet.util.async.Resource
import fleet.util.async.resourceOf
import fleet.util.logging.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.incrementAndFetch

private val LOG by lazy { logger<LspClient>() }

/**
 * How many notifications [NotificationDispatch.Sequential] queues for its worker. When the queue is full, the read loop
 * waits for room, so a slow handler slows the reader down, as it does with [NotificationDispatch.Inline], instead of
 * the queue growing without bound. While it waits, the loop reads nothing, so responses and `$/cancelRequest` wait
 * too: a handler that awaits a response of its own request hangs once the queue is full (the loop no longer reads that
 * response), as an inline handler that does so always hangs.
 * A cancelled session also cancels a loop waiting for room. Big enough that a flood (e.g. `$/progress` or `publishDiagnostics` bursts) seldom fills it; small enough that
 * the queued messages stay a few MB.
 */
private const val NOTIFICATION_QUEUE_CAPACITY = 1024

/** Params of [method] the peer sent that do not decode: answered with [ErrorCodes.InvalidParams], logged as a warning. */
private class InvalidParamsException(method: String, cause: Exception) :
    Exception("invalid params of $method: ${errorText(cause.message ?: cause::class.simpleName ?: "unknown error")}", cause)

/** [LspWireIncoming.decodeParams], with a decode failure as [InvalidParamsException]; a second pass counts in [stats]. */
private fun <T> LspWireIncoming.decodeParamsOf(method: String, serializer: DeserializationStrategy<T>, stats: LspWireStats): T? =
    try {
        if (!decodedInEnvelopePass(serializer)) stats.countSecondPass()
        decodeParams(serializer)
    }
    // a SerializationException is one, and serializers check their input with `require`
    catch (x: IllegalArgumentException) {
        throw InvalidParamsException(method, x)
    }

/**
 * A request awaiting its answer; the loop completes [deferred] with the response message. The loop's envelope pass
 * decodes the result with [resultSerializer] when the response has `id` before `result` ([LspPayloadResolver]).
 */
private class OutgoingRequest(
    val deferred: CompletableDeferred<LspWireIncoming>,
    val resultSerializer: DeserializationStrategy<*>,
)

/**
 * The result of [response] to a [requestType] request, decoded by the caller.
 *
 * @throws LspException for an error response. Its `data` is decoded into [LspException.payload]; when it does not
 *   decode, the payload is null, the decode failure is a suppressed exception of the [LspException], and the
 *   server's code and message are kept.
 * @throws LspResultDecodeException when the result does not decode.
 */
private fun <Result> decodeResponse(requestType: RequestType<*, Result, *>, response: LspWireIncoming, stats: LspWireStats): Result {
    when (val error = response.error) {
        null -> {
            if (!response.decodedInEnvelopePass(requestType.resultSerializer)) stats.countSecondPass()
            return try {
                @Suppress("UNCHECKED_CAST")
                (response.decodeResult(requestType.resultSerializer) as Result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = LspResultDecodeException(requestType.method, e)
                LOG.warn(e) { failure.message }
                throw failure
            }
        }

        else -> {
            var dataFailure: Exception? = null
            val payload = try {
                response.decodeErrorData(requestType.errorSerializer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                dataFailure = e
                null
            }
            throw LspResponseErrorException(
                message = error.message,
                errorCode = error.code,
                cause = null,
                payload = payload,
            ).also { exception -> dataFailure?.let { exception.addSuppressed(it) } }
        }
    }
}

/**
 * Encodes and sends the messages of [withLspImpl]. Every message is encoded as frame parts ([LspWireCodec]): the payload
 * goes straight to its text, then to its bytes, with no JSON tree.
 */
internal abstract class LspOutgoing {
    abstract suspend fun send(message: LspWireOutgoing)

    abstract fun trySend(message: LspWireOutgoing): ChannelResult<Unit>

    fun <T> encodeRequest(id: StringOrInt, method: String, serializer: SerializationStrategy<T>, params: T): LspWireOutgoing =
        LspWireCodec.encodeRequestFrame(id, method, serializer, params)

    fun <T> encodeNotification(method: String, serializer: SerializationStrategy<T>, params: T): LspWireOutgoing =
        LspWireCodec.encodeNotificationFrame(method, serializer, params)

    /** @throws Exception when [result] does not encode. */
    fun <T> encodeResult(id: StringOrInt, serializer: SerializationStrategy<T>, result: T): LspWireOutgoing =
        LspWireCodec.encodeResultFrame(id, serializer, result)

    fun encodeError(id: StringOrInt, error: ResponseError): LspWireOutgoing =
        LspWireCodec.encodeErrorFrame(id, error)
}

/** The channel gets the [LspWireOutgoing] frames as they are. */
private class WireOutgoing(private val outgoing: SendChannel<LspWireOutgoing>) : LspOutgoing() {
    override suspend fun send(message: LspWireOutgoing) = outgoing.send(message)

    override fun trySend(message: LspWireOutgoing): ChannelResult<Unit> = outgoing.trySend(message)
}

/**
 * The JSON-RPC session over [incoming] and [outgoing]. The loop reads each body ([LspWireBody.read]) in one kotlinx
 * pass that also decodes the payload typed when [body]'s handlers or a pending request tell its serializer
 * ([LspPayloadResolver]); so payloads decode on the loop, and `$/cancelRequest` waits behind a big one.
 */
internal suspend fun withLspImpl(
    incoming: ReceiveChannel<LspWireBody>,
    outgoing: SendChannel<LspWireOutgoing>,
    body: (LspClient) -> Resource<LspHandlers>,
    notificationDispatch: NotificationDispatch = NotificationDispatch.Inline,
) {
    val lspOutgoing: LspOutgoing = WireOutgoing(outgoing)
    val outgoingRequests = MultiplatformConcurrentHashMap<StringOrInt, OutgoingRequest>()
    val idGen = AtomicInt(0)
    val stats = currentCoroutineContext()[LspWireStats] ?: LspWireStats()
    val lspClient = object : LspClient {
        override suspend fun <Params, Result, Error> request(
            requestType: RequestType<Params, Result, Error>,
            params: Params,
        ): Result {
            val id = StringOrInt(JsonPrimitive(idGen.incrementAndFetch()))
            val deferred = CompletableDeferred<LspWireIncoming>()
            outgoingRequests[id] = OutgoingRequest(deferred, requestType.resultSerializer)
            lspOutgoing.send(lspOutgoing.encodeRequest(id, requestType.method, requestType.paramsSerializer, params))
            val response = try {
                deferred.await()
            } catch (c: CancellationException) {
                runCatching {
                    notifyAsync(LSP.CancelNotificationType, CancelParams(id))
                }.onFailure {
                    currentCoroutineContext().job.ensureActive()
                    LOG.info("Request cancellation for ${requestType.method} is not delivered ($it)")
                }
                outgoingRequests.remove(id)
                throw c
            } catch (e: Exception) {
                // the loop could not read the envelope of the response (e.g. a malformed `error`): this request fails, the session goes on
                val failure = LspResultDecodeException(requestType.method, e)
                LOG.warn(e) { failure.message }
                throw failure
            }
            // the loop decoded the result typed in the envelope pass (or skipped it); a failure surfaces here
            return decodeResponse(requestType, response, stats)
        }

        override fun <Params> notifyAsync(
            notificationType: NotificationType<Params>,
            params: Params,
        ) {
            lspOutgoing.trySend(lspOutgoing.encodeNotification(notificationType.method, notificationType.paramsSerializer, params)).getOrThrow()
        }

        override suspend fun <Params> notify(notificationType: NotificationType<Params>, params: Params) {
            lspOutgoing.send(lspOutgoing.encodeNotification(notificationType.method, notificationType.paramsSerializer, params))
        }
    }

    try {
        withLspSession(incoming, lspOutgoing, lspClient, body(lspClient), notificationDispatch, outgoingRequests, stats)
    } finally {
        LOG.debug { "Session wire stats: $stats" }
    }
}

/** The read loop of [withLspImpl] and its handlers; [stats] counts its envelope passes. */
private suspend fun withLspSession(
    incoming: ReceiveChannel<LspWireBody>,
    lspOutgoing: LspOutgoing,
    lspClient: LspClient,
    sessionHandlers: Resource<LspHandlers>,
    notificationDispatch: NotificationDispatch,
    outgoingRequests: MultiplatformConcurrentHashMap<StringOrInt, OutgoingRequest>,
    stats: LspWireStats,
) {
    sessionHandlers.use { handlers ->
        val payloadResolver = object : CountingPayloadResolver {
            override val stats: LspWireStats = stats

            override fun paramsSerializer(method: String, isRequest: Boolean): DeserializationStrategy<*>? = when {
                method == LSP.CancelNotificationType.method -> LSP.CancelNotificationType.paramsSerializer
                isRequest -> handlers.requestHandler(method)?.requestType?.paramsSerializer
                else -> handlers.notificationHandler(method)?.notificationType?.paramsSerializer
            }

            override fun resultSerializer(id: StringOrInt): DeserializationStrategy<*>? = outgoingRequests[id]?.resultSerializer
        }
        withSupervisor { supervisor ->
            val lspHandlerContext = LspHandlerContext(lspClient, supervisor)
            val incomingRequestsJobs = MultiplatformConcurrentHashMap<StringOrInt, Job>()
            withContext(CoroutineName("incoming requests accepter")) {
                // The scope for handler-launched work; `consume` below shadows `this` with the channel.
                val messageScope = this

                suspend fun handleNotification(notification: LspWireIncoming, notificationMethod: String) {
                    runCatching {
                        when (val handler = handlers.notificationHandler(notificationMethod)) {
                            null ->
                                LOG.info("Notification handler for $notificationMethod is not found")

                            else -> {
                                val deserializedParams =
                                    notification.decodeParamsOf(notificationMethod, handler.notificationType.paramsSerializer, stats)
                                @Suppress("UNCHECKED_CAST")
                                (handler as LspNotificationHandler<Any?>).handler(
                                    lspHandlerContext,
                                    messageScope,
                                    deserializedParams
                                )
                            }
                        }
                    }.onFailure { error ->
                        currentCoroutineContext().job.ensureActive()
                        if (error is InvalidParamsException) LOG.warn { error.message }
                        else LOG.error(error)
                    }
                }

                // Sequential: one worker handles the notifications in wire order; the loop only queues them, and
                // waits for room when the queue is full (NOTIFICATION_QUEUE_CAPACITY).
                val notificationQueue = when (notificationDispatch) {
                    NotificationDispatch.Inline -> null
                    NotificationDispatch.Sequential -> Channel<LspWireIncoming>(NOTIFICATION_QUEUE_CAPACITY).also { queue ->
                        messageScope.launch(CoroutineName("notification handlers")) {
                            for (notification in queue) {
                                handleNotification(notification, notification.method!!)
                            }
                        }
                    }
                }
                /**
                 * JSON-RPC tolerance for a message whose envelope does not decode (not JSON-RPC 2.0, a member of the
                 * wrong type, a malformed `error`): a response fails only its request, a request is answered with
                 * [ErrorCodes.InvalidRequest], anything with no readable id is dropped. Each is a warning; the session goes on.
                 */
                fun rejectEnvelope(body: LspWireBody, failure: Exception) {
                    val hint = EnvelopeHint.hintOf(body)
                    val id = hint.id
                    val reason = errorText(failure.message ?: failure::class.simpleName ?: "unknown error")
                    when {
                        id == null ->
                            LOG.warn { "Dropping a message with no readable id whose envelope does not decode: $reason" }

                        hint.hasMethod -> {
                            LOG.warn { "Answering request $id with InvalidRequest, its envelope does not decode: $reason" }
                            val error = lspOutgoing.encodeError(id, ResponseError(code = ErrorCodes.InvalidRequest, message = "invalid request: $reason"))
                            // off the loop, as request handlers answer
                            supervisor.launch(CoroutineName("InvalidRequest answer")) {
                                runCatching {
                                    lspOutgoing.send(error)
                                }.onFailure {
                                    currentCoroutineContext().job.ensureActive()
                                    LOG.info("InvalidRequest answer for $id is not delivered ($it)")
                                }
                            }
                        }

                        else -> when (val client = outgoingRequests.remove(id)) {
                            null -> LOG.warn { "Dropping a response to no pending request ($id) whose envelope does not decode: $reason" }
                            // `request` makes it an LspResultDecodeException in its caller
                            else -> client.deferred.completeExceptionally(failure)
                        }
                    }
                }

                // Drive the loop with a plain `for`, and leave it (on `exit`) so `consume` cancels
                // `incoming` on the way out, instead of cancelling the channel from inside its own handler.
                incoming.consume {
                    for (body in incoming) {
                        // the envelope pass: it decodes the payload too when the serializer is known
                        val message = try {
                            body.read(payloadResolver)
                        } catch (x: BodyNotJsonException) {
                            // not JSON: the frames are out of step, the session ends (a framing failure)
                            throw x
                        } catch (x: Exception) {
                            rejectEnvelope(body, x)
                            continue
                        }
                        when (message.kind) {
                            LspWireIncoming.Kind.Request -> {
                                val request = message
                                val requestId = request.id!!
                                val requestMethod = request.method!!
                                supervisor.launch(
                                    context = CoroutineName("handler for $requestMethod"),
                                    start = CoroutineStart.ATOMIC
                                ) {
                                    val maybeHandler = handlers.requestHandler(requestMethod)
                                    runCatching {
                                        val handler = requireNotNull(maybeHandler) {
                                            "no handler for request: $requestMethod"
                                        }
                                        val deserializedParams = request.decodeParamsOf(requestMethod, handler.requestType.paramsSerializer, stats)

                                        @Suppress("UNCHECKED_CAST")
                                        handler as LspRequestHandler<Any?, Any?, Any?>

                                        val result = handler.handler(
                                            lspHandlerContext,
                                            this,
                                            deserializedParams
                                        )

                                        lspOutgoing.encodeResult(requestId, handler.requestType.resultSerializer, result)
                                    }.fold(
                                        onSuccess = { encodedResponse -> encodedResponse },
                                        onFailure = { x ->
                                            val responseError = when (x) {
                                                is CancellationException -> {
                                                    ResponseError(
                                                        code = ErrorCodes.RequestCancelled,
                                                        message = "cancelled",
                                                    )
                                                }

                                                is LspException -> {
                                                    ResponseError(
                                                        code = x.errorCode,
                                                        message = x.message ?: x::class.simpleName ?: "unknown error",
                                                        data = runCatching {
                                                            @Suppress("UNCHECKED_CAST")
                                                            val errorSerializer = requireNotNull(maybeHandler) {
                                                                "we could not have caught LspException if we didn't find the handler"
                                                            }.requestType.errorSerializer as KSerializer<Any?>

                                                            LspWireCodec.encodeValue(errorSerializer, x.payload)
                                                        }.getOrNull()
                                                    )
                                                }

                                                is InvalidParamsException -> {
                                                    LOG.warn { x.message }

                                                    ResponseError(
                                                        code = ErrorCodes.InvalidParams,
                                                        message = x.message!!,
                                                    )
                                                }

                                                else -> {
                                                    LOG.error(x)

                                                    ResponseError(
                                                        code = ErrorCodes.RequestFailed,
                                                        message = x.message ?: x::class.simpleName ?: "unknown error",
                                                    )
                                                }
                                            }
                                            lspOutgoing.encodeError(requestId, responseError)
                                        }
                                    ).let { encodedResponse ->
                                        runCatching {
                                            lspOutgoing.send(encodedResponse)
                                        }.onFailure {
                                            currentCoroutineContext().job.ensureActive()
                                            LOG.info("Response for $requestMethod is not delivered ($it)")
                                        }
                                    }
                                }.also { requestJob ->
                                    incomingRequestsJobs[requestId] = requestJob
                                    requestJob.invokeOnCompletion {
                                        incomingRequestsJobs.remove(requestId)
                                    }
                                }
                            }

                            LspWireIncoming.Kind.Response -> {
                                val response = message
                                when (val client = outgoingRequests.remove(response.id!!)) {
                                    null -> {
                                        // request was cancelled
                                    }

                                    // the caller gets the result the envelope pass decoded (or decodes it), and the error (`decodeResponse`)
                                    else -> client.deferred.complete(response)
                                }
                            }

                            LspWireIncoming.Kind.Notification -> {
                                val notification = message
                                val notificationMethod = notification.method!!
                                when {
                                    // stays on the loop in both modes, so it cancels at once
                                    notificationMethod == LSP.CancelNotificationType.method -> {
                                        // a bad one is dropped with a warning, as other notifications are
                                        try {
                                            when (val params = notification.decodeParamsOf(notificationMethod, LSP.CancelNotificationType.paramsSerializer, stats)) {
                                                null -> LOG.warn { "Dropping $notificationMethod with no params" }
                                                else -> incomingRequestsJobs.remove(params.id)?.cancel()
                                            }
                                        } catch (x: InvalidParamsException) {
                                            LOG.warn { x.message }
                                        }
                                    }

                                    // suspends while the queue is full: backpressure on the reader, as with Inline
                                    notificationQueue != null -> notificationQueue.send(notification)

                                    else -> handleNotification(notification, notificationMethod)
                                }
                                // The exit notification ends the session: leave the loop so `consume` cancels `incoming`.
                                if (notificationMethod == ExitNotificationType.method) {
                                    break
                                }
                            }
                        }
                    }
                }
                // the loop ended: the worker handles what is queued, then ends, and `withContext` waits for it.
                // On a failure or cancellation, `withContext` cancels the worker instead.
                notificationQueue?.close()
            }
        }
    }
}

/**
 * The client side of a JSON-RPC session over wire messages ([withLspFraming]): [body] gets the [LspClient], and
 * [handlers] answer the peer. Payloads are decoded straight from the frame text, and outgoing frames are sent as they
 * are. Notification handlers run as [notificationDispatch] says (inline by default). A request's result is decoded on
 * the read loop, in the envelope pass, when the response has `id` before `result` (else in the coroutine that called
 * [LspClient.request]); a result that does not decode throws in that coroutine.
 *
 * NOT A STABLE API, see [LspWireCodec].
 */
suspend fun withLsp(
    incoming: ReceiveChannel<LspWireBody>,
    outgoing: SendChannel<LspWireOutgoing>,
    handlers: LspHandlers,
    notificationDispatch: NotificationDispatch = NotificationDispatch.Inline,
    body: suspend CoroutineScope.(LspClient) -> Unit,
) {
    withLspClient(handlers, body) { impl ->
        withLspImpl(incoming = incoming, outgoing = outgoing, body = impl, notificationDispatch = notificationDispatch)
    }
}

/**
 * [withLsp] over the base-protocol frames of [connection], on the wire path ([withLspFraming]), with the notification
 * handlers run as [notificationDispatch] says.
 */
suspend fun withLsp(
    connection: LspConnection,
    exitSignal: CompletableDeferred<Unit>?,
    handlers: LspHandlers,
    notificationDispatch: NotificationDispatch = NotificationDispatch.Inline,
    body: suspend CoroutineScope.(LspClient) -> Unit,
) {
    withLspFraming(connection = connection, exitSignal = exitSignal) { incoming, outgoing ->
        withLsp(incoming = incoming, outgoing = outgoing, handlers = handlers, notificationDispatch = notificationDispatch, body = body)
    }
}

private suspend fun withLspClient(
    handlers: LspHandlers,
    body: suspend CoroutineScope.(LspClient) -> Unit,
    session: suspend (impl: (LspClient) -> Resource<LspHandlers>) -> Unit,
) {
    coroutineScope {
        val futureClient = CompletableDeferred<LspClient>()
        launch {
            session { lspClient ->
                futureClient.complete(lspClient)
                resourceOf(handlers)
            }
        }.use {
            body(futureClient.await())
        }
    }
}

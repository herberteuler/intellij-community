package com.jetbrains.lsp.implementation

import com.jetbrains.lsp.protocol.ErrorCodes
import com.jetbrains.lsp.protocol.RequestType

/**
 * A failed request: as [LspResponseErrorException], the peer answered with an error ([errorCode], the error `data`
 * decoded into [payload]); as [LspResultDecodeException], the result could not be decoded.
 */
sealed class LspException(
    message: String,
    val errorCode: Int,
    val payload: Any?,
    cause: Throwable?,
) : Exception(message, cause)

/**
 * An error answer: the peer answered a request with an error, or a handler threw [throwLspError] to answer with one.
 */
class LspResponseErrorException internal constructor(
    message: String,
    errorCode: Int,
    payload: Any?,
    cause: Throwable?,
) : LspException(
    message = message,
    errorCode = errorCode,
    payload = payload,
    cause = cause,
)

/**
 * Thrown by [LspClient.request] when the peer answered [method] with a result that does not decode with the request
 * type's result serializer. [cause] is the decode failure. The code is [ErrorCodes.RequestFailed], set by this side
 * (the peer sent no error), so code that handles a failed request by its [LspException] code treats it as one. It is
 * not [ErrorCodes.ParseError]: callers read that as "our request was broken" and rethrow it. The session goes on:
 * later requests are not affected.
 */
class LspResultDecodeException internal constructor(
    val method: String,
    cause: Throwable,
) : LspException(
    message = "could not decode the result of $method: ${cause.message ?: cause::class.simpleName}",
    errorCode = ErrorCodes.RequestFailed,
    payload = null,
    cause = cause,
)

fun <E> throwLspError(
    requestType: RequestType<*, *, E>,
    message: String,
    data: E,
    code: Int,
    cause: Throwable? = null,
): Nothing =
    throw LspResponseErrorException(
        message = message,
        errorCode = code,
        payload = data,
        cause = cause,
    )

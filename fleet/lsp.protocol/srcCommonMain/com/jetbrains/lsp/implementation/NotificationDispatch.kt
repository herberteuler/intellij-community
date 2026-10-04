package com.jetbrains.lsp.implementation

/**
 * Where [withLsp] runs the handlers of incoming notifications. Requests are not affected: each request handler runs in
 * its own coroutine in both modes.
 */
enum class NotificationDispatch {
    /**
     * On the read loop, before the next message is read. A slow handler delays every later message, responses too, and
     * a notification is handled before any message that follows it on the wire. Servers need this: a `didChange` must
     * be applied before the next request starts.
     */
    Inline,

    /**
     * In wire order on one worker coroutine; the read loop only puts the notification in a bounded queue. A slow
     * handler delays later notifications only, not responses or requests. So a response that follows a notification on
     * the wire can reach its caller before that notification is handled. `$/cancelRequest` stays on the loop. When the queue is full, the loop waits for room, as it waits for an inline
     * handler.
     *
     * When the incoming channel ends (or `exit` arrives), the worker handles what is queued, and the session ends after
     * it, as it would after the last inline handler. When the session is cancelled, the worker is cancelled with it.
     */
    Sequential,
}

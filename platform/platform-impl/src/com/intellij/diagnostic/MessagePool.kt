// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Attachment
import com.intellij.util.SlowOperations
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.annotations.ApiStatus

/**
 * The class is for routing messages inside an IDE and shouldn't be accessed from plugins.
 * For reporting errors, see [com.intellij.openapi.diagnostic.Logger.error] methods.
 * For receiving reports, register own [com.intellij.openapi.diagnostic.ErrorReportSubmitter].
 */
@ApiStatus.Internal
object MessagePool {
  private const val MAX_POOL_SIZE = 100

  enum class State {
    NoErrors, ReadErrors, UnreadErrors
  }

  @JvmStatic
  fun getInstance(): MessagePool = this

  private val myErrors: MutableList<LogMessage> = ContainerUtil.createLockFreeCopyOnWriteList()
  private val myAdvisors: MutableList<MessagePoolAdvisor> = ContainerUtil.createLockFreeCopyOnWriteList()

  suspend fun addErrorMessage(message: LogMessage) {
    doAddMessage(message)
  }

  val state: State
    get() {
      if (myErrors.isEmpty()) return State.NoErrors
      for (message in myErrors) {
        if (!message.isRead) return State.UnreadErrors
      }
      return State.ReadErrors
    }

  fun getFatalErrors(includeReadMessages: Boolean, includeSubmittedMessages: Boolean): List<LogMessage> {
    val result = ArrayList<LogMessage>(myErrors.size)
    for (message in myErrors) {
      if (!includeReadMessages && message.isRead) continue
      if (!includeSubmittedMessages && (message.isSubmitted || message.throwable is TooManyErrorsException)) continue
      result.add(message)
    }
    return result
  }

  fun clearErrors() {
    for (message in myErrors) {
      message.setRead(true) // expire notifications
    }
    synchronized(myErrors) {
      myErrors.clear()
    }
    for (it in myAdvisors) {
      it.poolCleared()
    }
  }

  /** No-op, unsupported */
  @Deprecated("Use MessagePoolAdvisor")
  @Suppress("removal", "DEPRECATION")
  fun addListener(@Suppress("unused") listener: MessagePoolListener) { }

  /** No-op, unsupported */
  @Deprecated("Use MessagePoolAdvisor")
  @Suppress("removal", "DEPRECATION")
  fun removeListener(@Suppress("unused") listener: MessagePoolListener) { }

  fun addAdvisor(advisor: MessagePoolAdvisor) {
    myAdvisors.add(advisor)
  }

  fun removeAdvisor(advisor: MessagePoolAdvisor) {
    myAdvisors.remove(advisor)
  }

  private fun notifyEntryRead(message: LogMessage) {
    myAdvisors.forEach { it.entryWasRead(message) }
  }

  private suspend fun doAddMessage(message: LogMessage) {
    if (myErrors.lastOrNull() == message) return // already added

    for (listener in myAdvisors) {
      if (!listener.beforeEntryAdded(message)) {
        return
      }
    }

    var message = message
    synchronized(myErrors) {
      if (myErrors.size > MAX_POOL_SIZE) {
        return
      }
      else if (myErrors.size == MAX_POOL_SIZE) {
        message = LogMessage(TooManyErrorsException(), null, mutableListOf<Attachment>())
      }
      else {
        if (ApplicationManager.getApplication().isInternal()) {
          message.allAttachments.forEach { it.isIncluded = true }
        }
        if (shallAddSilently(message)) {
          message.setRead(true)
        }
      }
      message.setOnReadCallback { notifyEntryRead(message) }
      myErrors.add(message)
    }

    for (it in myAdvisors) {
      it.afterEntryAdded(message)
    }
  }

  class TooManyErrorsException internal constructor() : Exception(DiagnosticBundle.message("error.monitor.too.many.errors"))

  private fun shallAddSilently(message: LogMessage): Boolean = SlowOperations.isMyMessage(message.throwable.message)
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("UnhandledExceptions")
package com.intellij.openapi.application.impl

import com.intellij.CommonBundle
import com.intellij.concurrency.currentThreadContextOrNull
import com.intellij.diagnostic.DiagnosticBundle
import com.intellij.ide.actions.ShowLogAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionContextElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.Interactive
import com.intellij.openapi.application.UnhandledExceptionLoggingMode
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.UnhandledException
import com.intellij.openapi.diagnostic.fileLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.ui.EDT
import org.jetbrains.annotations.Nls
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext

private val LOG: Logger = fileLogger()

internal fun processUnhandledException(cause: Throwable, coroutineContext: CoroutineContext?) {
  val coroutineContext = coroutineContext ?: currentThreadContextOrNull()
  val message = "Unhandled exception in ${coroutineContext?.toString() ?: "EDT"}"

  when (val interactiveMode = interactiveMode(coroutineContext)) {
    is Mode.Interactive -> {
      val exception = UnhandledException(cause, isInteractive = true)
      // Write the log on this thread. A caller can stop the process before the EDT runs the block below,
      // and then the exception reaches no log. See IJPL-254578.
      logExceptionSafely(message, exception)

      val app = ApplicationManager.getApplication()
      val showError = (
        !(app == null || app.isHeadlessEnvironment || app.isExitInProgress) &&
        Registry.`is`("ide.exceptions.show.interactive", defaultValue = false)
      )
      if (showError) {
        SwingUtilities.invokeLater {
          val title = when {
            interactiveMode.action != null -> DiagnosticBundle.message("unhandled.exception.dialog.title.with.action", interactiveMode.action)
            else -> DiagnosticBundle.message("unhandled.exception.dialog.title")
          }
          val message = DiagnosticBundle.message("unhandled.exception.dialog.message", cause.javaClass.name, cause.message ?: "'null'")
          val options = arrayOf(CommonBundle.getCloseButtonText(), ShowLogAction.getActionName())
          val choice = Messages.showDialog(null as Project?, message, title, options, 0, Messages.getErrorIcon())
          if (choice == 1) {
            ShowLogAction.showLog()
          }
        }
      }
    }

    Mode.NonInteractive -> {
      logExceptionSafely(message, UnhandledException(cause, isInteractive = false))
    }
  }
}

private fun logExceptionSafely(message: String, exception: UnhandledException) {
  try {
    LOG.error(message, exception)
  }
  catch (_: Throwable) {}
}

/**
 * Is exception was thrown as a part of interactive activity and must be displayed directly to user
 */
private fun interactiveMode(coroutineContext: CoroutineContext?): Mode {
  // kept just in case for binary compatibility
  val deprecatedInteractive = coroutineContext?.get(@Suppress("DEPRECATION") Interactive.Key)
  if (deprecatedInteractive != null) {
    return Mode.Interactive(deprecatedInteractive.action)
  }

  val loggingMode = coroutineContext?.get(UnhandledExceptionLoggingMode.Key)
  if (loggingMode != null) {
    return when (loggingMode) {
      is UnhandledExceptionLoggingMode.Interactive -> Mode.Interactive(loggingMode.action)
      UnhandledExceptionLoggingMode.NonInteractive -> Mode.NonInteractive
    }
  }

  val action = coroutineContext?.get(ActionContextElement)
  if (action != null) {
    val text = ActionManager.getInstance().getAction(action.actionId)?.templatePresentation?.text
    return Mode.Interactive(action = text)
  }

  // Exception thrown on EDT with modal dialog (or no project) has something to do with current user task
  if ((EDT.isCurrentThreadEdt() && LaterInvocator.isInModalContext()) ||
      ApplicationManager.getApplication()
        ?.getServiceIfCreated(ProjectManager::class.java)
        ?.openProjects?.isEmpty() == true
  ) {
    return Mode.Interactive(action = null)
  }

  return Mode.NonInteractive
}

private sealed interface Mode {
  data object NonInteractive : Mode
  data class Interactive(val action: @Nls String?) : Mode
}

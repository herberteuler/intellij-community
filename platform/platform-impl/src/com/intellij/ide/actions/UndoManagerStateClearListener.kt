// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.actions

import com.intellij.openapi.command.impl.UndoManagerImpl
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.ApiStatus.Internal

@Internal
interface UndoManagerStateClearListener {
  companion object {
    @Topic.AppLevel
    val TOPIC: Topic<UndoManagerStateClearListener> = Topic(UndoManagerStateClearListener::class.java, Topic.BroadcastDirection.NONE)
  }

  fun clearUndoStack(undoManager: UndoManagerImpl, editor: FileEditor?)
}

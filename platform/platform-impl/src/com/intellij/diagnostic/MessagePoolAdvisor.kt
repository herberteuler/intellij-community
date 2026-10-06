// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic

import org.jetbrains.annotations.ApiStatus
import java.util.EventListener

@ApiStatus.Internal
interface MessagePoolAdvisor : EventListener {
  /** Return `false` to stop processing and do not add the message to the pool */
  suspend fun beforeEntryAdded(message: AbstractMessage): Boolean = true

  suspend fun afterEntryAdded(message: AbstractMessage) {}

  fun poolCleared() {}

  fun entryWasRead(message: AbstractMessage) {}
}

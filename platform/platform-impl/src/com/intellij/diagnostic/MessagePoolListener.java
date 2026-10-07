// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diagnostic;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.util.EventListener;

/// @deprecated use [MessagePoolAdvisor] instead.
@ApiStatus.Internal
@Deprecated(forRemoval = true)
@SuppressWarnings({"DeprecatedIsStillUsed", "unused"})
public interface MessagePoolListener extends EventListener {
  default boolean beforeEntryAdded(@NotNull AbstractMessage message) {
    return true;
  }

  void newEntryAdded();

  default void poolCleared() { }

  default void entryWasRead() { }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.wsl

import com.intellij.platform.eel.EelDescriptor
import org.jetbrains.annotations.ApiStatus

/**
 * The descriptor of a path inside a WSL distribution.
 *
 * A consumer tests this interface instead of the class `WslEelDescriptor`.
 * That class lives in the optional module `intellij.platform.ide.impl.wsl`.
 * A delegating descriptor does not implement this interface.
 */
@ApiStatus.Internal
interface EelDescriptorWithWslDistribution : EelDescriptor {
  /** The WSL distribution that holds the path. */
  val distribution: WSLDistribution
}

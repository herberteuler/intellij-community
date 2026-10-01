// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.ex.experimental.Version

/**
 * A version from [Version.of], which names its heads by lv. Only a test that builds the graph
 * itself knows which unit an lv names. So a graph reads these lvs as its own, and checks only that
 * they fit.
 */
internal class LvNamedVersion(lvs: Frontier) : Version {
  private val lvVersion = LvVersion(lvs)

  fun lvVersion(): LvVersion {
    return lvVersion
  }

  override fun isRoot(): Boolean {
    return lvVersion.lvs.isEmpty()
  }

  override fun equals(other: Any?): Boolean {
    return other is LvNamedVersion && lvVersion == other.lvVersion
  }

  override fun hashCode(): Int {
    return lvVersion.hashCode()
  }

  override fun toString(): String {
    return lvVersion.toString()
  }
}

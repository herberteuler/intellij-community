// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.ex.experimental

import com.intellij.openapi.editor.impl.experimental.AgentImpl

/**
 * The identity that authors [Event]s. Each event id is an (agent, seq) pair.
 *
 * The order of [compareTo] is total and the same in every process. A merge orders concurrent
 * inserts at one place by it, so equal inputs always merge to equal text.
 */
interface Agent : Comparable<Agent> {
  companion object {
    /**
     * The agent named [name]. Two agents with one name are equal.
     */
    @JvmStatic
    fun createAgent(name: String): Agent {
      return AgentImpl(name)
    }
  }
}

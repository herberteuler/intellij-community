// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.experimental

import com.intellij.openapi.editor.impl.experimental.AgentImpl

/**
 * The identity that authors [Event]s. Each event id is an (agent, seq) pair.
 *
 * The order defined by [compareTo] is total and stable across processes.
 * The Eg-walker merge uses it to break ties between concurrent insertions,
 * so equal inputs always merge to equal text.
 */
interface Agent : Comparable<Agent> {
  companion object {
    fun createAgent(name: String): Agent = AgentImpl(name)
  }
}

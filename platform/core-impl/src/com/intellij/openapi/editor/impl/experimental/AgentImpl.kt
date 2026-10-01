// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.ex.experimental.Agent

internal class AgentImpl(
  private val name: String,
) : Agent {

  init {
    checkName(name)
  }

  override fun compareTo(other: Agent): Int {
    return name.compareTo(implOf(other).name)
  }

  override fun equals(other: Any?): Boolean {
    return other is AgentImpl && name == other.name
  }

  override fun hashCode(): Int {
    return name.hashCode()
  }

  override fun toString(): String {
    return name
  }

  private fun checkName(name: String) {
    require(name.isNotEmpty()) {
      "The agent name is empty"
    }
  }

  companion object {
    fun implOf(agent: Agent): AgentImpl {
      require(agent is AgentImpl) {
        "Foreign Agent implementation: ${agent.javaClass.name}"
      }
      return agent
    }
  }
}

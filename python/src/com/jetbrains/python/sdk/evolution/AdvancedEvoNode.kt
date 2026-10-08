// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.evolution

import com.intellij.icons.AllIcons
import com.intellij.ide.ui.icons.rpcId
import com.intellij.python.sdk.backend.PySdkBundle
import com.intellij.python.sdk.backend.evolution.EvoToolContext
import com.intellij.python.sdk.backend.evolution.evoActionLeaf
import com.intellij.python.sdk.common.evolution.EvoLoadResultDto
import com.intellij.python.sdk.common.evolution.EvoNodeDto
import com.intellij.python.sdk.common.evolution.EvoNodeIds
import com.intellij.python.sdk.common.evolution.EvoNodeKind
import com.intellij.python.sdk.common.evolution.EvoSectionDto
import com.jetbrains.python.sdk.ModuleOrProject
import com.jetbrains.python.sdk.collectAddInterpreterActions

/**
 * The "Custom" node: the full set of add-interpreter actions.
 *
 * It manages no environments and has no tool behind it, so it is not a
 * [com.intellij.python.sdk.backend.evolution.PyEvoEnvironmentProvider]. The core shows it after every provider.
 */
internal object AdvancedEvoNode {
  val node: EvoNodeDto
    get() = EvoNodeDto(
      id = EvoNodeIds.ADVANCED,
      label = PySdkBundle.message("evolution.node.custom"),
      icon = AllIcons.Toolwindows.ToolWindowInternal.rpcId(),
      kind = EvoNodeKind.ADVANCED,
    )

  fun loadSections(context: EvoToolContext): EvoLoadResultDto {
    val actions = collectAddInterpreterActions(ModuleOrProject.ModuleAndProject(context.pyProject.pyProject)) { }
    // Serialize each add-interpreter action by its stable index; the same list is re-collected on click to run it
    // (see PyEvoSdkApiProvider.performNodeAction).
    val leaves = actions.mapIndexed { index, action ->
      val title = action.templatePresentation.text ?: ""
      evoActionLeaf(title = title, icon = action.templatePresentation.icon ?: AllIcons.Toolwindows.ToolWindowInternal,
                    actionId = index.toString())
    }
    return EvoLoadResultDto.Ok(listOf(EvoSectionDto(label = null, leaves = leaves)))
  }
}

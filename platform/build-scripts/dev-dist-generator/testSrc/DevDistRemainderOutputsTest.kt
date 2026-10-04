// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.buildScripts.devDistGenerator

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.intellij.build.devDist.CanonicalJarRecipe
import org.jetbrains.intellij.build.devDist.JarSourceRecipe
import org.jetbrains.intellij.build.devDist.JarWriterRecipe
import org.jetbrains.intellij.build.devDist.PluginPackingAsset
import org.jetbrains.intellij.build.devDist.PluginPackingProjection
import org.jetbrains.intellij.build.devDist.ReusableJarArtifact
import org.jetbrains.intellij.build.devDist.moduleJarAsset
import org.jetbrains.intellij.build.devDist.moduleJarRecipe
import org.jetbrains.intellij.build.devDist.pluginPackingExecutionVersion
import org.junit.jupiter.api.Test

class DevDistRemainderOutputsTest {
  private val nativesRecipe = CanonicalJarRecipe(
    sources = listOf(JarSourceRecipe(input = "natives", kind = "module", filter = "module-v1")),
    writer = JarWriterRecipe(mergeEntities = true, nativeLib = "jna"),
  )

  private val assets = listOf(
    moduleJarAsset("reused"),
    PluginPackingAsset(destination = "lib/moved.jar", inputs = listOf("moved"), recipe = moduleJarRecipe("moved")),
    PluginPackingAsset(destination = "lib/modules/natives.jar", inputs = listOf("natives"), recipe = nativesRecipe),
    PluginPackingAsset(destination = "lib/jna", inputs = listOf("native-tree:natives"), kind = "tree", classPath = false),
    PluginPackingAsset(
      destination = "lib/plugin.jar",
      inputs = listOf("descriptor:plugin", "plugin", "extra"),
      recipe = CanonicalJarRecipe(
        sources = listOf(
          JarSourceRecipe(input = "descriptor:plugin", kind = "file", filter = "none", entry = "META-INF/plugin.xml", options = listOf("patch")),
          JarSourceRecipe(input = "plugin", kind = "module", filter = "module-v1"),
          JarSourceRecipe(input = "extra", kind = "module", filter = "module-v1"),
        ),
        writer = JarWriterRecipe(mergeEntities = true),
      ),
    ),
    PluginPackingAsset(destination = "bin/tool", inputs = listOf("tool"), mode = 493),
    PluginPackingAsset(destination = "kotlinc", inputs = listOf("kotlinc-dist"), kind = "tree", classPath = false),
  )

  private val reused = listOf(
    ReusableJarArtifact(module = "reused", recipe = moduleJarRecipe("reused")),
    ReusableJarArtifact(module = "moved", recipe = moduleJarRecipe("moved")),
    ReusableJarArtifact(module = "natives", recipe = nativesRecipe),
  )

  private fun plan(assets: List<PluginPackingAsset>, reused: List<ReusableJarArtifact>) =
    PluginPackingProjection(version = pluginPackingExecutionVersion(assets), plugin = "intellij.sample", variant = "", assets = assets).plan(reused)

  @Test
  fun `the remainder states each asset that the packer writes and the destination of each reused jar outside lib modules`() {
    val destinations = assets.map { it.destination }.toMutableList()
    destinations[destinations.indexOf("kotlinc")] = "{platform:destination}"

    val outputs = devDistRemainderOutputs(plan(assets, reused), destinations)

    assertThat(outputs.files).containsExactly("lib/plugin.jar" to listOf("plugin", "extra"), "bin/tool" to emptyList())
    assertThat(outputs.trees).containsExactly("{platform:destination}")
    assertThat(outputs.executables).containsExactly("bin/tool")
    assertThat(outputs.independentDestinations).containsExactly("moved" to "lib/moved.jar", "native-tree:natives" to "lib/jna")
  }

  @Test
  fun `a module jar without reuse is a remainder file with its module`() {
    val outputs = devDistRemainderOutputs(plan(listOf(moduleJarAsset("own")), emptyList()), listOf("lib/modules/own.jar"))

    assertThat(outputs.files).containsExactly("lib/modules/own.jar" to listOf("own"))
    assertThat(outputs.independentDestinations).isEmpty()
  }

  @Test
  fun `a link asset is refused, because the packer writes no link`() {
    val link = PluginPackingAsset(destination = "lib/current.jar", inputs = emptyList(), symlinkTarget = "plugin.jar")

    assertThatThrownBy { devDistRemainderOutputs(plan(listOf(link), emptyList()), listOf("lib/current.jar")) }
      .hasMessageContaining("the packer writes only files and trees")
  }
}

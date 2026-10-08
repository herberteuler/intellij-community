// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.compose.ide.plugin.codeInsight

import com.intellij.compose.ide.plugin.codeInsight.highlighting.COMPOSABLE_CALL_TEXT_ATTRIBUTES_KEY
import com.intellij.compose.ide.plugin.shared.ComposeIdeBundle
import com.intellij.compose.ide.plugin.shared.icons.ComposeIdePluginSharedIcons
import com.intellij.compose.ide.plugin.shared.isAndroidPluginLoaded
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.extensions.ExtensionNotApplicableException
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.openapi.util.NlsContexts
import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.idea.highlighter.KotlinHighlighter
import org.jetbrains.kotlin.idea.highlighter.KotlinHighlightingColors
import javax.swing.Icon

private val DEMO_TEXT =
  """
  <A>@Composable</A>
  <K>fun</K> <FD>Text</FD>(<FP>text</FP>: <FP>String</FP>) {}

  <A>@Composable</A>
  <K>fun</K> <FD>Greeting</FD>() {
    <CC>Text</CC>(<FP>"Hello"</FP>)
  }
  """
    .trimIndent()

private val TAG_TO_DESCRIPTOR =
  mapOf(
    "CC" to COMPOSABLE_CALL_TEXT_ATTRIBUTES_KEY,
    "A" to KotlinHighlightingColors.ANNOTATION,
    "K" to KotlinHighlightingColors.KEYWORD,
    "FD" to KotlinHighlightingColors.FUNCTION_DECLARATION,
    "FP" to KotlinHighlightingColors.PARAMETER,
  )

private val COMPOSABLE_CALL_DESCRIPTOR =
  AttributesDescriptor(
    ComposeIdeBundle.message("compose.composable.call.text.attributes.description"),
    COMPOSABLE_CALL_TEXT_ATTRIBUTES_KEY,
  )

internal class ComposeColorSettingPage : ColorSettingsPage {
  init {
    // The Android plugin manages compose color settings if it is loaded
    if (isAndroidPluginLoaded()) throw ExtensionNotApplicableException.create()
  }

  override fun getIcon(): Icon = ComposeIdePluginSharedIcons.ComposeMultiplatform

  override fun getHighlighter(): SyntaxHighlighter = KotlinHighlighter()

  override fun getDemoText(): @NonNls String = DEMO_TEXT

  override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey?> = TAG_TO_DESCRIPTOR

  override fun getAttributeDescriptors(): Array<out AttributesDescriptor?> = arrayOf(COMPOSABLE_CALL_DESCRIPTOR)

  override fun getColorDescriptors(): Array<out ColorDescriptor?> = emptyArray()

  override fun getDisplayName(): @NlsContexts.ConfigurableName String = ComposeIdeBundle.message("compose.color.settings.page.name")
}

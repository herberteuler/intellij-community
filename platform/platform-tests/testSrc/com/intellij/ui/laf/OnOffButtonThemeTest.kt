// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui.laf

import com.intellij.ide.ui.UITheme
import com.intellij.openapi.application.UI
import com.intellij.openapi.util.IconLoader
import com.intellij.platform.ide.impl.icons.PlatformIdeImplIcons
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.IconManager
import com.intellij.ui.components.OnOffButton
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.svg.AttributeMutator
import com.intellij.ui.svg.createJSvgDocument
import com.intellij.util.ui.UIUtil
import com.intellij.util.xml.dom.createXmlStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.UIDefaults
import javax.swing.plaf.ButtonUI

@TestApplication
class OnOffButtonThemeTest {
  @BeforeEach
  fun activateIcons() {
    IconLoader.activate()
    IconManager.activate(null)
  }

  @AfterEach
  fun deactivateIcons() {
    IconManager.deactivate()
    IconLoader.deactivate()
  }

  @ParameterizedTest
  @ValueSource(strings = [
    "darcula", "intellijlaf", "Light", "HighContrast",
    "expUI/expUI_dark", "expUI/expUI_light", "expUI/expUI_light_with_light_header",
    "islands/ManyIslandsDark", "islands/ManyIslandsLight", "islands/ManyIslandsDarcula", "islands/HighContrast",
  ])
  fun `themes inherit the toggle delegate and resolve its colors`(themePath: String) {
    val defaults = loadDefaults(themePath)

    val delegate = "com.intellij.ide.ui.laf.darcula.ui.DarculaOnOffButtonUI"
    assertEquals(delegate, defaults["OnOffButtonUI"], themePath)
    assertNotNull(defaults.getUIClass("OnOffButtonUI", javaClass.classLoader), themePath)

    for (state in listOf("Off", "On", "OffFocused", "OnFocused", "OffDisabled", "OnDisabled")) {
      val path = "/com/intellij/ide/ui/laf/icons/toggle$state.svg"
      javaClass.getResourceAsStream(path)!!.use { stream ->
        createJSvgDocument(createXmlStreamReader(stream), object : AttributeMutator {
          override fun invoke(attributes: MutableMap<String, String>) {
            for (attribute in listOf("color-fill-key", "color-stroke-key")) {
              val key = attributes[attribute] ?: continue
              assertNotNull(defaults.getColor("ColorPalette.$key"), "$themePath: $key")
            }
          }
        })
      }
    }
  }

  @ParameterizedTest
  @CsvSource(
    "darcula, islands/ManyIslandsDarcula",
    "HighContrast, islands/HighContrast",
    "intellijlaf, islands/ManyIslandsLight",
    "Light, islands/ManyIslandsLight",
    "expUI/expUI_light, islands/ManyIslandsLight",
    "expUI/expUI_light_with_light_header, islands/ManyIslandsLight",
    "expUI/expUI_dark, islands/ManyIslandsDark",
  )
  fun `toggle colors match the corresponding Islands theme`(themePath: String, islandsThemePath: String) {
    fun toggleColors(defaults: UIDefaults) = defaults.keys
      .filterIsInstance<String>()
      .filter { it.startsWith("ColorPalette.toggle-") }
      .associateWith { defaults.getColor(it).rgb }

    val expected = toggleColors(loadDefaults(islandsThemePath))
    assertEquals(13, expected.size, islandsThemePath)
    assertEquals(expected, toggleColors(loadDefaults(themePath)), themePath)
  }

  private fun loadDefaults(themePath: String): UIDefaults {
    val data = javaClass.getResourceAsStream("/themes/$themePath.theme.json")!!.use { it.readBytes() }
    val theme = UITheme.loadFromJsonWithParent(data, themePath, javaClass.classLoader)
    return UIDefaults().also { theme.applyTheme(it) }
  }

  @ParameterizedTest
  @ValueSource(ints = [20, 22, 24, 28, 32])
  fun `toggle is vertically centered in compact list rows`(height: Int): Unit = timeoutRunBlocking {
    val defaults = loadDefaults("darcula")
    val center = withContext(Dispatchers.UI) {
      val renderer = listCellRenderer {
        rowHeight = height
        text(value)
        switch(isOn = true)
      }
      val list = JList(arrayOf("Toggle"))
      val row = renderer.getListCellRendererComponent(list, "Toggle", 0, true, false) as JComponent
      val button = UIUtil.findComponentOfType(row, OnOffButton::class.java)!!
      button.setUI(defaults.getUI(button) as ButtonUI)
      fun layout(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach { layout(it) }
      }
      row.setSize(200, height)
      layout(row)
      val bounds = SwingUtilities.convertRectangle(button.parent, button.bounds, row)
      val iconHeight = PlatformIdeImplIcons.ToggleOn.iconHeight
      assertEquals(JBUIScale.scale(22), iconHeight)
      bounds.y + (bounds.height - iconHeight) / 2 + iconHeight / 2
    }
    assertEquals(height / 2, center)
  }
}

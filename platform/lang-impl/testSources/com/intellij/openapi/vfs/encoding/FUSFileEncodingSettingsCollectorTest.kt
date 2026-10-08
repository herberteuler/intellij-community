// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.encoding

import com.intellij.internal.statistic.FUCollectorTestCase
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.encoding.EncodingProjectManagerImpl.BOMForNewUTF8Files
import com.intellij.openapi.vfs.encoding.FUSFileEncodingSettingsCollector.Setting
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import com.jetbrains.fus.reporting.model.lion3.LogEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.nio.charset.StandardCharsets
import javax.swing.JTable

@TestApplication
internal class FUSFileEncodingSettingsCollectorTest {
  private val project: Project by projectFixture()

  @TestDisposable
  private lateinit var disposable: Disposable

  @ParameterizedTest
  @EnumSource(Setting::class)
  fun `reports only the changed setting and its restoration`(setting: Setting): Unit = timeoutRunBlocking {
    val events = withContext(Dispatchers.EDT) {
      val manager = EncodingProjectManager.getInstance(project) as EncodingProjectManagerImpl
      val appManager = EncodingManager.getInstance()
      val before = FUSFileEncodingSettingsCollector.captureState(project)
      val file = LightVirtualFile("private-file-name.txt")
      try {
        collectEvents {
          when (setting) {
            Setting.IDE_ENCODING -> appManager.defaultCharsetName =
              if (before.ideEncoding == StandardCharsets.UTF_16.name()) StandardCharsets.UTF_8.name() else StandardCharsets.UTF_16.name()
            Setting.PROJECT_ENCODING -> manager.setEncoding(null, StandardCharsets.UTF_16)
            Setting.PROPERTIES_ENCODING -> manager.setDefaultCharsetForPropertiesFiles(null, StandardCharsets.UTF_16)
            Setting.NATIVE_TO_ASCII -> manager.setNative2AsciiForPropertiesFiles(null, !before.nativeToAscii)
            Setting.UTF8_BOM -> manager.setBOMForNewUtf8Files(BOMForNewUTF8Files.ALWAYS)
            Setting.PATH_MAPPINGS -> manager.setEncoding(file, StandardCharsets.UTF_16)
          }
          FUSFileEncodingSettingsCollector.logChanges(project, before)
          val after = FUSFileEncodingSettingsCollector.captureState(project)
          when (setting) {
            Setting.IDE_ENCODING -> appManager.defaultCharsetName = before.ideEncoding
            Setting.PROJECT_ENCODING -> manager.setEncoding(null, before.projectEncoding)
            Setting.PROPERTIES_ENCODING -> manager.setDefaultCharsetForPropertiesFiles(null, before.propertiesEncoding)
            Setting.NATIVE_TO_ASCII -> manager.setNative2AsciiForPropertiesFiles(null, before.nativeToAscii)
            Setting.UTF8_BOM -> manager.setBOMForNewUtf8Files(before.utf8Bom)
            Setting.PATH_MAPPINGS -> manager.setEncoding(file, null)
          }
          FUSFileEncodingSettingsCollector.logChanges(project, after)
        }
      }
      finally {
        appManager.defaultCharsetName = before.ideEncoding
      }
    }
    assertThat(events).hasSize(2)
    assertSettingChanged(events.take(1), setting)
    assertSettingChanged(events.drop(1), setting)
  }

  @Test
  fun `reports mapping replacements with the same number of entries`(): Unit = timeoutRunBlocking {
    val events = withContext(Dispatchers.EDT) {
      val manager = EncodingProjectManager.getInstance(project) as EncodingProjectManagerImpl
      val file = LightVirtualFile("private-file-name.txt")
      manager.setEncoding(file, StandardCharsets.UTF_8)
      val before = FUSFileEncodingSettingsCollector.captureState(project)
      collectEvents {
        manager.setEncoding(file, StandardCharsets.UTF_16)
        FUSFileEncodingSettingsCollector.logChanges(project, before)
      }
    }
    assertSettingChanged(events, Setting.PATH_MAPPINGS)
  }

  @Test
  fun `applying unchanged settings reports nothing`(): Unit = timeoutRunBlocking {
    val events = withContext(Dispatchers.EDT) {
      val configurable = FileEncodingConfigurable(project)
      try {
        configurable.createComponent()
        configurable.reset()
        collectEvents { configurable.apply() }
      }
      finally {
        configurable.disposeUIResources()
      }
    }
    assertThat(events).isEmpty()
  }

  @Test
  fun `applying a change once reports one event`(): Unit = timeoutRunBlocking {
    val events = withContext(Dispatchers.EDT) {
      val configurable = FileEncodingConfigurable(project)
      try {
        val component = configurable.createComponent()
        configurable.reset()
        val nativeToAscii = checkNotNull(UIUtil.findComponentOfType(component, JBCheckBox::class.java))
        nativeToAscii.isSelected = !nativeToAscii.isSelected
        collectEvents {
          configurable.apply()
          configurable.apply()
        }
      }
      finally {
        configurable.disposeUIResources()
      }
    }
    assertSettingChanged(events, Setting.NATIVE_TO_ASCII)
  }

  @Test
  fun `unapplied mapping reports nothing`(): Unit = timeoutRunBlocking {
    val events = withContext(Dispatchers.EDT) {
      val configurable = FileEncodingConfigurable(project)
      try {
        val component = configurable.createComponent()
        configurable.reset()
        configurable.selectFile(LightVirtualFile("outside-the-project.txt"))
        val table = checkNotNull(UIUtil.findComponentOfType(component, JTable::class.java))
        table.model.setValueAt(StandardCharsets.UTF_16, 0, 1)
        collectEvents { configurable.apply() }
      }
      finally {
        configurable.disposeUIResources()
      }
    }
    assertThat(events).isEmpty()
  }

  @Test
  fun `discarding a change reports nothing`(): Unit = timeoutRunBlocking {
    val events = withContext(Dispatchers.EDT) {
      collectEvents {
        val configurable = FileEncodingConfigurable(project)
        try {
          val component = configurable.createComponent()
          configurable.reset()
          val nativeToAscii = checkNotNull(UIUtil.findComponentOfType(component, JBCheckBox::class.java))
          nativeToAscii.isSelected = !nativeToAscii.isSelected
          configurable.reset()
        }
        finally {
          configurable.disposeUIResources()
        }
      }
    }
    assertThat(events).isEmpty()
  }

  private fun collectEvents(action: () -> Unit): List<LogEvent> =
    FUCollectorTestCase.collectLogEvents(disposable, action).filter { it.group.id == "file.encoding.settings" }

  private fun assertSettingChanged(events: List<LogEvent>, setting: Setting) {
    assertThat(events).hasSize(1)
    val event = events.single().event
    assertThat(event.id).isEqualTo("setting.changed")
    assertThat(event.data["setting"]).isEqualTo(setting.name)
    when (setting) {
      Setting.IDE_ENCODING -> assertThat(event.data.keys).containsExactly("setting")
      Setting.PROJECT_ENCODING, Setting.PROPERTIES_ENCODING, Setting.NATIVE_TO_ASCII, Setting.UTF8_BOM, Setting.PATH_MAPPINGS ->
        assertThat(event.data.keys).containsExactlyInAnyOrder("setting", "project")
    }
  }
}

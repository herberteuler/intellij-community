// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.vfs.encoding

import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.eventLog.events.EventId1
import com.intellij.internal.statistic.service.fus.collectors.CounterUsagesCollector
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.encoding.EncodingProjectManagerImpl.BOMForNewUTF8Files
import java.nio.charset.Charset

internal object FUSFileEncodingSettingsCollector : CounterUsagesCollector() {
  private val GROUP: EventLogGroup = EventLogGroup("file.encoding.settings", 1)
  private val SETTING_CHANGED: EventId1<Setting> = GROUP.registerEvent("setting.changed", EventFields.Enum("setting", Setting::class.java))

  override fun getGroup(): EventLogGroup = GROUP

  enum class Setting {
    IDE_ENCODING,
    PROJECT_ENCODING,
    PROPERTIES_ENCODING,
    NATIVE_TO_ASCII,
    UTF8_BOM,
    PATH_MAPPINGS,
  }

  data class State(
    val ideEncoding: String,
    val projectEncoding: Charset?,
    val propertiesEncoding: Charset?,
    val nativeToAscii: Boolean,
    val utf8Bom: BOMForNewUTF8Files,
    val pathMappings: Map<String, Charset>,
  )

  @JvmStatic
  fun captureState(project: Project): State {
    val manager = EncodingProjectManager.getInstance(project) as EncodingProjectManagerImpl
    return State(
      ideEncoding = EncodingManager.getInstance().defaultCharsetName,
      projectEncoding = manager.configuredDefaultCharset,
      propertiesEncoding = manager.getDefaultCharsetForPropertiesFiles(null),
      nativeToAscii = manager.isNative2AsciiForPropertiesFiles,
      utf8Bom = manager.bomForNewUTF8Files,
      pathMappings = manager.allPointersMappings.entries.associate { (pointer, charset) -> pointer.url to charset },
    )
  }

  @JvmStatic
  fun logChanges(project: Project, before: State) {
    val after = captureState(project)
    if (before.ideEncoding != after.ideEncoding) {
      SETTING_CHANGED.log(Setting.IDE_ENCODING)
    }
    if (before.projectEncoding != after.projectEncoding) {
      SETTING_CHANGED.log(project, Setting.PROJECT_ENCODING)
    }
    if (before.propertiesEncoding != after.propertiesEncoding) {
      SETTING_CHANGED.log(project, Setting.PROPERTIES_ENCODING)
    }
    if (before.nativeToAscii != after.nativeToAscii) {
      SETTING_CHANGED.log(project, Setting.NATIVE_TO_ASCII)
    }
    if (before.utf8Bom != after.utf8Bom) {
      SETTING_CHANGED.log(project, Setting.UTF8_BOM)
    }
    if (before.pathMappings != after.pathMappings) {
      SETTING_CHANGED.log(project, Setting.PATH_MAPPINGS)
    }
  }
}

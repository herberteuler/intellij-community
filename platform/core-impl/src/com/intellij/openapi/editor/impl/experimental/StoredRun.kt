// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.editor.experimental.Event

/**
 * One stored run: the public [com.intellij.openapi.editor.experimental.Event] plus its graph links. The run covers the lvs
 * `[lvStart, lvStart + event.length())`. [parents] belong to the first unit; every
 * later unit has the one implicit parent `lv - 1`.
 */
internal class StoredRun(
  val event: Event,
  val lvStart: LV,
  val parents: IntArray,
) {
  fun lvEnd(): LV {
    return lvStart + event.length()
  }
}

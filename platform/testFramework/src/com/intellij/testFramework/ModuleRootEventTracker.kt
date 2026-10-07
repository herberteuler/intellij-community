// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testFramework

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

class ModuleRootEventTracker(private val project: Project) : ModuleRootListener {
  var beforeCount: Int = 0
    private set
  var afterCount: Int = 0
    private set
  var modificationCount: Long = 0
    private set

  override fun beforeRootsChange(event: ModuleRootEvent) {
    beforeCount++
  }

  override fun rootsChanged(event: ModuleRootEvent) {
    afterCount++
  }

  fun reset() {
    beforeCount = 0
    afterCount = 0
    modificationCount = ProjectRootManager.getInstance(project).modificationCount
  }

  fun assertEventsCountAndIncrementModificationCount(
    eventsCount: Int,
    modificationCountMustBeIncremented: Boolean,
    modificationCountMayBeIncremented: Boolean,
  ) {
    val beforeCount = this.beforeCount
    val afterCount = this.afterCount
    assertEquals("beforeCount = $beforeCount, afterCount = $afterCount", beforeCount, afterCount)
    assertEquals(eventsCount, beforeCount)
    val currentModificationCount = ProjectRootManager.getInstance(project).modificationCount
    if (modificationCountMayBeIncremented) {
      assertTrue(currentModificationCount >= modificationCount)
    }
    else if (modificationCountMustBeIncremented) {
      assertTrue(currentModificationCount > modificationCount)
    }
    else {
      assertEquals(modificationCount, currentModificationCount)
    }
    reset()
  }

  @JvmOverloads
  fun assertNoEvents(modificationCountMayBeIncremented: Boolean = false) {
    assertEventsCountAndIncrementModificationCount(0, false, modificationCountMayBeIncremented)
  }

  fun assertEventsCount(count: Int) {
    assertEventsCountAndIncrementModificationCount(count, count != 0, false)
  }
}
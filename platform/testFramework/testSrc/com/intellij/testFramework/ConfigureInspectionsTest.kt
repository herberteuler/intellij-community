// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testFramework

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

@TestApplication
internal class ConfigureInspectionsTest {
  private val project by projectFixture()

  @Test
  fun `disposal removes keys for all configured inspections`(@TestDisposable parentDisposable: Disposable): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val first = TestInspection("ConfigureInspectionsFirst")
      val second = TestInspection("ConfigureInspectionsSecond")
      val disposable = Disposer.newDisposable(parentDisposable)
      try {
        configureInspections(arrayOf(first, second), project, disposable)
        assertNotNull(HighlightDisplayKey.find(first.shortName))
        assertNotNull(HighlightDisplayKey.find(second.shortName))

        Disposer.dispose(disposable)

        assertAll(
          { assertNull(HighlightDisplayKey.find(first.shortName)) },
          { assertNull(HighlightDisplayKey.find(second.shortName)) },
        )
      }
      finally {
        Disposer.dispose(disposable)
        HighlightDisplayKey.unregister(first.shortName)
        HighlightDisplayKey.unregister(second.shortName)
      }
    }
  }

  @Test
  fun `disposal preserves a preexisting key`(@TestDisposable parentDisposable: Disposable): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tool = TestInspection("ConfigureInspectionsExisting")
      val key = HighlightDisplayKey.findOrRegister(tool.shortName, "Existing display name", "ExistingInspectionId")
      val disposable = Disposer.newDisposable(parentDisposable)
      try {
        configureInspections(arrayOf(tool), project, disposable)

        Disposer.dispose(disposable)

        assertSame(key, HighlightDisplayKey.find(tool.shortName))
        assertThat(HighlightDisplayKey.findById("ExistingInspectionId")).isSameAs(key)
        assertEquals("Existing display name", HighlightDisplayKey.getDisplayNameByKey(key))
      }
      finally {
        Disposer.dispose(disposable)
        HighlightDisplayKey.unregister(tool.shortName)
      }
    }
  }

  @Test
  fun `disposal preserves a replacement key`(@TestDisposable parentDisposable: Disposable): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val tool = TestInspection("ConfigureInspectionsReplaced")
      val disposable = Disposer.newDisposable(parentDisposable)
      try {
        configureInspections(arrayOf(tool), project, disposable)
        HighlightDisplayKey.unregister(tool.shortName)
        val replacement = HighlightDisplayKey.findOrRegister(tool.shortName, "Replacement display name")

        Disposer.dispose(disposable)

        assertSame(replacement, HighlightDisplayKey.find(tool.shortName))
        assertEquals("Replacement display name", HighlightDisplayKey.getDisplayNameByKey(replacement))
      }
      finally {
        Disposer.dispose(disposable)
        HighlightDisplayKey.unregister(tool.shortName)
      }
    }
  }

  @Test
  fun `disposal removes keys after configuration fails`(@TestDisposable parentDisposable: Disposable): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val first = TestInspection("ConfigureInspectionsBeforeFailure")
      val second = object : TestInspection("ConfigureInspectionsFailure") {
        override fun getDisplayName(): String = error("Cannot get the display name")
      }
      val disposable = Disposer.newDisposable(parentDisposable)
      try {
        assertThrows(IllegalStateException::class.java) {
          configureInspections(arrayOf(first, second), project, disposable)
        }
        assertNotNull(HighlightDisplayKey.find(first.shortName))
        assertNotNull(HighlightDisplayKey.find(second.shortName))

        Disposer.dispose(disposable)

        assertAll(
          { assertNull(HighlightDisplayKey.find(first.shortName)) },
          { assertNull(HighlightDisplayKey.find(second.shortName)) },
        )
      }
      finally {
        Disposer.dispose(disposable)
        HighlightDisplayKey.unregister(first.shortName)
        HighlightDisplayKey.unregister(second.shortName)
      }
    }
  }

  private open class TestInspection(private val name: String) : GlobalInspectionTool() {
    override fun getShortName(): String = name

    override fun getDisplayName(): String = name
  }
}
// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.impl.JBTabsImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import javax.swing.JLabel

@TestApplication
@Timeout(30)
class JBTabsReallocateTest {
  @Test
  fun `overlapping targets swap adjacent tabs in both directions`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val disposable = Disposer.newDisposable()
      try {
        val tabs = JBTabsImpl(null, disposable)
        val first = tabs.addTab(TabInfo(JLabel()).setText("A"))
        val second = tabs.addTab(TabInfo(JLabel()).setText("B"))
        tabs.reallocate(first, second)
        assertEquals(listOf(second, first), tabs.tabs)
        tabs.reallocate(first, second)
        assertEquals(listOf(first, second), tabs.tabs)
      }
      finally {
        Disposer.dispose(disposable)
      }
    }
  }

  @Test
  fun `overlapping targets permit both end positions`(): Unit = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val disposable = Disposer.newDisposable()
      try {
        val tabs = JBTabsImpl(null, disposable)
        val first = tabs.addTab(TabInfo(JLabel()).setText("A"))
        val second = tabs.addTab(TabInfo(JLabel()).setText("B"))
        val third = tabs.addTab(TabInfo(JLabel()).setText("C"))
        tabs.reallocate(first, third)
        assertEquals(listOf(second, third, first), tabs.tabs)
        tabs.reallocate(first, second)
        assertEquals(listOf(first, second, third), tabs.tabs)
      }
      finally {
        Disposer.dispose(disposable)
      }
    }
  }
}

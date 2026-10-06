// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.xml

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.DefaultPluginDescriptor
import com.intellij.openapi.extensions.ExtensionDescriptor
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.extensions.impl.ExtensionPointImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xml.dom.XmlElement
import com.intellij.util.xml.impl.DomImplementationClassEP
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

class ConverterCacheCancellationTest : BasePlatformTestCase() {
  override fun runInDispatchThread(): Boolean = false

  // IJPL-257210
  fun testCancelledBuildOfConverterCacheDoesNotBreakLaterLookups() {
    registerLazyConverterImplementation()
    val converterManager = service<ConverterManager>()

    timeoutRunBlocking {
      val cancelledRead = launch(Dispatchers.Default) {
        val readJob = coroutineContext.job
        readAction {
          // the cancellation comes after the start of the read, so the converter cache is built under a cancelled job
          readJob.cancel()
          converterManager.getConverterInstance(MyConverter::class.java)
        }
      }
      // a swallowed cancellation makes the lookup fail, and the failure of the child fails this test
      cancelledRead.join()
      assertTrue(cancelledRead.isCancelled)

      assertInstanceOf(readAction { converterManager.getConverterInstance(MyConverter::class.java) }, MyConverterImpl::class.java)
    }
  }

  /**
   * Registers the extension without a listener notification, so it has no instance until the converter cache is built.
   * The registration also drops the converter cache.
   */
  private fun registerLazyConverterImplementation() {
    @Suppress("UNCHECKED_CAST")
    val point = ApplicationManager.getApplication().extensionArea
      .getExtensionPoint<DomImplementationClassEP>("com.intellij.dom.converter") as ExtensionPointImpl<DomImplementationClassEP>
    val pluginDescriptor = DefaultPluginDescriptor(PluginId.getId(javaClass.name), javaClass.classLoader)
    val element = XmlElement(name = "dom.converter",
                             attributes = mapOf("interfaceClass" to MyConverter::class.java.name,
                                                "implementationClass" to MyConverterImpl::class.java.name))
    point.registerExtensions(listOf(ExtensionDescriptor(implementation = null, os = null, orderId = null, order = LoadingOrder.ANY,
                                                        element = element, hasExtraAttributes = false)),
                             pluginDescriptor, null)
    Disposer.register(testRootDisposable) {
      point.unregisterExtensions({ _, adapter -> adapter.pluginDescriptor !== pluginDescriptor }, false)
    }
  }

  abstract class MyConverter : Converter<String>()

  class MyConverterImpl : MyConverter() {
    override fun fromString(s: String?, context: ConvertContext): String? = s

    override fun toString(t: String?, context: ConvertContext): String? = t
  }
}

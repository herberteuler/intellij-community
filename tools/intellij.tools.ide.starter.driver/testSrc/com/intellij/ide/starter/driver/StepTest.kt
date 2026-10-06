package com.intellij.ide.starter.driver

import com.intellij.driver.client.Driver
import com.intellij.driver.sdk.DriverTestLogger
import com.intellij.driver.sdk.step
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy

internal class StepTest {
  @Test
  fun `driver step writes to the log of its driver`() {
    val driver = FakeDriver()

    assertEquals(42, driver.proxy.step("compute") { 42 })

    assertEquals(2, driver.lines.size)
    assertEquals("info: Step 'compute' started", driver.lines[0])
    assertMatches("info: Step 'compute' finished in .+", driver.lines[1])
  }

  @Test
  fun `step without a driver gives the result of its action`() {
    assertEquals("done", step("compute") { "done" })
  }

  @Test
  fun `failed step writes a warning and rethrows the error`() {
    val driver = FakeDriver()
    val error = IllegalStateException("boom")

    val thrown = assertThrows<IllegalStateException> { driver.proxy.step("fail") { throw error } }

    assertSame(error, thrown)
    assertMatches("warn: Step 'fail' failed in .+ with 'boom'", driver.lines[1])
  }

  @Test
  fun `step skips a driver without a connection`() {
    val driver = FakeDriver(isConnected = false)

    driver.proxy.step("click") {}

    assertEquals(emptyList<String>(), driver.lines)
  }

  @Test
  fun `step does not fail when the log of a driver fails`() {
    val driver = FakeDriver(failingLogger = true)

    assertEquals("done", driver.proxy.step("unaffected") { "done" })
  }

  private fun assertMatches(pattern: String, actual: String) {
    assertTrue(Regex(pattern).matches(actual), "'$actual' does not match '$pattern'")
  }

  private class FakeDriver(isConnected: Boolean = true, failingLogger: Boolean = false) {
    val lines = mutableListOf<String>()

    private val logger = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DriverTestLogger::class.java)) { _, method, args ->
      if (failingLogger) error("The IDE is gone")
      lines.add("${method.name}: ${args.single()}")
      null
    } as DriverTestLogger

    val proxy: Driver = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Driver::class.java)) { self, method, args ->
      when (method.name) {
        "isConnected" -> isConnected
        "utility" -> logger
        "hashCode" -> System.identityHashCode(self)
        "equals" -> self === args.single()
        "toString" -> "FakeDriver"
        else -> error("Unexpected call: ${method.name}")
      }
    } as Driver
  }
}

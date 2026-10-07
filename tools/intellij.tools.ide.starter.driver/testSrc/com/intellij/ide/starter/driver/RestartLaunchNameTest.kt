package com.intellij.ide.starter.driver

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RestartLaunchNameTest {
  @Test
  fun `an unnamed launch gets the first restart name`() {
    nextRestartLaunchNameOf("") shouldBe "restarted"
  }

  @Test
  fun `a named launch keeps its name as the base`() {
    nextRestartLaunchNameOf("foo") shouldBe "foo-restarted"
  }

  @Test
  fun `a restarted launch gets a number and then increments it`() {
    nextRestartLaunchNameOf("restarted") shouldBe "restarted2"
    nextRestartLaunchNameOf("foo-restarted") shouldBe "foo-restarted2"
    nextRestartLaunchNameOf("foo-restarted2") shouldBe "foo-restarted3"
    nextRestartLaunchNameOf("restarted9") shouldBe "restarted10"
  }

  @Test
  fun `a launch named after a restart of its own is not a restart`() {
    nextRestartLaunchNameOf("restart") shouldBe "restart-restarted"
    nextRestartLaunchNameOf("unrestarted") shouldBe "unrestarted-restarted"
  }
}

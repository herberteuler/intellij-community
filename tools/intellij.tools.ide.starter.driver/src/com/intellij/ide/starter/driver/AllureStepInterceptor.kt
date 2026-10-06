package com.intellij.ide.starter.driver

import com.intellij.driver.sdk.StepInterceptor
import io.qameta.allure.Allure

internal class AllureStepInterceptor : StepInterceptor {
  override fun <T> intercept(name: String, action: () -> T): T {
    if (Allure.getLifecycle().currentTestCaseOrStep.isEmpty) return action()
    return Allure.step(name, Allure.ThrowableContextRunnable { action() })
  }
}

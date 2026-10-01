// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.roots;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.TestModuleProperties;
import com.intellij.testFramework.JavaModuleTestCase;

public class ModuleTestPropertiesTest extends JavaModuleTestCase {
  public void testSetAndGet() {
    Module tests = createModule("tests");
    TestModuleProperties moduleProperties = TestModuleProperties.getInstance(tests);
    ApplicationManager.getApplication().runWriteAction(() -> {
      moduleProperties.setProductionModuleName(myModule.getName());
    });
    assertSame(myModule, moduleProperties.getProductionModule());
  }
}

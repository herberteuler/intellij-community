// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInspection;

import com.intellij.codeInspection.booleanIsAlwaysInverted.BooleanMethodIsAlwaysInvertedInspection;

public class BooleanMethodIsAlwaysInvertedLocalInspectionTest extends BooleanMethodIsAlwaysInvertedInspectionTest {

  @Override
  protected void doTest(boolean checkRange) {
    doTest("invertedBoolean/" + getTestName(true), new BooleanMethodIsAlwaysInvertedInspection().getSharedLocalInspectionTool());
  }
}

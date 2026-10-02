// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight.daemon.quickFix;

import com.intellij.codeInsight.daemon.quickFix.LightQuickFixParameterizedTestCase;

public class RemoveNewKeywordTest extends LightQuickFixParameterizedTestCase {
  @Override
  protected String getBasePath() {
    return "/codeInsight/highlight/remove_new";
  }
}

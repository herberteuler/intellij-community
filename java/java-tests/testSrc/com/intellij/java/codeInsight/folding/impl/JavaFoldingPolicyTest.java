// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight.folding.impl;

import com.intellij.codeInsight.folding.impl.AbstractFoldingPolicyTest;
import com.intellij.codeInsight.folding.impl.FoldingPolicy;
import com.intellij.psi.PsiElement;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTreeUtil;

public class JavaFoldingPolicyTest extends AbstractFoldingPolicyTest {
  public void testAdditionalChildDocComments() {
    myFixture.configureByText("Test.java",
                              """
                                /** outer **/
                                class Test {
                                /** <caret>inner **/
                                }""");
    PsiElement element = PsiTreeUtil.getParentOfType(myFixture.getFile().findElementAt(myFixture.getCaretOffset()),
                                                     PsiDocComment.class, false);
    assertNotNull(element);
    String signature = FoldingPolicy.getSignature(element);
    if (signature != null) {
      assertEquals(element, FoldingPolicy.restoreBySignature(element.getContainingFile(), signature));
    }
  }
}

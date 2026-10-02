// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight.folding.impl;

import com.intellij.codeInsight.folding.impl.OffsetsElementSignatureProvider;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

public class JavaOffsetsElementSignatureProviderTest extends LightJavaCodeInsightFixtureTestCase {
  private final OffsetsElementSignatureProvider myProvider = new OffsetsElementSignatureProvider();

  public void testJavaStringLiteral() {
    String text =
      """
        class Test {
            void test() {
                bundle.getMessage("this.is.my.key");
            }
        }""";
    myFixture.configureByText("test.java", text);

    int startOffset = text.indexOf('"');
    int endOffset = text.indexOf(')', startOffset);


    String baseSignature = String.format("e#%d#%d", startOffset, endOffset);
    PsiElement implicitTop = myProvider.restoreBySignature(myFixture.getFile(), baseSignature, null);
    assertNotNull(implicitTop);

    PsiElement top = myProvider.restoreBySignature(myFixture.getFile(), baseSignature + "#0", null);
    assertSame(implicitTop, top);

    PsiElement bottom = myProvider.restoreBySignature(myFixture.getFile(), baseSignature + "#1", null);
    assertNotNull(bottom);
    assertNotSame(top, bottom);
  }
}

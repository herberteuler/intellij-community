// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

public class CreateInnerClassFromNewPreviewTest extends LightJavaCodeInsightFixtureTestCase {
  public void testPreviewWhenTargetClassIsInAnotherFile() {
    myFixture.addClass("public class B { }");
    myFixture.configureByText("Test.java", """
      public class Test {
        void f() {
          new B.<caret>Builder();
        }
      }""");
    IntentionAction action = myFixture.findSingleIntention("Create inner class 'Builder'");
    String previewText = myFixture.getIntentionPreviewText(action);
    assertNotNull(previewText);
    assertEquals("""
                   public class B { 
                       public static class Builder {
                       }
                   }""", previewText);
  }

  public void testPreviewWhenTargetClassIsInTheSameFile() {
    myFixture.configureByText("Test.java", """
      public class Test { 
        void f() {
          new B.<caret>Builder();
        }
      }
      class B { }""");
    IntentionAction action = myFixture.findSingleIntention("Create inner class 'Builder'");
    String previewText = myFixture.getIntentionPreviewText(action);
    assertEquals("""
                   public class Test {
                     void f() {
                       new B.Builder();
                     }
                   }
                   class B {
                       public static class Builder {
                       }
                   }""", previewText);
  }
}

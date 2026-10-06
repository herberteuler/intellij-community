// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.intellij.openapi.fileEditor.impl;

import com.intellij.application.options.CodeStyle;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class InconsistentLineSeparatorsTest extends BasePlatformTestCase {
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myFixture.enableInspections(new InconsistentLineSeparatorsInspection());
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); // invokeLater() in EncodingProjectManagerImpl.reloadAllFilesUnder()
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      super.tearDown();
    }
  }

  public void testMixedLineSeparators() throws IOException {
    String rawText = "abc\r\ndef\nghi";
    configureFromLiteralText("<warning descr=\"Line separators in the current file (\\n, \\r\\n) differ from the project defaults (\\n)\">" + rawText +"</warning>");

    CodeStyle.getSettings(getProject()).LINE_SEPARATOR = "\n";
    myFixture.checkHighlighting(true, false, false);
  }

  public void testLineSeparatorsDifferentFromProjectDefault() throws IOException {
    String rawText = "abc\r\ndef\r\nghi";
    configureFromLiteralText("<warning descr=\"Line separators in the current file (\\r\\n) differ from the project defaults (\\n)\">" + rawText +"</warning>");

    CodeStyle.getSettings(getProject()).LINE_SEPARATOR = "\n";
    myFixture.checkHighlighting(true, false, false);
  }

  public void testMustWarnAboutMixedSeparatorsEvenWhenTheDetectedLineSeparatorIsProject() throws IOException {
    String rawText = "abc\rdef\ndef\ndef\nghi";
    configureFromLiteralText("<warning descr=\"Line separators in the current file (\\n, \\r) differ from the project defaults (\\n)\">" + rawText +"</warning>");
    assertEquals("\n", myFixture.getFile().getVirtualFile().getDetectedLineSeparator());

    CodeStyle.getSettings(getProject()).LINE_SEPARATOR = "\n";
    myFixture.checkHighlighting(true, false, false);
  }

  public void testMustNotWarnAboutOneLiners() throws IOException {
    String rawText = "abc";
    configureFromLiteralText(rawText);
    CodeStyle.getSettings(getProject()).LINE_SEPARATOR = "\n";
    myFixture.checkHighlighting(true, false, false);
  }


  private void configureFromLiteralText(@NotNull String rawText) throws IOException {
    WriteAction.run(() -> {
      PsiFile file = myFixture.configureByText("text.txt", StringUtil.convertLineSeparators(rawText));
      file.getVirtualFile().setBinaryContent(rawText.getBytes(StandardCharsets.UTF_8));
    });
  }
}

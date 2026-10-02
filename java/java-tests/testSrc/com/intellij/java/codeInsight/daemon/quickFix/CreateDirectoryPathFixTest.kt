// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight.daemon.quickFix

import com.intellij.codeInsight.intention.IntentionAction

class CreateDirectoryPathFixTest : CreateFileQuickFixTestCase() {

  override fun setUp() {
    super.setUp()

    myFixture.copyDirectoryToProject("src", "")
  }

  override fun getTestCasePath(): String {
    return "/codeInsight/daemonCodeAnalyzer/quickFix/createDirectoryPath"
  }

  fun testCreateSourceDirectory() {
    assertIntentionCreatesDir("srcdir", "/main/java/long/srcdir", "/main/java/pkg/ClassWithDirectoryReference.java")
  }

  fun testCreateTestDirectory() {
    assertIntentionCreatesDir("testdir", "/test/java/long/testdir", "/test/java/pkg/TestClassWithDirectoryReference.java")
  }

  private fun assertIntentionCreatesDir(expectedDirName: String, expectedDirPath: String, javaSourcePath: String) {
    myFixture.configureFromTempProjectFile(javaSourcePath)
    myFixture.testHighlighting(true, false, true)

    withFileReferenceInStringLiteral {
      val ref = myFixture.getReferenceAtCaretPosition()
      val fileReference = findFileReference(ref)

      assertEquals(expectedDirName, fileReference.fileNameToCreate)
      val intention = fileReference.quickFixes!![0]

      myFixture.launchAction(intention as IntentionAction)

      val notFoundDirectory = myFixture.findFileInTempDir(expectedDirPath)
      assertTrue(notFoundDirectory.exists())
    }
  }
}
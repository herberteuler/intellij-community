// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.testframework.sm.runner;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.DefaultLogger;
import com.intellij.testFramework.TestLoggerKt;
import com.intellij.testFramework.junit5.TestDisposable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Roman Chernyatchik
 */
public class TestSuiteStackTest extends BaseSMTRunnerTestCase {
  private TestSuiteStack myTestSuiteStack;

  @BeforeEach
  void setUp() {
    myTestSuiteStack = new TestSuiteStack("from tests");
  }

  @AfterEach
  void tearDown() {
    disableDebugMode();
  }

  @Test
  public void testPushSuite() {
    myTestSuiteStack.pushSuite(mySuite);
    assertEquals(1, myTestSuiteStack.getStackSize());
    assertEquals(mySuite, myTestSuiteStack.getCurrentSuite());

    myTestSuiteStack.pushSuite(mySuite);
    assertEquals(2, myTestSuiteStack.getStackSize());
    assertEquals(mySuite, myTestSuiteStack.getCurrentSuite());

    final SMTestProxy newSuite = createSuiteProxy();
    myTestSuiteStack.pushSuite(newSuite);
    assertEquals(3, myTestSuiteStack.getStackSize());
    assertEquals(newSuite, myTestSuiteStack.getCurrentSuite());
  }

  @Test
  public void testGetStackSize() {
    assertEquals(0, myTestSuiteStack.getStackSize());

    myTestSuiteStack.pushSuite(mySuite);
    assertEquals(1, myTestSuiteStack.getStackSize());

    myTestSuiteStack.popSuite(mySuite.getName());
    assertEquals(0, myTestSuiteStack.getStackSize());
  }

  @Test
  public void testGetCurrentSuite() {
    assertNull(myTestSuiteStack.getCurrentSuite());

    myTestSuiteStack.pushSuite(mySuite);
    assertEquals(mySuite, myTestSuiteStack.getCurrentSuite());
  }

  @Test
  public void testPopEmptySuite_DebugMode(@TestDisposable Disposable disposable) throws Throwable {
    DefaultLogger.disableStderrDumping(disposable);

    enableDebugMode();

    TestLoggerKt.rethrowLoggedErrorsIn(() -> {
      assertThrows(Throwable.class, () -> myTestSuiteStack.popSuite("some suite"));
    });
  }

  @Test
  public void testPopEmptySuite_NormalMode() {
    assertNull(myTestSuiteStack.popSuite("some suite"));
  }

  @Test
  public void testPopInconsistentSuite_DebugMode(@TestDisposable Disposable disposable) throws Throwable {
    DefaultLogger.disableStderrDumping(disposable);
    TestLoggerKt.rethrowLoggedErrorsIn(() -> {
      enableDebugMode();

      final String suiteName = mySuite.getName();

      myTestSuiteStack.pushSuite(createSuiteProxy("0"));
      myTestSuiteStack.pushSuite(mySuite);
      myTestSuiteStack.pushSuite(createSuiteProxy("2"));
      myTestSuiteStack.pushSuite(createSuiteProxy("3"));

      assertEquals(4, myTestSuiteStack.getStackSize());
      assertEquals("3", myTestSuiteStack.getCurrentSuite().getName());

      assertThrows(Throwable.class, () -> myTestSuiteStack.popSuite(suiteName));
      assertEquals(4, myTestSuiteStack.getStackSize());
    });
  }

  @Test
  public void testPopInconsistentSuite_NormalMode() {
    final String suiteName = mySuite.getName();

    myTestSuiteStack.pushSuite(createSuiteProxy("0"));
    myTestSuiteStack.pushSuite(mySuite);
    myTestSuiteStack.pushSuite(createSuiteProxy("2"));
    myTestSuiteStack.pushSuite(createSuiteProxy("3"));

    assertEquals(4, myTestSuiteStack.getStackSize());
    assertEquals("3", myTestSuiteStack.getCurrentSuite().getName());


    assertNotNull(myTestSuiteStack.popSuite(suiteName));
    assertEquals(1, myTestSuiteStack.getStackSize());
  }

  @Test
  public void testPopSuite() {
    final String suiteName = mySuite.getName();

    myTestSuiteStack.pushSuite(mySuite);
    assertEquals(mySuite, myTestSuiteStack.popSuite(suiteName));
    assertEquals(0, myTestSuiteStack.getStackSize());
  }

  @Test
  public void testGetSuitePath() {
    assertThat(myTestSuiteStack.getSuitePath()).isEmpty();

    myTestSuiteStack.pushSuite(createSuiteProxy("1"));
    myTestSuiteStack.pushSuite(createSuiteProxy("2"));
    myTestSuiteStack.pushSuite(createSuiteProxy("3"));

    assertThat(myTestSuiteStack.getSuitePath()).containsExactlyInAnyOrder("1", "2", "3");
  }

  @Test
  public void testGetSuitePathPresentation() {
    assertEquals("empty", myTestSuiteStack.getSuitePathPresentation());

    myTestSuiteStack.pushSuite(createSuiteProxy("1"));
    myTestSuiteStack.pushSuite(createSuiteProxy("2"));
    myTestSuiteStack.pushSuite(createSuiteProxy("3"));

    assertEquals("[1]->[2]->[3]", myTestSuiteStack.getSuitePathPresentation());    
  }

  @Test
  public void testClear() {
    myTestSuiteStack.pushSuite(createSuiteProxy("1"));
    myTestSuiteStack.pushSuite(createSuiteProxy("2"));
    myTestSuiteStack.pushSuite(createSuiteProxy("3"));
    myTestSuiteStack.clear();

    assertEquals(0, myTestSuiteStack.getStackSize());
  }

  @Test
  public void testIsEmpty() {
    assertTrue(myTestSuiteStack.isEmpty());

    myTestSuiteStack.pushSuite(createSuiteProxy("1"));
    assertFalse(myTestSuiteStack.isEmpty());

    myTestSuiteStack.popSuite("1");
    assertTrue(myTestSuiteStack.isEmpty());

    myTestSuiteStack.pushSuite(createSuiteProxy("1"));
    myTestSuiteStack.pushSuite(createSuiteProxy("2"));
    myTestSuiteStack.clear();
    assertTrue(myTestSuiteStack.isEmpty());    
  }

  private static void enableDebugMode() {
    // enable debug mode
    System.setProperty("idea.smrunner.debug", "true");
  }
  private static void disableDebugMode() {
    // enable debug mode
    System.setProperty("idea.smrunner.debug", "false");
  }
}

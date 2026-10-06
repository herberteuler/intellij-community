// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.testframework.sm.runner;

import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.testframework.CompositePrintable;
import com.intellij.execution.testframework.Printable;
import com.intellij.execution.testframework.Printer;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestIgnoredEvent;
import com.intellij.execution.testframework.sm.runner.events.TestOutputEvent;
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestSuiteFinishedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestSuiteStartedEvent;
import com.intellij.execution.testframework.sm.runner.ui.MockPrinter;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm;
import com.intellij.execution.testframework.ui.TestsOutputConsolePrinter;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.util.Disposer;
import com.intellij.util.concurrency.Semaphore;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Roman Chernyatchik
 */
public class SMTRunnerConsoleTest extends BaseSMTRunnerTestCase {
  private MyConsoleView myConsole;
  private GeneralToSMTRunnerEventsConvertor myEventsProcessor;
  private MockPrinter myMockResettablePrinter;
  private SMTestProxy.SMRootTestProxy myRootSuite;
  private SMTestRunnerResultsForm myResultsViewer;

  private final class MyConsoleView extends SMTRunnerConsoleView {
    private final TestsOutputConsolePrinter myTestsOutputConsolePrinter;

    private MyConsoleView(final TestConsoleProperties consoleProperties) {
      super(consoleProperties);

      myTestsOutputConsolePrinter = new TestsOutputConsolePrinter(this, consoleProperties, null) {
        @Override
        public void print(final @NotNull String text, final @NotNull ConsoleViewContentType contentType) {
          myMockResettablePrinter.print(text, contentType);
        }
      };
    }

    @Override
    public TestsOutputConsolePrinter getPrinter() {
      return myTestsOutputConsolePrinter;
    }
  }

  @BeforeEach
  void setUp() {
    runInEdtAndWait(() -> {
      final TestConsoleProperties consoleProperties = createConsoleProperties();

      myMockResettablePrinter = new MockPrinter();
      myConsole = new MyConsoleView(consoleProperties);
      myConsole.initUI();
      myResultsViewer = myConsole.getResultsViewer();
      myRootSuite = myResultsViewer.getTestsRootNode();
      myEventsProcessor = new GeneralToSMTRunnerEventsConvertor(consoleProperties.getProject(), myResultsViewer.getTestsRootNode(), "SMTestFramework");

      myEventsProcessor.onStartTesting();
    });
  }

  @AfterEach
  void tearDown() {
    runInEdtAndWait(() -> {
      Disposer.dispose(myEventsProcessor);
      Disposer.dispose(myConsole);
    });
  }

  @Test
  public void testPrintTestProxy() {
    runInEdtAndWait(() -> {
      mySimpleTest.setPrinter(myMockResettablePrinter);
      mySimpleTest.addLast(new Printable() {
        @Override
        public void printOn(final Printer printer) {
          printer.print("std out", ConsoleViewContentType.NORMAL_OUTPUT);
          printer.print("std err", ConsoleViewContentType.ERROR_OUTPUT);
          printer.print("std sys", ConsoleViewContentType.SYSTEM_OUTPUT);
        }
      });
      assertAllOutputs(myMockResettablePrinter, "std out", "std err", "std sys");
    });
  }

  @Test
  public void testAddStdOut() {
    runInEdtAndWait(() -> {
      mySimpleTest.setPrinter(myMockResettablePrinter);

      mySimpleTest.addStdOutput("one");
      assertStdOutput(myMockResettablePrinter, "one");

      mySimpleTest.addStdErr("two");
      assertStdErr(myMockResettablePrinter, "two");

      mySimpleTest.addStdOutput("one");
      mySimpleTest.addStdOutput("one");
      mySimpleTest.addStdErr("two");
      mySimpleTest.addStdErr("two");
      assertAllOutputs(myMockResettablePrinter, "oneone", "twotwo", "");
    });
  }

  @Test
  public void testAddStdSys() {
    runInEdtAndWait(() -> {
      mySimpleTest.setPrinter(myMockResettablePrinter);

      mySimpleTest.addSystemOutput("sys");
      assertAllOutputs(myMockResettablePrinter, "", "", "sys");
    });
  }

  @Test
  public void testPrintTestProxy_Order() {
    runInEdtAndWait(() -> {
      mySimpleTest.setPrinter(myMockResettablePrinter);

      sendToTestProxyStdOut(mySimpleTest, "first ");
      sendToTestProxyStdOut(mySimpleTest, "second");

      assertStdOutput(myMockResettablePrinter, "first second");
    });
  }

  @Test
  public void testSetPrintListener_ForExistingChildren() {
    runInEdtAndWait(() -> {
      mySuite.addChild(mySimpleTest);

      mySuite.setPrinter(myMockResettablePrinter);

      sendToTestProxyStdOut(mySimpleTest, "child ");
      sendToTestProxyStdOut(mySuite, "root");

      assertStdOutput(myMockResettablePrinter, "child root");
    });
  }

  @Test
  public void testSetPrintListener_OnNewChild() {
    runInEdtAndWait(() -> {
      mySuite.setPrinter(myMockResettablePrinter);

      sendToTestProxyStdOut(mySuite, "root ");

      sendToTestProxyStdOut(mySimpleTest, "[child old msg] ");
      mySuite.addChild(mySimpleTest);

      sendToTestProxyStdOut(mySuite, "{child added} ");
      sendToTestProxyStdOut(mySimpleTest, "[child new msg]");
      // printer for parent have been already set, thus new
      // child should immediately print himself on this printer
      assertStdOutput(myMockResettablePrinter, "root [child old msg] {child added} [child new msg]");
    });
  }

  @Test
  public void testDeferredPrint() {
    runInEdtAndWait(() -> {
      sendToTestProxyStdOut(mySimpleTest, "one ");
      sendToTestProxyStdOut(mySimpleTest, "two ");
      sendToTestProxyStdOut(mySimpleTest, "three");

      myMockResettablePrinter.onNewAvailable(mySimpleTest);
      assertStdOutput(myMockResettablePrinter, "one two three");

      myMockResettablePrinter.resetIfNecessary();
      assertFalse(myMockResettablePrinter.hasPrinted());

      myMockResettablePrinter.onNewAvailable(mySimpleTest);
      assertStdOutput(myMockResettablePrinter, "one two three");
    });
  }

  @Test
  public void testProcessor_OnTestStdOutput() {
    runInEdtAndWait(() -> {
      startTestWithPrinter("my_test");

      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout2", true));

      assertStdOutput(myMockResettablePrinter, "stdout1 stdout2");
    });
  }

  @Test
  public void testProcessor_OnTestStdErr() {
    runInEdtAndWait(() -> {
      startTestWithPrinter("my_test");

      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr2", false));

      assertStdErr(myMockResettablePrinter, "stderr1 stderr2");
    });
  }

  @Test
  public void testProcessor_OnTestMixedStd() {
    runInEdtAndWait(() -> {
      startTestWithPrinter("my_test");

      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout2", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr2", false));

      assertAllOutputs(myMockResettablePrinter, "stdout1 stdout2", "stderr1 stderr2", "");
    });
  }

  @Test
  public void testProcessor_OnFailure() {
    runInEdtAndWait(() -> {
      final SMTestProxy myTest1 = startTestWithPrinter("my_test");

      myEventsProcessor.onTestFailure(new TestFailedEvent("my_test", "error msg", "method1:1\nmethod2:2", false, null, null));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "\nerror msg\nmethod1:1\nmethod2:2\nstderr1 ", "");

      final MockPrinter mockPrinter1 = new MockPrinter();
      mockPrinter1.onNewAvailable(myTest1);
      assertAllOutputs(mockPrinter1, "stdout1 ", "stderr1 \nerror msg\nmethod1:1\nmethod2:2\n", "");

      //other output order
      final SMTestProxy myTest2 = startTestWithPrinter("my_test2");
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stderr1 ", false));
      myEventsProcessor.onTestFailure(new TestFailedEvent("my_test2", "error msg", "method1:1\nmethod2:2", false, null, null));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "stderr1 \nerror msg\nmethod1:1\nmethod2:2\n", "");
      final MockPrinter mockPrinter2 = new MockPrinter();
      mockPrinter2.onNewAvailable(myTest2);
      assertAllOutputs(mockPrinter2, "stdout1 ", "stderr1 \nerror msg\nmethod1:1\nmethod2:2\n", "");
    });
  }

  @Test
  public void testProcessor_OnFailure_EmptyStacktrace() {
    runInEdtAndWait(() -> {
      final SMTestProxy myTest1 = startTestWithPrinter("my_test");

      myEventsProcessor.onTestFailure(new TestFailedEvent("my_test", "error msg", "\n\n", false, null, null));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "\nerror msg\nstderr1 ", "");

      final MockPrinter mockPrinter1 = new MockPrinter();
      mockPrinter1.onNewAvailable(myTest1);
      assertAllOutputs(mockPrinter1, "stdout1 ", "stderr1 \nerror msg\n", "");
    });
  }

  @Test
  public void testProcessor_OnFailure_Comparision_Strings() {
    runInEdtAndWait(() -> {
      final SMTestProxy myTest1 = startTestWithPrinter("my_test");

      myEventsProcessor.onTestFailure(new TestFailedEvent("my_test", "error msg", "method1:1\nmethod2:2", false, "actual", "expected"));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));

      assertAllOutputs(myMockResettablePrinter,
                       // std out
                       "stdout1 ",
                       // std err
                       """

                         error msg
                         expected
                         actual


                         method1:1
                         method2:2
                         stderr1\s""",
                       // std sys
                       "Expected :Actual   :");

      final MockPrinter mockPrinter1 = new MockPrinter();
      mockPrinter1.onNewAvailable(myTest1);
      assertAllOutputs(mockPrinter1,
                       // std out
                       "stdout1 ",
                       // std err
                       """
                         stderr1\s
                         error msg
                         expected
                         actual


                         method1:1
                         method2:2
                         """,
                       // std sys
                       "Expected :Actual   :");
    });
  }

  @Test
  public void testProcessor_OnFailure_Comparision_MultilineTexts() {
    runInEdtAndWait(() -> {
      final SMTestProxy myTest1 = startTestWithPrinter("my_test");

      myEventsProcessor.onTestFailure(new TestFailedEvent("my_test", "error msg", "method1:1\nmethod2:2", false,
                                      "this is:\nactual", "this is:\nexpected"));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", """

        error msg


        method1:1
        method2:2
        stderr1\s""", "");

      final MockPrinter mockPrinter1 = new MockPrinter();
      mockPrinter1.onNewAvailable(myTest1);
      assertAllOutputs(mockPrinter1, "stdout1 ", """
        stderr1\s
        error msg


        method1:1
        method2:2
        """, "");
    });
  }

  @Test
  public void testProcessor_OnError() {
    runInEdtAndWait(() -> {
      final SMTestProxy myTest1 = startTestWithPrinter("my_test");

      myEventsProcessor.onTestFailure(new TestFailedEvent("my_test", "error msg", "method1:1\nmethod2:2", true, null, null));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "\nerror msg\nmethod1:1\nmethod2:2\nstderr1 ", "");

      final MockPrinter mockPrinter1 = new MockPrinter();
      mockPrinter1.onNewAvailable(myTest1);
      assertAllOutputs(mockPrinter1, "stdout1 ", "stderr1 \nerror msg\nmethod1:1\nmethod2:2\n", "");

      //other output order
      final SMTestProxy myTest2 = startTestWithPrinter("my_test2");
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stderr1 ", false));
      myEventsProcessor.onTestFailure(new TestFailedEvent("my_test2", "error msg", "method1:1\nmethod2:2", true, null, null));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "stderr1 \nerror msg\nmethod1:1\nmethod2:2\n", "");
      final MockPrinter mockPrinter2 = new MockPrinter();
      mockPrinter2.onNewAvailable(myTest2);
      assertAllOutputs(mockPrinter2, "stdout1 ", "stderr1 \nerror msg\nmethod1:1\nmethod2:2\n", "");
    });
  }

  @Test
  public void testProcessor_OnErrorMsg() {
    runInEdtAndWait(() -> {
      final SMTestProxy myTest1 = startTestWithPrinter("my_test");

      myEventsProcessor.onError("error msg", "method1:1\nmethod2:2", true);
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "\nerror msg\nmethod1:1\nmethod2:2\nstderr1 ", "");

      final MockPrinter mockPrinter1 = new MockPrinter();
      mockPrinter1.onNewAvailable(myTest1);
      assertAllOutputs(mockPrinter1, "stdout1 ", """

        error msg
        method1:1
        method2:2
        stderr1\s""", "");
      myEventsProcessor.onTestFinished(new TestFinishedEvent("my_test", 1L));
      myTest1.setFinished();

      //other output order
      final SMTestProxy myTest2 = startTestWithPrinter("my_test2");
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stderr1 ", false));
      myEventsProcessor.onError("error msg", "method1:1\nmethod2:2", true);

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "stderr1 \nerror msg\nmethod1:1\nmethod2:2\n", "");
      final MockPrinter mockPrinter2 = new MockPrinter();
      mockPrinter2.onNewAvailable(myTest2);
      assertAllOutputs(mockPrinter2, "stdout1 ", "stderr1 \nerror msg\nmethod1:1\nmethod2:2\n", "");
    });
  }

  @Test
  public void testProcessor_Suite_OnErrorMsg() {
    runInEdtAndWait(() -> {
      myEventsProcessor.onError("error msg:root", "method1:1\nmethod2:2", true);

      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite", null));
      final SMTestProxy suite = myEventsProcessor.getCurrentSuite();
      suite.setPrinter(myMockResettablePrinter);
      myEventsProcessor.onError("error msg:suite", "method1:1\nmethod2:2", true);

      assertAllOutputs(myMockResettablePrinter, "", """

        error msg:suite
        method1:1
        method2:2
        """, "");

      final MockPrinter mockSuitePrinter = new MockPrinter();
      mockSuitePrinter.onNewAvailable(suite);
      assertAllOutputs(mockSuitePrinter, "", """

        error msg:suite
        method1:1
        method2:2
        """, "");
      final MockPrinter mockRootSuitePrinter = new MockPrinter();
      mockRootSuitePrinter.onNewAvailable(myRootSuite);
      assertAllOutputs(mockRootSuitePrinter, "", """

        error msg:root
        method1:1
        method2:2

        error msg:suite
        method1:1
        method2:2
        """, "");
    });
  }

  @Test
  public void testProcessor_OnIgnored() {
    runInEdtAndWait(() -> {
      final SMTestProxy myTest1 = startTestWithPrinter("my_test");

      myEventsProcessor.onTestIgnored(new TestIgnoredEvent("my_test", "ignored msg", null));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "stderr1 ", "\nignored msg\n");

      final MockPrinter mockPrinter1 = new MockPrinter();
      mockPrinter1.onNewAvailable(myTest1);
      assertAllOutputs(mockPrinter1, "stdout1 ", "stderr1 ", "\nignored msg\n");

      //other output order
      final SMTestProxy myTest2 = startTestWithPrinter("my_test2");
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stderr1 ", false));
      myEventsProcessor.onTestIgnored(new TestIgnoredEvent("my_test2", "ignored msg", null));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ", "stderr1 ", "\nignored msg\n");
      final MockPrinter mockPrinter2 = new MockPrinter();
      mockPrinter2.onNewAvailable(myTest2);
      assertAllOutputs(mockPrinter2, "stdout1 ", "stderr1 ", "\nignored msg\n");
    });
  }

  @Test
  public void testProcessor_OnIgnored_WithStacktrace() {
    runInEdtAndWait(() -> {
      final SMTestProxy myTest1 = startTestWithPrinter("my_test");

      myEventsProcessor.onTestIgnored(new TestIgnoredEvent("my_test", "ignored2 msg", "method1:1\nmethod2:2"));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test", "stderr1 ", false));

      assertAllOutputs(myMockResettablePrinter, "stdout1 ",
                       "stderr1 ",
                       "\nignored2 msg\n\nmethod1:1\nmethod2:2\n");

      final MockPrinter mockPrinter1 = new MockPrinter();
      mockPrinter1.onNewAvailable(myTest1);
      assertAllOutputs(mockPrinter1,
                       "stdout1 ",
                       "stderr1 ",
                       "\nignored2 msg\n\nmethod1:1\nmethod2:2\n");

      //other output order
      final SMTestProxy myTest2 = startTestWithPrinter("my_test2");
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stdout1 ", true));
      myEventsProcessor.onTestOutput(new TestOutputEvent("my_test2", "stderr1 ", false));
      myEventsProcessor.onTestIgnored(new TestIgnoredEvent("my_test2", "ignored msg", "method1:1\nmethod2:2"));

      assertAllOutputs(myMockResettablePrinter,
                       "stdout1 ",
                       "stderr1 ",
                       "\nignored msg\n\nmethod1:1\nmethod2:2\n");
      final MockPrinter mockPrinter2 = new MockPrinter();
      mockPrinter2.onNewAvailable(myTest2);
      assertAllOutputs(mockPrinter2,
                       "stdout1 ",
                       "stderr1 ",
                       "\nignored msg\n\nmethod1:1\nmethod2:2\n");
    });
  }

  @Test
  public void testOnUncapturedOutput_BeforeProcessStarted() {
    runInEdtAndWait(() -> {
      myRootSuite.setPrinter(myMockResettablePrinter);

      assertOnUncapturedOutput();
    });
  }

  @Test
  public void testOnUncapturedOutput_BeforeFirstSuiteStarted() {
    runInEdtAndWait(() -> {
      myRootSuite.setPrinter(myMockResettablePrinter);

      myEventsProcessor.onStartTesting();
      assertOnUncapturedOutput();
    });
  }

  @Test
  public void testOnUncapturedOutput_SomeSuite() {
    runInEdtAndWait(() -> {
      myEventsProcessor.onStartTesting();

      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("my suite", null));
      final SMTestProxy mySuite = myEventsProcessor.getCurrentSuite();
      assertNotSame(mySuite, myRootSuite);
      mySuite.setPrinter(myMockResettablePrinter);

      assertOnUncapturedOutput();
    });
  }

  @Test
  public void testOnUncapturedOutput_SomeTest() {
    runInEdtAndWait(() -> {
      myEventsProcessor.onStartTesting();

      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("my suite", null));
      startTestWithPrinter("my test");

      assertOnUncapturedOutput();
    });
  }


  public void assertOnUncapturedOutput() {
    myEventsProcessor.onUncapturedOutput("stdout", ProcessOutputTypes.STDOUT);
    myEventsProcessor.onUncapturedOutput("stderr", ProcessOutputTypes.STDERR);
    myEventsProcessor.onUncapturedOutput("system", ProcessOutputTypes.SYSTEM);

    assertAllOutputs(myMockResettablePrinter, "stdout", "stderr", "system");
  }

  public static void assertStdOutput(final MockPrinter printer, final String out) {
    assertAllOutputs(printer, out, "", "");
  }

  public static void assertStdErr(final MockPrinter printer, final String out) {
    assertAllOutputs(printer, "", out, "");
  }

  public static void assertAllOutputs(final MockPrinter printer,
                                      final String out, final String err, final String sys)
  {
    assertTrue(printer.hasPrinted());
    assertEquals(out, printer.getStdOut());
    assertEquals(err, printer.getStdErr());
    assertEquals(sys, printer.getStdSys());

    printer.resetIfNecessary();
  }

  @Test
  public void testStopCollectingOutput() {
    runInEdtAndWait(() -> {
      myResultsViewer.selectAndNotify(myResultsViewer.getTestsRootNode());

      myEventsProcessor.onStartTesting();
      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite", null));
      final SMTestProxy suite = myEventsProcessor.getCurrentSuite();
      myEventsProcessor.onSuiteFinished(new TestSuiteFinishedEvent("suite"));
      myEventsProcessor.onUncapturedOutput("preved", ProcessOutputTypes.STDOUT);
      myEventsProcessor.onFinishTesting();

      //myResultsViewer.selectAndNotify(suite);
      //the string above doesn't update tree immediately so we should simulate update
      myConsole.getPrinter().updateOnTestSelected(suite);

      //Lets reset printer /clear console/ before selection changed to
      //get after selection event only actual ouptut
      myMockResettablePrinter.resetIfNecessary();

      //myResultsViewer.selectAndNotify(myResultsViewer.getTestsRootNode());
      //the string above doesn't update tree immediately so we should simulate update
      myConsole.getPrinter().updateOnTestSelected(myResultsViewer.getTestsRootNode());

      assertAllOutputs(myMockResettablePrinter, "preved", "","");
    });
  }

  @Test
  public void testPrintingOnlyOwnContentForRoot() {
    runInEdtAndWait(() -> {
      myRootSuite.setShouldPrintOwnContentOnly(true);

      myConsole.getPrinter().updateOnTestSelected(myResultsViewer.getTestsRootNode());

      myEventsProcessor.onStartTesting();
      myEventsProcessor.onUncapturedOutput("root output 1\n", ProcessOutputTypes.STDOUT);
      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite", null));
      SMTestProxy suite = myEventsProcessor.getCurrentSuite();
      myEventsProcessor.onUncapturedOutput("suite output\n", ProcessOutputTypes.STDOUT);
      myEventsProcessor.onTestStarted(new TestStartedEvent("my test", null));
      myEventsProcessor.onUncapturedOutput("test output\n", ProcessOutputTypes.STDOUT);
      myEventsProcessor.onTestFinished(new TestFinishedEvent("my test", null));
      myEventsProcessor.onSuiteFinished(new TestSuiteFinishedEvent("suite"));
      myEventsProcessor.onUncapturedOutput("root output 2\n", ProcessOutputTypes.STDOUT);
      myEventsProcessor.onFinishTesting();

      assertAllOutputs(myMockResettablePrinter, """
        root output 1
        root output 2
        """, "", "");

      myMockResettablePrinter.resetIfNecessary();
      myConsole.getPrinter().updateOnTestSelected(suite);
      assertAllOutputs(myMockResettablePrinter, """
        suite output
        test output
        """, "", "");

      myMockResettablePrinter.resetIfNecessary();
      myConsole.getPrinter().updateOnTestSelected(myResultsViewer.getTestsRootNode());
      assertAllOutputs(myMockResettablePrinter, """
        root output 1
        root output 2
        """, "", "");

      myRootSuite.setShouldPrintOwnContentOnly(false);

      myMockResettablePrinter.resetIfNecessary();
      myConsole.getPrinter().updateOnTestSelected(suite);
      assertAllOutputs(myMockResettablePrinter, """
        suite output
        test output
        """, "", "");

      myMockResettablePrinter.resetIfNecessary();
      myConsole.getPrinter().updateOnTestSelected(myResultsViewer.getTestsRootNode());
      assertAllOutputs(myMockResettablePrinter, """
        root output 1
        suite output
        test output
        root output 2
        """, "", "");
    });
  }

  @Test
  public void testPrintingManyOutputForRootWithoutChildren() {
    runInEdtAndWait(() -> {
      myRootSuite.setShouldPrintOwnContentOnly(true);

      myConsole.getPrinter().updateOnTestSelected(myResultsViewer.getTestsRootNode());

      myEventsProcessor.onStartTesting();
      StringBuilder expectedOutput = new StringBuilder();
      for (int i = 0; i < 10000; i++) {
        String text = "root output " + i + "\n";
        myEventsProcessor.onUncapturedOutput(text, ProcessOutputTypes.STDOUT);
        expectedOutput.append(text);
      }
      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite", null));
      SMTestProxy suite = myEventsProcessor.getCurrentSuite();
      myEventsProcessor.onUncapturedOutput("suite output\n", ProcessOutputTypes.STDOUT);
      myEventsProcessor.onFinishTesting();

      assertAllOutputs(myMockResettablePrinter, expectedOutput.toString(), "", "");

      myMockResettablePrinter.resetIfNecessary();
      myConsole.getPrinter().updateOnTestSelected(suite);
      assertAllOutputs(myMockResettablePrinter, "suite output\n", "", "");

      myMockResettablePrinter.resetIfNecessary();
      myConsole.getPrinter().updateOnTestSelected(myResultsViewer.getTestsRootNode());
      assertAllOutputs(myMockResettablePrinter, expectedOutput.toString(), "", "");
    });
  }

  @Test
  public void testEnsureOrderedClearFlush() {
    runInEdtAndWait(() -> {
      StringBuffer buf = new StringBuffer();
      StringBuilder expected = new StringBuilder();
      for(int i = 0; i < 100; i++) {
        expected.append("1");
        expected.append("2");
        CompositePrintable.invokeInAlarm(() -> buf.append("1"), false);
        CompositePrintable.invokeInAlarm(() -> buf.append("2"), false);
      }
      Semaphore s = new Semaphore();
      s.down();
      CompositePrintable.invokeInAlarm(s::up, false);
      assertTrue(s.waitFor(1000));
      assertEquals(expected.toString(), buf.toString());
    });
  }

  @NotNull
  private SMTestProxy startTestWithPrinter(final String testName) {
    myEventsProcessor.onTestStarted(new TestStartedEvent(testName, null));
    final SMTestProxy proxy =
        myEventsProcessor.getProxyByFullTestName(myEventsProcessor.getFullTestName(testName));
    assertNotNull(proxy);
    proxy.setPrinter(myMockResettablePrinter);
    return proxy;
  }

  private static void sendToTestProxyStdOut(final SMTestProxy proxy, final String text) {
    proxy.addLast(new Printable() {
      @Override
      public void printOn(final Printer printer) {
        printer.print(text, ConsoleViewContentType.NORMAL_OUTPUT);
      }
    });
  }
}

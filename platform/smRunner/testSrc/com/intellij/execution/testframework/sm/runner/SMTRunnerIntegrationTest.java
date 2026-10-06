// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.testframework.sm.runner;

import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.NopProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.testframework.sm.SMTestRunnerConnectionUtil;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.junit5.TestApplication;
import com.intellij.testFramework.junit5.fixture.TestFixture;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static com.intellij.testFramework.junit5.fixture.FixturesKt.projectFixture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@TestApplication
public class SMTRunnerIntegrationTest {

  private static final String TEST_FRAMEWORK_NAME = "SMRunnerTests";
  private static final TestFixture<Project> projectFixture = projectFixture();

  private SMTRunnerConsoleView myConsole;
  private SMTestRunnerResultsForm myResultsViewer;
  private SMTestProxy.SMRootTestProxy myRootNode;
  private ProcessHandler myProcessHandler;

  @BeforeEach
  void setUp() {
    runInEdtAndWait(() -> createConsole(true));
  }

  private void createConsole(boolean idBasedTestTree) {
    SMTRunnerConsoleProperties properties = new SMTRunnerConsoleProperties(new MockRuntimeConfiguration(projectFixture.get()),
                                                                           TEST_FRAMEWORK_NAME,
                                                                           DefaultRunExecutor.getRunExecutorInstance());
    properties.setIdBasedTestTree(idBasedTestTree);
    myConsole = (SMTRunnerConsoleView)SMTestRunnerConnectionUtil.createConsole(TEST_FRAMEWORK_NAME, properties);
    myResultsViewer = myConsole.getResultsViewer();
    myRootNode = myResultsViewer.getTestsRootNode();
    myProcessHandler = new NopProcessHandler();
    myConsole.attachToProcess(myProcessHandler);
    myProcessHandler.startNotify();
  }

  @AfterEach
  void tearDown() {
    runInEdtAndWait(() -> {
      myProcessHandler.destroyProcess();
      Disposer.dispose(myConsole);
    });
  }

  @Test
  public void testMultiTestings() {
    runInEdtAndWait(() -> {
      notifyStdoutLineAvailable("##teamcity[enteredTheMatrix]");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");
      assertState(0, 0, 0, "passed");
      notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='1' parentNodeId='0' name='sum-test.js']");
      assertState(0, 0, 1, "passed");
      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='2' parentNodeId='1' name='x']");
      notifyStdoutLineAvailable("##teamcity[testFinished nodeId='2' duration='2']");
      assertState(1, 0, 1, "passed");
      notifyStdoutLineAvailable("##teamcity[testSuiteFinished nodeId='1']");
      notifyStdoutLineAvailable("##teamcity[testingFinished]");
      assertState(1, 0, 1, "passed");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");
      assertState(0, 0, 0, "passed");
    });
  }

  @Test
  public void testFailedThenSuccessTestings() {
    runInEdtAndWait(() -> {
      notifyStdoutLineAvailable("##teamcity[enteredTheMatrix]");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");
      notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='1' parentNodeId='0' name='sum-test.js']");
      assertState(0, 0, 1, "passed");
      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='2' parentNodeId='1' name='x']");
      assertState(0, 0, 1, "passed");
      notifyStdoutLineAvailable("##teamcity[testFailed nodeId='2' message='Failed' duration='2']");
      assertState(1, 1, 1, "failed");
      notifyStdoutLineAvailable("##teamcity[testSuiteFinished nodeId='1']");
      notifyStdoutLineAvailable("##teamcity[testingFinished]");
      assertState(1, 1, 1, "failed");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");
      assertState(0, 0, 0, "passed");

      notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='1' parentNodeId='0' name='sum-test.js']");
      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='2' parentNodeId='1' name='x']");
      notifyStdoutLineAvailable("##teamcity[testFinished nodeId='2' duration='2']");
      notifyStdoutLineAvailable("##teamcity[testSuiteFinished nodeId='1']");
      notifyStdoutLineAvailable("##teamcity[testingFinished]");
      assertState(1, 0, 1, "passed");
    });
  }

  @Test
  public void testSuitePassedFailedIgnored() {
    runInEdtAndWait(() -> {
      notifyStdoutLineAvailable("##teamcity[enteredTheMatrix]");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");

      int finishedTests = 0;
      int failedTests = 0;
      int ignoredTests = 0;
      int topLevelChildrenCount = 0;
      {
        notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='1' parentNodeId='0' name='test1']");
        topLevelChildrenCount++;
        assertState(finishedTests, failedTests, ignoredTests, topLevelChildrenCount, "passed");
        notifyStdoutLineAvailable("##teamcity[testFinished nodeId='1']");
        assertState(finishedTests, failedTests, ignoredTests, topLevelChildrenCount, "passed");
      }
      {
        notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='2' parentNodeId='0' name='test2']");
        topLevelChildrenCount++;
        assertState(finishedTests, failedTests, ignoredTests, topLevelChildrenCount, "passed");
        notifyStdoutLineAvailable("##teamcity[testFailed nodeId='2' message='failed']");
        failedTests++;
        assertState(finishedTests, failedTests, ignoredTests, topLevelChildrenCount, "failed");
      }
      {
        notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='3' parentNodeId='0' name='test3']");
        topLevelChildrenCount++;
        assertState(finishedTests, failedTests, ignoredTests, topLevelChildrenCount, "failed");
        notifyStdoutLineAvailable("##teamcity[testIgnored nodeId='3']");
        assertState(finishedTests, failedTests, ignoredTests, topLevelChildrenCount, "failed");
      }

      notifyStdoutLineAvailable("##teamcity[testingFinished]");
    });
  }

  @Test
  public void testIgnoredTestings() {
    runInEdtAndWait(() -> {
      notifyStdoutLineAvailable("##teamcity[enteredTheMatrix]");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");

      int topLevelChildrenCount = 0;
      int ignoredTests = 0;
      {
        // empty suite1 with ignored state
        notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='1' parentNodeId='0' name='suite1']");
        ++topLevelChildrenCount;
        assertState(0, 0, ignoredTests, topLevelChildrenCount, "passed");
        notifyStdoutLineAvailable("##teamcity[testIgnored nodeId='1' name='suite1']");
        assertState(0, 0, ignoredTests, topLevelChildrenCount, "passed");
      }
      {
        // non-empty suite2 with ignored test21
        notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='2' parentNodeId='0' name='suite2']");
        ++topLevelChildrenCount;
        assertState(0, 0, ignoredTests, topLevelChildrenCount, "passed");
        {
          notifyStdoutLineAvailable("##teamcity[testStarted nodeId='21' parentNodeId='2' name='test21']");
          assertState(0, 0, ignoredTests, topLevelChildrenCount, "passed");
          notifyStdoutLineAvailable("##teamcity[testIgnored nodeId='21' name='test21']");
          ++ignoredTests;
          assertState(ignoredTests, 0, ignoredTests, topLevelChildrenCount, "passed");
        }
        notifyStdoutLineAvailable("##teamcity[testSuiteFinished nodeId='2']");
        assertState(ignoredTests, 0, ignoredTests, topLevelChildrenCount, "passed");
      }

      notifyStdoutLineAvailable("##teamcity[testingFinished]");
    });
  }

  @Test
  public void testSuiteFinishedWithWallTimeDurationWithoutDurationStrategy() {
    runInEdtAndWait(() -> {
      notifyStdoutLineAvailable("##teamcity[enteredTheMatrix]");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");
      notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='1' parentNodeId='0' name='suite']");

      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='2' parentNodeId='1' name='test1']");
      notifyStdoutLineAvailable("##teamcity[testFinished nodeId='2' duration='100']");

      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='3' parentNodeId='1' name='test2']");
      notifyStdoutLineAvailable("##teamcity[testFinished nodeId='3' duration='50']");

      notifyStdoutLineAvailable("##teamcity[testSuiteFinished nodeId='1' duration='500']");
      notifyStdoutLineAvailable("##teamcity[testingFinished]");
      assertState(2, 0, 1, "passed");
      SMTestProxy suite = myRootNode.getChildren().getFirst();
      assertNotNull(suite);
      Long suiteDuration = suite.getDuration();
      assertNotNull(suiteDuration);
      assertEquals(150L, suiteDuration.longValue());
    });
  }

  @Test
  public void testManualSuiteWithoutExplicitDuration_computesFromChildren() {
    runInEdtAndWait(() -> {
      notifyStdoutLineAvailable("##teamcity[enteredTheMatrix]");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");
      notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='1' parentNodeId='0' name='suite']");
      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='2' parentNodeId='1' name='test1']");
      notifyStdoutLineAvailable("##teamcity[testFinished nodeId='2' duration='100']");
      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='3' parentNodeId='1' name='test2']");
      notifyStdoutLineAvailable("##teamcity[testFinished nodeId='3' duration='200']");
      notifyStdoutLineAvailable("##teamcity[testSuiteFinished nodeId='1']"); // no explicit duration
      notifyStdoutLineAvailable("##teamcity[testingFinished]");

      SMTestProxy suite = myRootNode.getChildren().getFirst();
      assertEquals(300L, suite.getDuration().longValue());
    });
  }

  @Test
  public void testManualSuite_cacheInvalidatedAfterChildrenFinish() {
    runInEdtAndWait(() -> {
      notifyStdoutLineAvailable("##teamcity[enteredTheMatrix]");
      notifyStdoutLineAvailable("##teamcity[testingStarted]");
      notifyStdoutLineAvailable("##teamcity[testSuiteStarted nodeId='1' parentNodeId='0' name='suite']");
      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='2' parentNodeId='1' name='test1']");

      SMTestProxy suite = myRootNode.getChildren().getFirst();
      assertNull(suite.getDuration()); // cached as null before children finish

      notifyStdoutLineAvailable("##teamcity[testFinished nodeId='2' duration='100']");
      assertEquals(100L, suite.getDuration().longValue()); // cache invalidated, now correct

      notifyStdoutLineAvailable("##teamcity[testStarted nodeId='3' parentNodeId='1' name='test2']");
      notifyStdoutLineAvailable("##teamcity[testFinished nodeId='3' duration='200']");

      notifyStdoutLineAvailable("##teamcity[testSuiteFinished nodeId='1']"); // no explicit duration
      notifyStdoutLineAvailable("##teamcity[testingFinished]");

      assertEquals(300L, suite.getDuration().longValue()); // cache invalidated, now correct
    });
  }

  private void assertState(int finishedTests, int failedTests, int topLevelChildrenCount, String status) {
    assertState(finishedTests,
                failedTests,
                0,
                topLevelChildrenCount,
                status);
  }

  private void assertState(int finishedTests,
                           int failedTests,
                           int ignoredTests,
                           int topLevelChildrenCount,
                           String status) {
    assertEquals(stringify(finishedTests, failedTests, ignoredTests, topLevelChildrenCount, status),
                 stringify(myResultsViewer.getFinishedTestCount(),
                           myResultsViewer.getFailedTestCount(),
                           myResultsViewer.getIgnoredTestCount(),
                           myRootNode.getChildren().size(),
                           myResultsViewer.getTestsStatus()));
  }

  private static String stringify(int finishedTests,
                                  int failedTests,
                                  int ignoredTests,
                                  int topLevelChildrenCount,
                                  String status) {
    return String.format(Locale.US, "finishedTests=%d failedTests=%d ignoredTests=%d topLevelChildrenCount=%d color=%s",
                         finishedTests, failedTests, ignoredTests, topLevelChildrenCount, status);
  }

  private void notifyStdoutLineAvailable(@NotNull String line) {
    myProcessHandler.notifyTextAvailable(line + "\n", ProcessOutputTypes.STDOUT);
  }
}

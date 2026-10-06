// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.testframework.sm.runner.ui;

import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.testframework.TestConsoleProperties;
import com.intellij.execution.testframework.actions.ViewAssertEqualsDiffAction;
import com.intellij.execution.testframework.sm.runner.BaseSMTRunnerTestCase;
import com.intellij.execution.testframework.sm.runner.GeneralToSMTRunnerEventsConvertor;
import com.intellij.execution.testframework.sm.runner.SMTestProxy;
import com.intellij.execution.testframework.sm.runner.events.TestFailedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestFinishedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestStartedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestSuiteFinishedEvent;
import com.intellij.execution.testframework.sm.runner.events.TestSuiteStartedEvent;
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo;
import com.intellij.execution.testframework.stacktrace.DiffHyperlink;
import com.intellij.openapi.ListSelection;
import com.intellij.openapi.progress.util.ProgressBarUtil;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;
import java.util.List;

import static com.intellij.testFramework.EdtTestUtil.runInEdtAndWait;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Roman Chernyatchik
 */
public class SMTestRunnerResultsFormTest extends BaseSMTRunnerTestCase {
  private SMTRunnerConsoleView myConsole;
  private GeneralToSMTRunnerEventsConvertor myEventsProcessor;
  private TreeModel myTreeModel;
  private SMTestRunnerResultsForm myResultsViewer;
  private TestConsoleProperties myConsoleProperties;
  private SMTestProxy.SMRootTestProxy myTestsRootNode;

  @BeforeEach
  void setUp() {
    runInEdtAndWait(() -> {
      myConsoleProperties = createConsoleProperties();
      TestConsoleProperties.HIDE_PASSED_TESTS.set(myConsoleProperties, false);
      TestConsoleProperties.OPEN_FAILURE_LINE.set(myConsoleProperties, false);
      TestConsoleProperties.SCROLL_TO_SOURCE.set(myConsoleProperties, false);
      TestConsoleProperties.SELECT_FIRST_DEFECT.set(myConsoleProperties, false);
      TestConsoleProperties.TRACK_RUNNING_TEST.set(myConsoleProperties, false);

      final ExecutionEnvironment environment = new ExecutionEnvironment();

      myConsole = new SMTRunnerConsoleView(myConsoleProperties);
      myConsole.initUI();
      myResultsViewer = myConsole.getResultsViewer();
      myTestsRootNode = myResultsViewer.getTestsRootNode();
      myEventsProcessor = new GeneralToSMTRunnerEventsConvertor(myConsoleProperties.getProject(), myResultsViewer.getTestsRootNode(), "SMTestFramework");
      myEventsProcessor.addEventsListener(myResultsViewer);
      myTreeModel = myResultsViewer.getTreeView().getModel();
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());
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
  public void testGetTestsRootNode() {
    runInEdtAndWait(() -> {
      assertNotNull(myTestsRootNode);

      myResultsViewer.onTestingFinished(myTestsRootNode);
      assertNotNull(myResultsViewer.getTestsRootNode());
    });
  }

  @Test
  public void testTestingStarted() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestingStarted(myTestsRootNode);

      assertTrue(myResultsViewer.getStartTime() > 0);
      assertEquals(0, myResultsViewer.getFinishedTestCount());
      assertEquals(0, myResultsViewer.getTotalTestCount());
    });
  }

  @Test
  public void testOnTestStarted() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestStarted(createTestProxy("some_test", myTestsRootNode));
      assertEquals(1, myResultsViewer.getStartedTestCount());

      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertEquals(2, myResultsViewer.getStartedTestCount());
    });
  }

  @Test
  public void testCount() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestsCountInSuite(1);

      assertEquals(1, myResultsViewer.getTotalTestCount());

      myResultsViewer.onTestStarted(createTestProxy("some_test", myTestsRootNode));
      assertEquals(1, myResultsViewer.getTotalTestCount());

      // if exceeds - will be incremented
      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertEquals(2, myResultsViewer.getTotalTestCount());
    });
  }

  @Test
  public void testCount_UnSet() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestStarted(createTestProxy("some_test", myTestsRootNode));
      assertEquals(0, myResultsViewer.getTotalTestCount());

      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertEquals(0, myResultsViewer.getTotalTestCount());

      // count will be updated only on tests finished if wasn't set
      myResultsViewer.onTestingFinished(myTestsRootNode);
      assertEquals(2, myResultsViewer.getTotalTestCount());
    });
  }

  @Test
  public void testOnTestFailure() {
    runInEdtAndWait(() -> {
      final SMTestProxy test = createTestProxy(myTestsRootNode);

      myResultsViewer.onTestStarted(test);
      myResultsViewer.onTestFailed(test);

      assertEquals(1, myResultsViewer.getFailedTestCount());
      assertEquals(1, myResultsViewer.getFailedTestCount());
    });
  }

  @Test
  public void testOnTestFinished() {
    runInEdtAndWait(() -> {
      final SMTestProxy test = createTestProxy("some_test", myTestsRootNode);

      myResultsViewer.onTestStarted(test);
      assertEquals(1, myResultsViewer.getStartedTestCount());

      myResultsViewer.onTestFinished(test);
      assertEquals(1, myResultsViewer.getFinishedTestCount());
    });
  }

  @Test
  public void testOnTestsCountInSuite() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestsCountInSuite(200);

      assertEquals(0, myResultsViewer.getFinishedTestCount());
      assertEquals(200, myResultsViewer.getTotalTestCount());

      myResultsViewer.onTestsCountInSuite(50);
      assertEquals(250, myResultsViewer.getTotalTestCount());
    });
  }

  @Test
  public void testOnTestStart_ChangeTotal() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestsCountInSuite(2);

      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(2, myResultsViewer.getTotalTestCount());
      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertEquals(2, myResultsViewer.getTotalTestCount());
      myResultsViewer.onTestStarted(createTestProxy("some_test3", myTestsRootNode));
      assertEquals(3, myResultsViewer.getTotalTestCount());
      myResultsViewer.onTestStarted(createTestProxy("some_test4", myTestsRootNode));
      assertEquals(4, myResultsViewer.getTotalTestCount());

      myResultsViewer.onTestsCountInSuite(2);
      myResultsViewer.onTestStarted(createTestProxy("another_test1", myTestsRootNode));
      assertEquals(6, myResultsViewer.getTotalTestCount());
      myResultsViewer.onTestStarted(createTestProxy("another_test2", myTestsRootNode));
      assertEquals(6, myResultsViewer.getTotalTestCount());
      myResultsViewer.onTestStarted(createTestProxy("another_test3", myTestsRootNode));
      assertEquals(7, myResultsViewer.getTotalTestCount());
    });
  }

  @Test
  public void testOnFinishTesting_EndTime() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestingFinished(myTestsRootNode);
      assertTrue(myResultsViewer.getEndTime() > 0);
    });
  }

  @Test
  public void testOnSuiteStarted() {
    runInEdtAndWait(() -> {
      assertEquals(0, myResultsViewer.getFinishedTestCount());
      myResultsViewer.onSuiteStarted(createSuiteProxy(myTestsRootNode));
      assertEquals(0, myResultsViewer.getFinishedTestCount());
    });
  }

  @Test
  public void testRuby_1767() {
    runInEdtAndWait(() -> {
      TestConsoleProperties.HIDE_PASSED_TESTS.set(myConsoleProperties, true);

      myEventsProcessor.onStartTesting();
      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite", null));
      myResultsViewer.performUpdate();
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());

      myEventsProcessor.onTestStarted(new TestStartedEvent("test_failed", null));
      myResultsViewer.performUpdate();
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());

      myEventsProcessor.onTestFailure(new TestFailedEvent("test_failed", "", "", false, null, null));
      myResultsViewer.performUpdate();
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());

      myEventsProcessor.onTestFinished(new TestFinishedEvent("test_failed", 10L));
      myResultsViewer.performUpdate();
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());

      myEventsProcessor.onTestStarted(new TestStartedEvent("test", null));
      myResultsViewer.performUpdate();
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());

      assertEquals(2, myTreeModel.getChildCount(myTreeModel.getChild(myTreeModel.getRoot(), 0)));

      myEventsProcessor.onTestFinished(new TestFinishedEvent("test", 10L));
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());
      assertEquals(2, myTreeModel.getChildCount(myTreeModel.getChild(myTreeModel.getRoot(), 0)));

      myEventsProcessor.onSuiteFinished(new TestSuiteFinishedEvent("suite"));
      myEventsProcessor.onFinishTesting();
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());

      assertEquals(1, myTreeModel.getChildCount(myTreeModel.getChild(myTreeModel.getRoot(), 0)));
    });
  }

  @Test
  public void testExpandIfOnlyOneRootChild() {
    runInEdtAndWait(() -> {
      myEventsProcessor.onStartTesting();
      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite1", null));
      myResultsViewer.performUpdate();
      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite2", null));
      myResultsViewer.performUpdate();

      myEventsProcessor.onTestStarted(new TestStartedEvent("test_failed", null));
      myResultsViewer.performUpdate();
      myEventsProcessor.onTestFailure(new TestFailedEvent("test_failed", "", "", false, null, null));
      myResultsViewer.performUpdate();
      myEventsProcessor.onTestFinished(new TestFinishedEvent("test_failed", 10L));
      myResultsViewer.performUpdate();

      myEventsProcessor.onTestStarted(new TestStartedEvent("test", null));
      myResultsViewer.performUpdate();

      myEventsProcessor.onTestFinished(new TestFinishedEvent("test", 10L));
      myResultsViewer.performUpdate();

      myEventsProcessor.onSuiteFinished(new TestSuiteFinishedEvent("suite2"));
      myResultsViewer.performUpdate();
      myEventsProcessor.onSuiteFinished(new TestSuiteFinishedEvent("suite1"));
      myResultsViewer.performUpdate();
      myEventsProcessor.onFinishTesting();
      myResultsViewer.performUpdate();
      PlatformTestUtil.waitWhileBusy(myResultsViewer.getTreeView());

      final DefaultMutableTreeNode suite1Node =
        (DefaultMutableTreeNode)myTreeModel.getChild(myTreeModel.getRoot(), 0);
      final DefaultMutableTreeNode suite2Node =
        (DefaultMutableTreeNode)myTreeModel.getChild(suite1Node, 0);

      //todo auto expand is disabled
      assertFalse(myResultsViewer.getTreeView().isExpanded(new TreePath(suite1Node.getPath())));
      assertFalse(myResultsViewer.getTreeView().isExpanded(new TreePath(suite2Node.getPath())));
    });
  }

  //with test tree build before start actual tests
  @Test
  public void testPrependTreeAndSameTestsStartFinish() {
    runInEdtAndWait(() -> {
      //send tree
      myEventsProcessor.onSuiteTreeStarted("suite1", null, null, "suite1", "0");
      myEventsProcessor.onSuiteTreeNodeAdded("test1", null, null,"test1", "suite1");
      myEventsProcessor.onSuiteTreeEnded("suite1");
      myEventsProcessor.onBuildTreeEnded();

      //start testing
      myEventsProcessor.onStartTesting();
    
      //invocation count for method set to 2
      for(int i = 0; i < 2; i++) {
        myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite1", null));

        myEventsProcessor.onTestStarted(new TestStartedEvent("test1", null));
        myResultsViewer.performUpdate();
        myEventsProcessor.onTestFailure(new TestFailedEvent("test1", "", "", false, "a", "b"));
        myResultsViewer.performUpdate();
        myEventsProcessor.onTestFinished(new TestFinishedEvent("test1", 10L));
        myResultsViewer.performUpdate();
        myEventsProcessor.onSuiteFinished(new TestSuiteFinishedEvent("suite1"));
        myResultsViewer.performUpdate();
      }

      myEventsProcessor.onFinishTesting();
      myResultsViewer.performUpdate();

      //ensure 2 nodes found
      assertEquals(2, myResultsViewer.getFailedTestCount());
    });
  }

  @Test
  public void testBuildAsSuiteFailAsTest() {
    runInEdtAndWait(() -> {
      //send tree
      myEventsProcessor.onSuiteTreeStarted("suite1", null, null, "suite1", "0");
      myEventsProcessor.onSuiteTreeStarted("test1", null, null,"test1", "suite1");
      myEventsProcessor.onSuiteTreeEnded("test1");
      myEventsProcessor.onSuiteTreeEnded("suite1");
      myEventsProcessor.onBuildTreeEnded();

      //start testing
      myEventsProcessor.onStartTesting();
    
      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("suite1", null));
      myEventsProcessor.onSuiteStarted(new TestSuiteStartedEvent("test1", null));

      myEventsProcessor.onTestFailure(new TestFailedEvent("test1", "", "", false, "a", "b"));
      myResultsViewer.performUpdate();
      myEventsProcessor.onTestFinished(new TestFinishedEvent("test1", 10L));
      myResultsViewer.performUpdate();
      myEventsProcessor.onSuiteFinished(new TestSuiteFinishedEvent("suite1"));
      myResultsViewer.performUpdate();

      myEventsProcessor.onFinishTesting();
      myResultsViewer.performUpdate();

      List<? extends SMTestProxy> children = myResultsViewer.getTestsRootNode().getChildren();
      assertThat(children).hasSize(1);
      assertEquals(TestStateInfo.Magnitude.FAILED_INDEX.getValue(), children.get(0).getMagnitude());
    });
  }

  @Test
  public void testCustomProgress_General() {
    runInEdtAndWait(() -> {
      myResultsViewer.onCustomProgressTestsCategory("foo", 4);

      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(0, myResultsViewer.getFinishedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(1, myResultsViewer.getStartedTestCount());

      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertEquals(1, myResultsViewer.getStartedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(2, myResultsViewer.getStartedTestCount());
    });
  }

  @Test
  public void testCustomProgress_MixedMde() {
    runInEdtAndWait(() -> {
      // enable custom mode
      myResultsViewer.onCustomProgressTestsCategory("foo", 4);

      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(0, myResultsViewer.getFinishedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(1, myResultsViewer.getStartedTestCount());

      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertEquals(1, myResultsViewer.getStartedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(2, myResultsViewer.getStartedTestCount());

      // disable custom mode
      myResultsViewer.onCustomProgressTestsCategory(null, 0);

      assertEquals(2, myResultsViewer.getStartedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(2, myResultsViewer.getStartedTestCount());
      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(3, myResultsViewer.getStartedTestCount());

      assertEquals(3, myResultsViewer.getStartedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(3, myResultsViewer.getStartedTestCount());
      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(4, myResultsViewer.getStartedTestCount());
    });
  }

  @Test
  public void testCustomProgress_EmptySuite() {
    runInEdtAndWait(() -> {
      myResultsViewer.onCustomProgressTestsCategory("foo", 0);

      final SMTestProxy suite = createSuiteProxy("some_suite", myTestsRootNode);
      myTestsRootNode.setStarted();

      myResultsViewer.onSuiteStarted(suite);
      suite.setStarted();
      suite.setFinished();
      myResultsViewer.onSuiteFinished(suite);

      myTestsRootNode.setFinished();
      myResultsViewer.onSuiteFinished(myTestsRootNode);
    
      myResultsViewer.onTestingFinished(myTestsRootNode);
      assertEquals(0, myResultsViewer.getTotalTestCount());
    });
  }

  @Test
  public void testCustomProgress_Failure() {
    runInEdtAndWait(() -> {
      myResultsViewer.onCustomProgressTestsCategory("foo", 4);

      final SMTestProxy test1 = createTestProxy("some_test1", myTestsRootNode);
      myResultsViewer.onTestStarted(test1);
      myResultsViewer.onCustomProgressTestStarted();

      myResultsViewer.onTestFailed(test1);
      assertEquals(0, myResultsViewer.getFailedTestCount());

      myResultsViewer.onCustomProgressTestFailed();
      assertEquals(1, myResultsViewer.getFailedTestCount());

      assertEquals(ProgressBarUtil.FAILED_VALUE, myResultsViewer.getTestsStatus());
    });
  }

  @Test
  public void testProgressBar_Ignored() {
    runInEdtAndWait(() -> {
      final SMTestProxy test1 = createTestProxy("some_test1", myTestsRootNode);
      myResultsViewer.onTestStarted(test1);
      myResultsViewer.performUpdate();
      myResultsViewer.onTestIgnored(test1);
      myResultsViewer.performUpdate();
      assertEquals(0, myResultsViewer.getFailedTestCount());
      assertEquals(1, myResultsViewer.getIgnoredTestCount());

      assertEquals(ProgressBarUtil.PASSED_VALUE, myResultsViewer.getTestsStatus());
    });
  }

  @Test
  public void testCustomProgress_Terminated() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestingStarted(myTestsRootNode);

      final SMTestProxy test1 = createTestProxy("some_test1", myTestsRootNode);
      myResultsViewer.onTestStarted(test1);

      myResultsViewer.onTestingFinished(myTestsRootNode);

      assertEquals(ProgressBarUtil.PASSED_VALUE, myResultsViewer.getTestsStatus());
    });
  }

  @Test
  public void testCustomProgress_Terminated_SmthFailed() {
    runInEdtAndWait(() -> {
      myResultsViewer.onTestingStarted(myTestsRootNode);

      final SMTestProxy test1 = createTestProxy("some_test1", myTestsRootNode);
      myResultsViewer.onTestStarted(test1);
      myResultsViewer.onTestFailed(test1);
      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      myResultsViewer.onTestingFinished(myTestsRootNode);

      assertEquals(ProgressBarUtil.FAILED_VALUE, myResultsViewer.getTestsStatus());
    });
  }

  @Test
  public void testCustomProgress_UnSetCount() {
    runInEdtAndWait(() -> {
      myResultsViewer.onCustomProgressTestsCategory("foo", 0);

      assertEquals(0, myResultsViewer.getTotalTestCount());

      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(0, myResultsViewer.getTotalTestCount());

      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(0, myResultsViewer.getTotalTestCount());

      // count will be updated only on tests finished if wasn't set
      myResultsViewer.onTestingFinished(myTestsRootNode);
      assertEquals(2, myResultsViewer.getTotalTestCount());
    });
  }

  @Test
  public void testCustomProgress_IncreaseCount() {
    runInEdtAndWait(() -> {
      myResultsViewer.onCustomProgressTestsCategory("foo", 1);

      assertEquals(1, myResultsViewer.getTotalTestCount());

      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(1, myResultsViewer.getTotalTestCount());

      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(2, myResultsViewer.getTotalTestCount());
    });
  }

  @Test
  public void testCustomProgress_IncreaseCount_MixedMode() {
    runInEdtAndWait(() -> {
      // custom mode
      myResultsViewer.onCustomProgressTestsCategory("foo", 1);

      assertEquals(1, myResultsViewer.getTotalTestCount());

      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(1, myResultsViewer.getTotalTestCount());

      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(2, myResultsViewer.getTotalTestCount());

      // disable custom mode
      myResultsViewer.onCustomProgressTestsCategory(null, 0);
      assertEquals(2, myResultsViewer.getTotalTestCount());

      myResultsViewer.onTestsCountInSuite(1);
      assertEquals(3, myResultsViewer.getTotalTestCount());

      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(3, myResultsViewer.getTotalTestCount());

      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertEquals(4, myResultsViewer.getTotalTestCount());
    });
  }

  //TODO categories - mized

  @Test
  public void testCustomProgress_MentionedCategories_CategoryWithoutName() {
    runInEdtAndWait(() -> {
      // enable custom mode
      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());

      myResultsViewer.onCustomProgressTestsCategory("foo", 4);

      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());
    });
  }

  @Test
  public void testCustomProgress_MentionedCategories_DefaultCategory() {
    runInEdtAndWait(() -> {
      // enable custom mode
      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());

      myResultsViewer.onCustomProgressTestStarted();

      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());
    });
  }

  @Test
  public void testCustomProgress_MentionedCategories_OneCustomCategory() {
    runInEdtAndWait(() -> {
      // enable custom mode
      myResultsViewer.onCustomProgressTestsCategory("Foo", 4);
      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());

      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());

      myResultsViewer.onCustomProgressTestStarted();
      assertThat(myResultsViewer.getMentionedCategories()).containsExactlyInAnyOrder("Foo");

      // disable custom mode
      myResultsViewer.onCustomProgressTestsCategory(null, 0);
      assertThat(myResultsViewer.getMentionedCategories()).containsExactlyInAnyOrder("Foo");
    });
  }

  @Test
  public void testCustomProgress_MentionedCategories_SeveralCategories() {
    runInEdtAndWait(() -> {
      // enable custom mode
      myResultsViewer.onCustomProgressTestsCategory("Foo", 4);
      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());

      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());

      myResultsViewer.onCustomProgressTestStarted();
      assertThat(myResultsViewer.getMentionedCategories()).containsExactlyInAnyOrder("Foo");

      // disable custom mode
      myResultsViewer.onCustomProgressTestsCategory(null, 0);

      myResultsViewer.onCustomProgressTestStarted();
      assertThat(myResultsViewer.getMentionedCategories()).containsExactlyInAnyOrder("Foo");

      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertThat(myResultsViewer.getMentionedCategories()).containsExactlyInAnyOrder("Foo", TestsPresentationUtil.DEFAULT_TESTS_CATEGORY);
    });
  }

  @Test
  public void testCustomProgress_MentionedCategories() {
    runInEdtAndWait(() -> {
      // enable custom mode
      assertTrue(myResultsViewer.getMentionedCategories().isEmpty());

      myResultsViewer.onCustomProgressTestsCategory("foo", 4);

      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(0, myResultsViewer.getStartedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(1, myResultsViewer.getStartedTestCount());

      myResultsViewer.onTestStarted(createTestProxy("some_test2", myTestsRootNode));
      assertEquals(1, myResultsViewer.getStartedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(2, myResultsViewer.getStartedTestCount());

      // disable custom mode
      myResultsViewer.onCustomProgressTestsCategory(null, 0);

      assertEquals(2, myResultsViewer.getStartedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(2, myResultsViewer.getStartedTestCount());
      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(3, myResultsViewer.getStartedTestCount());

      assertEquals(3, myResultsViewer.getStartedTestCount());
      myResultsViewer.onCustomProgressTestStarted();
      assertEquals(3, myResultsViewer.getStartedTestCount());
      myResultsViewer.onTestStarted(createTestProxy("some_test1", myTestsRootNode));
      assertEquals(4, myResultsViewer.getStartedTestCount());
    });
  }

  @Test
  public void testDiffOnNonLeafNode() {
    runInEdtAndWait(() -> {
      SMTestProxy suite1 = createSuiteProxy(myTestsRootNode);
      suite1.setStarted();
      SMTestProxy test1 = createTestProxy("test1", suite1);
      test1.setStarted();
      test1.setTestComparisonFailed("m1", "m1", "m2", "m1");
      test1.setFinished();
      suite1.setFinished();

      SMTestProxy suite2 = createSuiteProxy(myTestsRootNode);
      suite2.setStarted();
      SMTestProxy test2 = createTestProxy("test2", suite2);
      test2.setStarted();
      test2.setTestComparisonFailed("m2", "m2", "m1", "m2");
      test2.setFinished();
      suite2.setFinished();

      ListSelection<DiffHyperlink> hyperlinks = ViewAssertEqualsDiffAction.showDiff(suite2, myResultsViewer);
      List<? extends DiffHyperlink> providers = hyperlinks.getList();
      assertEquals(2, providers.size());
      assertEquals(1, hyperlinks.getSelectedIndex());
      DiffHyperlink selectedProvider = providers.get(0);
      assertEquals("m1", selectedProvider.getLeft());
      assertEquals("m2", selectedProvider.getRight());
    });
  }
}

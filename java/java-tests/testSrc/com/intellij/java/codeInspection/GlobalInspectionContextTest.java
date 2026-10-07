// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.java.codeInspection;

import com.intellij.JavaTestUtil;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.JavaCodeInsightTestCase;
import com.intellij.codeInsight.daemon.impl.DaemonProgressIndicator;
import com.intellij.codeInspection.CommonProblemDescriptor;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.GlobalInspectionTool;
import com.intellij.codeInspection.GlobalSimpleInspectionTool;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.InspectionProfileEntry;
import com.intellij.codeInspection.LocalInspectionEP;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptionsProcessor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.actions.RunInspectionIntention;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.GlobalInspectionToolWrapper;
import com.intellij.codeInspection.ex.InspectionManagerEx;
import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.codeInspection.ex.InspectionToolWrapper;
import com.intellij.codeInspection.ex.InspectionToolsSupplier;
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper;
import com.intellij.codeInspection.ex.Tools;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefManager;
import com.intellij.codeInspection.reference.RefManagerImpl;
import com.intellij.codeInspection.reference.RefMethodImpl;
import com.intellij.codeInspection.ui.InspectionToolPresentation;
import com.intellij.codeInspection.visibility.VisibilityInspection;
import com.intellij.diagnostic.PluginException;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.application.ex.ApplicationEx;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressWrapper;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.InspectionsKt;
import com.intellij.testFramework.LoggedErrorProcessor;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.concurrency.ThreadingAssertions;
import com.intellij.util.containers.ContainerUtil;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class GlobalInspectionContextTest extends JavaCodeInsightTestCase {
  @Override
  public void setUp() throws Exception {
    super.setUp();
    InspectionProfileImpl.INIT_INSPECTIONS = true;
    ThreadingAssertions.assertEventDispatchThread();
    assertFalse(ApplicationManager.getApplication().isWriteAccessAllowed());
  }

  @Override
  public void tearDown() throws Exception {
    InspectionProfileImpl.INIT_INSPECTIONS = false;
    super.tearDown();
  }

  @NotNull
  @Override
  protected String getTestDataPath() {
    return JavaTestUtil.getJavaTestDataPath() + "/inspection/globalContext/";
  }

  public void testProblemDuplication() throws Exception {
    String shortName = new VisibilityInspection().getShortName();
    InspectionProfileImpl profile = new InspectionProfileImpl("Foo");
    InspectionsKt.disableAllTools(profile);
    profile.enableTool(shortName, getProject());

    GlobalInspectionContextImpl context = ((InspectionManagerEx)InspectionManager.getInstance(getProject())).createNewGlobalContext();
    context.setExternalProfile(profile);
    configureByFile("Foo.java");

    AnalysisScope scope = new AnalysisScope(getFile());
    context.doInspections(scope);
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); // wait for launchInspections in invoke later

    Tools tools = context.getTools().get(shortName);
    GlobalInspectionToolWrapper toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
    InspectionToolPresentation presentation = context.getPresentation(toolWrapper);
    assertEquals(1, presentation.getProblemDescriptors().size());

    context.doInspections(scope);
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); // wait for launchInspections in invoke later

    tools = context.getTools().get(shortName);
    toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
    presentation = context.getPresentation(toolWrapper);
    assertEquals(1, presentation.getProblemDescriptors().size());
  }

  public void testBatchInspectionMustBeRunUnderDaemonProgressIndicatorToAvoidSpammingStatusBarWithIrrelevantMessages() throws Throwable {
    AtomicBoolean run = new AtomicBoolean();
    AtomicReference<Throwable> throwable = new AtomicReference<>();
    LocalInspectionTool tool = new LocalInspectionTool() {
      @Nls
      @NotNull
      @Override
      public String getGroupDisplayName() {
        return "fegna2";
      }

      @Nls
      @NotNull
      @Override
      public String getDisplayName() {
        return getGroupDisplayName();
      }

      @NotNull
      @Override
      public String getShortName() {
        return getGroupDisplayName();
      }

      @NotNull
      @Override
      public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
          @Override
          public void visitFile(@NotNull PsiFile psiFile) {
            run.set(true);
            ProgressIndicator indicator = ProgressWrapper.unwrapAll(ProgressManager.getGlobalProgressIndicator());
            if (!(indicator instanceof DaemonProgressIndicator)) {
              throwable.set(new IllegalStateException("expected DaemonProgressIndicator but got: " + indicator +" "+indicator.getClass()));
            }
          }
        };
      }
    };
    InspectionProfileImpl profile = InspectionsKt.configureInspections(new InspectionProfileEntry[]{tool}, getProject(), getTestRootDisposable());

    GlobalInspectionContextImpl context = ((InspectionManagerEx)InspectionManager.getInstance(getProject())).createNewGlobalContext();
    context.setExternalProfile(profile);
    configureByText(PlainTextFileType.INSTANCE, "blah");

    AnalysisScope scope = new AnalysisScope(getFile());
    context.doInspections(scope);
    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue(); // wait for launchInspections in invoke later

    assertTrue(run.get());
    if (throwable.get() != null) throw throwable.get();
  }

  public void testRunInspectionContext() {
    InspectionProfile profile = new InspectionProfileImpl("foo");
    List<InspectionToolWrapper<?, ?>> tools = profile.getInspectionTools(null);
    PsiFile file = createDummyFile("xx.txt", "xxx");
    for (InspectionToolWrapper<?, ?> toolWrapper : tools) {
      if (!toolWrapper.isEnabledByDefault()) {
        InspectionManagerEx instance = (InspectionManagerEx)InspectionManager.getInstance(myProject);
        GlobalInspectionContextImpl context = RunInspectionIntention.createContext(toolWrapper, instance, file);
        context.initializeTools(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assertEquals(1, context.getTools().size());
        return;
      }
    }
    fail("No disabled tools found: " + tools);
  }

  public void testToolThatCannotBeInstantiatedIsSkipped() {
    LocalInspectionEP ep = new LocalInspectionEP();
    ep.shortName = ep.displayName = ep.groupDisplayName = "BrokenTestInspection";
    ep.level = "WARNING";
    ep.enabledByDefault = true;
    ep.implementationClass = "com.intellij.java.codeInspection.NonExistentInspection";
    ep.setPluginDescriptor(PluginManagerCore.getPlugin(PluginManagerCore.CORE_ID));
    LocalInspectionToolWrapper brokenWrapper = new LocalInspectionToolWrapper(ep);
    LocalInspectionToolWrapper workingWrapper = new LocalInspectionToolWrapper(new WorkingTestInspection());

    InspectionToolsSupplier.Simple toolSupplier = new InspectionToolsSupplier.Simple(List.of(brokenWrapper, workingWrapper));
    Disposer.register(getTestRootDisposable(), toolSupplier);
    InspectionProfileImpl profile = new InspectionProfileImpl("Foo", toolSupplier, (InspectionProfileImpl)null);
    profile.enableTool(brokenWrapper.getShortName(), getProject());
    profile.enableTool(workingWrapper.getShortName(), getProject());

    GlobalInspectionContextImpl context = ((InspectionManagerEx)InspectionManager.getInstance(getProject())).createNewGlobalContext();
    context.setExternalProfile(profile);
    List<Tools> localTools = new ArrayList<>();
    Throwable error = LoggedErrorProcessor.executeAndReturnLoggedError(
      () -> context.initializeTools(new ArrayList<>(), localTools, new ArrayList<>()));

    assertInstanceOf(error, PluginException.class);
    assertEquals(PluginManagerCore.CORE_ID, ((PluginException)error).getPluginId());
    assertTrue(error.getMessage(), error.getMessage().contains(brokenWrapper.getShortName()));
    Map<String, Tools> tools = context.getTools();
    assertFalse(tools.containsKey(brokenWrapper.getShortName()));
    assertTrue(tools.containsKey(workingWrapper.getShortName()));
    assertEquals(List.of(workingWrapper.getShortName()), ContainerUtil.map(localTools, Tools::getShortName));
  }

  public static final class WorkingTestInspection extends LocalInspectionTool {
  }

  public void testJavaMethodExternalization() throws Exception {
    PsiFile file = createFile("Foo.java", """
      public class Foo {
          <T> void foo(T t) {
          }
      }""");
    GlobalInspectionContextImpl context = ((InspectionManagerEx)InspectionManager.getInstance(getProject())).createNewGlobalContext();
    context.setCurrentScope(new AnalysisScope(file));
    context.initializeTools(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    PsiClass[] classes = ((PsiClassOwner)file).getClasses();
    PsiClass fooClass = classes[0];
    PsiMethod fooMethod = fooClass.findMethodsByName("foo", false)[0];
    RefElement refMethod = context.getRefManager().getReference(fooMethod);
    String externalName = refMethod.getExternalName();
    PsiMethod deserialized = RefMethodImpl.findPsiMethod(fooMethod.getManager(), externalName);
    assertEquals(deserialized, fooMethod);
  }

  public void testGlobalInspectionContinuesAfterWriteAction() {
    doTestGlobalInspectionProgress(false, true, false);
  }

  public void testGlobalInspectionExternalUsagesContinueAfterWriteAction() {
    doTestGlobalInspectionProgress(true, true, false);
  }

  public void testGlobalInspectionCompletesWithoutWriteAction() {
    doTestGlobalInspectionProgress(false, false, false);
  }

  public void testGlobalInspectionGetsCancelledByUser() {
    doTestGlobalInspectionProgress(false, false, true);
  }

  public void testGlobalInspectionExternalUsagesGetCancelledByUser() {
    doTestGlobalInspectionProgress(true, false, true);
  }

  public void testGlobalInspectionRestartsAndFinishesAfterTyping() {
    doTestGlobalInspectionTyping(1, false);
  }

  public void testGlobalInspectionRestartsAndFinishesAfterRepeatedTyping() {
    doTestGlobalInspectionTyping(2, false);
  }

  public void testGlobalInspectionGetsCancelledAfterRestart() {
    doTestGlobalInspectionTyping(1, true);
  }

  private void doTestGlobalInspectionTyping(int typingCount, boolean cancelAfterRestart) {
    configureByText(JavaFileType.INSTANCE, "class Foo {<caret>}");
    var application = ApplicationManager.getApplication();
    var modalityState = ModalityState.current();
    var document = getEditor().getDocument();
    var inspectionStarted = new AtomicInteger();
    var interruptedAttemptsExited = new AtomicInteger();
    var interruptedAttemptCompletedNormally = new AtomicBoolean();
    var typingCompleted = new AtomicInteger();
    var inspectionIndicator = new AtomicReference<ProgressIndicator>();
    var previousRefManager = new AtomicReference<RefManager>();
    var cleanupCount = new AtomicInteger();
    var testActive = new AtomicBoolean(true);
    var inspectionRuns = new AtomicInteger();
    var inspectionCompleted = new AtomicBoolean();
    var inspectedText = new AtomicReference<String>();
    var queryCompleted = new AtomicBoolean();
    var tool = new GlobalInspectionTool() {
      @Override
      public @NotNull String getShortName() {
        return "GlobalTypingRestartTest";
      }

      @Override
      public @NotNull String getDisplayName() {
        return getShortName();
      }

      @Override
      public void initialize(@NotNull GlobalInspectionContext context) {
        inspectionIndicator.set(ProgressIndicatorProvider.getGlobalProgressIndicator());
      }

      @Override
      public void cleanup(@NotNull Project project) {
        cleanupCount.incrementAndGet();
      }

      @Override
      public void runInspection(@NotNull AnalysisScope scope,
                                @NotNull InspectionManager manager,
                                @NotNull GlobalInspectionContext globalContext,
                                @NotNull ProblemDescriptionsProcessor processor) {
        ApplicationManager.getApplication().assertReadAccessAllowed();
        int attempt = inspectionRuns.incrementAndGet();
        var refManager = globalContext.getRefManager();
        assertTrue("The reference graph must be built for each attempt", ((RefManagerImpl)refManager).isDeclarationsFound());
        assertNotSame("A restart must replace the reference manager", previousRefManager.getAndSet(refManager), refManager);
        assertEquals("A restart must clean the inspection", attempt - 1, cleanupCount.get());
        if (attempt <= typingCount || cancelAfterRestart) {
          processor.addProblemElement(refManager.getRefProject(), manager.createProblemDescriptor("Partial result"));
          inspectionStarted.set(attempt);
          try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (testActive.get() && System.nanoTime() < deadline) {
              ProgressManager.checkCanceled();
              Thread.onSpinWait();
            }
            ProgressManager.checkCanceled();
            interruptedAttemptCompletedNormally.set(true);
          }
          finally {
            interruptedAttemptsExited.incrementAndGet();
          }
          return;
        }
        inspectedText.set(getFile().getText());
        processor.addProblemElement(globalContext.getRefManager().getRefProject(),
                                    manager.createProblemDescriptor("Finished after typing"));
        inspectionCompleted.set(true);
      }

      @Override
      public boolean queryExternalUsagesRequests(@NotNull InspectionManager manager,
                                                 @NotNull GlobalInspectionContext globalContext,
                                                 @NotNull ProblemDescriptionsProcessor processor) {
        queryCompleted.set(true);
        return false;
      }
    };
    var profile = InspectionsKt.configureInspections(new InspectionProfileEntry[]{tool}, getProject(), getTestRootDisposable());
    var context = ((InspectionManagerEx)InspectionManager.getInstance(getProject())).createNewGlobalContext();
    context.setExternalProfile(profile);
    try {
      application.invokeLater(new Runnable() {
        private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        @Override
        public void run() {
          if (!testActive.get()) return;
          if (inspectionStarted.get() <= typingCompleted.get()) {
            if (System.nanoTime() < deadline) {
              application.invokeLater(this, modalityState);
            }
            return;
          }
          if (typingCompleted.get() < typingCount) {
            type(' ');
            typingCompleted.incrementAndGet();
            if (typingCompleted.get() < typingCount || cancelAfterRestart) {
              application.invokeLater(this, modalityState);
            }
          }
          else {
            assertNotNull(inspectionIndicator.get());
            inspectionIndicator.get().cancel();
          }
        }
      }, modalityState);
      context.doInspections(new AnalysisScope(getFile()));
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

      assertEquals("The user must type during each interrupted attempt", typingCount, typingCompleted.get());
      var expectedText = "class Foo {" + " ".repeat(typingCount) + "}";
      assertEquals(expectedText, document.getText());
      assertEquals(typingCount + 1, inspectionRuns.get());
      assertEquals(typingCount + (cancelAfterRestart ? 1 : 0), interruptedAttemptsExited.get());
      assertFalse("An interrupted attempt must not complete normally", interruptedAttemptCompletedNormally.get());
      assertEquals(!cancelAfterRestart, inspectionCompleted.get());
      assertEquals(cancelAfterRestart ? null : expectedText, inspectedText.get());
      assertEquals(!cancelAfterRestart, queryCompleted.get());
      assertEquals(cancelAfterRestart, inspectionIndicator.get().isCanceled());
      assertEquals(!cancelAfterRestart, context.areToolsInitialized());
      var wrapper = profile.getInspectionTool(tool.getShortName(), getProject());
      var descriptors = context.getPresentation(wrapper).getProblemDescriptors();
      if (cancelAfterRestart) {
        assertNull(context.getCurrentScope());
        assertEmpty(descriptors);
      }
      else {
        assertNotNull(context.getCurrentScope());
        assertEquals(typingCount, cleanupCount.get());
        assertEquals("Finished after typing", assertOneElement(descriptors).getDescriptionTemplate());
      }
    }
    finally {
      testActive.set(false);
      context.cleanup();
    }
  }

  private void doTestGlobalInspectionProgress(boolean blockInQuery, boolean requestWrite, boolean cancelInspection) {
    var application = (ApplicationEx)ApplicationManager.getApplication();
    var inspectionStarted = new AtomicBoolean();
    var inspectionIndicator = new AtomicReference<ProgressIndicator>();
    var testActive = new AtomicBoolean(true);
    var writeCompleted = new AtomicBoolean();
    var inspectionCompleted = new AtomicBoolean();
    var queryCompleted = new AtomicBoolean();
    var inspectionRuns = new AtomicInteger();
    var queryRuns = new AtomicInteger();
    Runnable awaitAction = () -> {
      application.assertReadAccessAllowed();
      inspectionStarted.set(true);
      if (requestWrite || cancelInspection) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (testActive.get() && (!requestWrite || !writeCompleted.get()) && System.nanoTime() < deadline) {
          ProgressManager.checkCanceled();
          Thread.onSpinWait();
        }
        ProgressManager.checkCanceled();
        assertFalse("The inspection must stop when the user cancels it", cancelInspection);
        fail("The write action must cancel the inspection attempt");
      }
    };
    class TestInspection extends GlobalInspectionTool {
      private final String shortName;
      private boolean firstInspection;

      private TestInspection(String shortName) {
        this.shortName = shortName;
      }

      @Override
      public @NotNull String getShortName() {
        return shortName;
      }

      @Override
      public @NotNull String getDisplayName() {
        return getShortName();
      }

      @Override
      public boolean isGraphNeeded() {
        return false;
      }

      @Override
      public void initialize(@NotNull GlobalInspectionContext context) {
        inspectionIndicator.set(ProgressIndicatorProvider.getGlobalProgressIndicator());
      }

      @Override
      public void runInspection(@NotNull AnalysisScope scope,
                                @NotNull InspectionManager manager,
                                @NotNull GlobalInspectionContext globalContext,
                                @NotNull ProblemDescriptionsProcessor processor) {
        firstInspection = inspectionRuns.incrementAndGet() == 1;
        processor.addProblemElement(globalContext.getRefManager().getRefProject(), manager.createProblemDescriptor("Result"));
        if (firstInspection) {
          if (!blockInQuery) {
            awaitAction.run();
          }
        }
        else if (requestWrite) {
          assertTrue("The write action must finish before the next inspection", writeCompleted.get());
        }
        inspectionCompleted.set(true);
      }

      @Override
      public boolean queryExternalUsagesRequests(@NotNull InspectionManager manager,
                                                 @NotNull GlobalInspectionContext globalContext,
                                                 @NotNull ProblemDescriptionsProcessor processor) {
        queryRuns.incrementAndGet();
        if (firstInspection) {
          if (blockInQuery) {
            awaitAction.run();
          }
        }
        queryCompleted.set(true);
        return false;
      }
    }
    var tools = new InspectionProfileEntry[]{new TestInspection("GlobalProgressTestOne"), new TestInspection("GlobalProgressTestTwo")};
    var profile = InspectionsKt.configureInspections(tools, getProject(), getTestRootDisposable());
    var context = ((InspectionManagerEx)InspectionManager.getInstance(getProject())).createNewGlobalContext();
    context.setExternalProfile(profile);
    configureByText(JavaFileType.INSTANCE, "class Foo {}");

    if (requestWrite || cancelInspection) {
      SwingUtilities.invokeLater(new Runnable() {
        @Override
        public void run() {
          if (!testActive.get()) return;
          if (!inspectionStarted.get()) {
            SwingUtilities.invokeLater(this);
            return;
          }
          if (cancelInspection) {
            inspectionIndicator.get().cancel();
          }
          else {
            WriteAction.run(() -> {
              assertEquals(blockInQuery, inspectionCompleted.get());
              assertFalse(queryCompleted.get());
              writeCompleted.set(true);
            });
          }
        }
      });
    }
    try {
      context.doInspections(new AnalysisScope(getFile()));
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

      assertEquals(cancelInspection ? 1 : requestWrite ? 3 : 2, inspectionRuns.get());
      assertEquals(cancelInspection ? blockInQuery ? 1 : 0 : requestWrite && blockInQuery ? 3 : 2, queryRuns.get());
      assertEquals(!cancelInspection || blockInQuery, inspectionCompleted.get());
      assertEquals(!cancelInspection, queryCompleted.get());
      assertEquals(requestWrite, writeCompleted.get());
      if (cancelInspection) {
        assertFalse(context.areToolsInitialized());
        assertNull(context.getCurrentScope());
      }
      else {
        assertTrue(context.areToolsInitialized());
        assertNotNull(context.getCurrentScope());
      }
      for (var tool : tools) {
        var wrapper = profile.getInspectionTool(tool.getShortName(), getProject());
        var descriptors = context.getPresentation(wrapper).getProblemDescriptors();
        if (cancelInspection) {
          assertEmpty(descriptors);
        }
        else {
          assertEquals("Result", assertOneElement(descriptors).getDescriptionTemplate());
        }
      }
    }
    finally {
      testActive.set(false);
      context.cleanup();
    }
  }

  public void testGlobalSimpleInspectionGetsInterruptedOnWriteActionStart() {
    AtomicBoolean inspectionStarted = new AtomicBoolean();
    AtomicBoolean doRunInspection = new AtomicBoolean(true);
    GlobalSimpleInspectionTool myTool = new GlobalSimpleInspectionTool() {
      @Override
      public void checkFile(@NotNull PsiFile psiFile,
                            @NotNull InspectionManager manager,
                            @NotNull ProblemsHolder problemsHolder,
                            @NotNull GlobalInspectionContext globalContext,
                            @NotNull ProblemDescriptionsProcessor problemDescriptionsProcessor) {
        inspectionStarted.set(true);
        while (doRunInspection.get()) {
          ProgressManager.checkCanceled();
        }
        problemsHolder.registerProblem(manager.createProblemDescriptor(psiFile, "Finished: " + getShortName(), (LocalQuickFix)null, ProblemHighlightType.GENERIC_ERROR_OR_WARNING, true));
      }

      @NotNull
      @Override
      public String getShortName() {
        return "myTool";
      }

      @NotNull
      @Override
      public String getDisplayName() {
        return "A"+getShortName();
      }
    };
    String shortName = myTool.getShortName();
    InspectionProfileImpl profile = new InspectionProfileImpl("myTestProfile:"+getTestName(false));
    InspectionsKt.disableAllTools(profile);
    profile.addTool(getProject(), new GlobalInspectionToolWrapper(myTool), null);
    profile.enableTool(shortName, getProject());

    GlobalInspectionContextImpl context = ((InspectionManagerEx)InspectionManager.getInstance(getProject())).createNewGlobalContext();
    context.setExternalProfile(profile);
    @Language("JAVA")
    String text = "class Foo {}";
    configureByText(JavaFileType.INSTANCE, text);

    AnalysisScope scope = new AnalysisScope(getFile());
    //noinspection SSBasedInspection
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        if (!inspectionStarted.get()) {
          SwingUtilities.invokeLater(this);
          return;
        }

        inspectionStarted.set(false);
        WriteAction.run(() -> { });

        // have to restart
        while (!inspectionStarted.get()) {
          PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
        }

        doRunInspection.set(false);
      }
    });

    context.doInspections(scope);

    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();

    Tools tools = context.getTools().get(shortName);
    GlobalInspectionToolWrapper toolWrapper = (GlobalInspectionToolWrapper)tools.getTool();
    InspectionToolPresentation presentation = context.getPresentation(toolWrapper);
    CommonProblemDescriptor descriptor = assertOneElement(presentation.getProblemDescriptors());
    assertEquals("Finished: "+shortName, descriptor.getDescriptionTemplate());
  }
}

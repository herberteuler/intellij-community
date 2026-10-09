// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.impl;

import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.Project;
import com.intellij.threadDumpParser.ThreadState;
import com.intellij.unscramble.AnalyzeStacktraceUtil;
import com.intellij.unscramble.DumpItem;
import com.intellij.unscramble.IntelliJThreadDumpParserKt;
import com.intellij.unscramble.ThreadDumpPanel;
import com.intellij.unscramble.ThreadDumpState;
import org.jetbrains.annotations.ApiStatus;

import javax.swing.JComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ThreadDumpConsoleFactory implements AnalyzeStacktraceUtil.ConsoleFactory {
  private final Project myProject;
  private final ThreadDumpState myThreadDump;

  public ThreadDumpConsoleFactory(Project project, List<ThreadState> threadDump) {
    this(project, new ThreadDumpState(threadDump, Collections.emptyList()));
  }

  @ApiStatus.Internal
  public ThreadDumpConsoleFactory(Project project, ThreadDumpState threadDump) {
    myProject = project;
    myThreadDump = threadDump;
  }

  @Override
  public JComponent createConsoleComponent(ConsoleView consoleView, DefaultActionGroup toolbarActions) {
    List<DumpItem> dumpItems = new ArrayList<>(IntelliJThreadDumpParserKt.toDumpItems(myThreadDump));
    dumpItems.sort(DumpItem.BY_INTEREST);
    return ThreadDumpPanel.createFromDumpItems(myProject, consoleView, toolbarActions, dumpItems);
  }
}

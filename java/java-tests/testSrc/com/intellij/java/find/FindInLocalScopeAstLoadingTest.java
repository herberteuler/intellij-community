// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.find;

import com.intellij.find.FindModel;
import com.intellij.find.impl.FindInProjectUtil;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.LocalSearchScope;
import com.intellij.testFramework.IndexingTestUtil;
import com.intellij.testFramework.LoggedErrorProcessor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.CommonProcessors;
import com.intellij.util.ExceptionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Pins that a "Find in Path" search in a {@link LocalSearchScope} of a stub-backed element may load the AST of the element's file.
 * The scope check needs the tree; only the text match runs under the AST loading filter (see {@code FindInProjectUtil}).
 */
public class FindInLocalScopeAstLoadingTest extends LightJavaCodeInsightFixtureTestCase {
  public void testSearchInLocalScopeOfStubBasedElementLoadsItsTree() throws Exception {
    Registry.get("ast.loading.filter").setValue(true, getTestRootDisposable());
    VirtualFile file = myFixture.getTempDirFixture().createFile(
      "p/A.java", "package p; class A { void m() { int needle = 1; } void other() { int needle = 2; } }");
    IndexingTestUtil.waitUntilIndexesAreReady(getProject());
    PsiClass aClass = JavaPsiFacade.getInstance(getProject()).findClass("p.A", GlobalSearchScope.projectScope(getProject()));
    assertNotNull(aClass);
    PsiMethod method = aClass.findMethodsByName("m", false)[0];
    assertNull("the AST of the scope file before the search", ((PsiFileImpl)method.getContainingFile()).getTreeElement());

    FindModel model = new FindModel();
    model.setStringToFind("needle");
    model.setMultipleFiles(true);
    model.setProjectScope(false);
    model.setCustomScope(true);
    model.setCustomScope(new LocalSearchScope(method));
    List<UsageInfo> usages = Collections.synchronizedList(new ArrayList<>());
    List<String> errors = Collections.synchronizedList(new ArrayList<>());
    LoggedErrorProcessor.executeWith(new LoggedErrorProcessor() {
      @Override
      public @NotNull Set<Action> processError(@NotNull String category, @NotNull String message, String @NotNull [] details,
                                               @Nullable Throwable t) {
        errors.add(t == null ? message : message + ": " + ExceptionUtil.getThrowableText(t));
        return Action.NONE;
      }
    }, () -> FindInProjectUtil.findUsages(model, getProject(), new CommonProcessors.CollectProcessor<>(usages),
                                          FindInProjectUtil.setupProcessPresentation(false, FindInProjectUtil.setupViewPresentation(false, model))));

    assertEmpty("errors logged by the search", errors);
    assertNotNull("the AST of the scope file after the search", ((PsiFileImpl)method.getContainingFile()).getTreeElement());
    assertSize(1, usages);
    assertEquals(file, usages.getFirst().getVirtualFile());
  }
}

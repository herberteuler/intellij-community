// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.emmet;

import com.intellij.application.options.emmet.EmmetOptions;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiUtilBase;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;

public class EmmetPreviewTypedHandler implements AnActionListener {
  @Override
  public void afterEditorTyping(char c, @NotNull DataContext dataContext) {
    if (!EmmetOptions.getInstance().isEmmetEnabled() ||
        !EmmetOptions.getInstance().isPreviewEnabled()) {
      return;
    }

    Project project = CommonDataKeys.PROJECT.getData(dataContext);
    Editor editor = dataContext.getData(CommonDataKeys.EDITOR);
    if (project == null || editor == null) return;

    PsiFile psiFile = PsiUtilBase.getPsiFileInEditor(editor, project);
    if (psiFile == null) return;

    EmmetPreviewHint existingBalloon = EmmetPreviewHint.getExistingHint(editor);
    if (existingBalloon == null) {
      scheduleHint(editor, psiFile);
    }
  }

  private void scheduleHint(@NotNull Editor editor, @NotNull PsiFile file) {
    ReadAction.nonBlocking(() -> {
        if (editor.isDisposed() || EmmetPreviewHint.getExistingHint(editor) != null) return null;
        return EmmetPreviewUtil.calculateTemplateText(editor, file, true);
      })
      .finishOnUiThread(ModalityState.current(), templateText -> {
        if (StringUtil.isNotEmpty(templateText)) {
          EmmetPreviewHint.createHint((EditorEx)editor, templateText, file.getFileType()).showHint();
          EmmetPreviewUtil.addEmmetPreviewListeners(editor, file, false);
        }
      })
      .withDocumentsCommitted(file.getProject())
      .coalesceBy(this, editor)
      .expireWhen(editor::isDisposed)
      .submit(AppExecutorUtil.getAppExecutorService());
  }
}

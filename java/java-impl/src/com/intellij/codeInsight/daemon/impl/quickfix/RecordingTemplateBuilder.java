// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.template.Expression;
import com.intellij.codeInsight.template.TemplateBuilder;
import com.intellij.codeInsight.template.impl.ConstantNode;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.ModTemplateBuilder;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.SmartPointerManager;
import com.intellij.psi.SmartPsiElementPointer;
import com.intellij.util.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the template fields of an element, and writes them into a {@link ModTemplateBuilder} later.
 * <p>
 * A {@link ModTemplateBuilder} writes the value of a field into the document at once, which leaves the PSI
 * behind. So the caller makes every PSI change first, and calls {@link #flushTo} at the end.
 * <p>
 * It supports only the fields which replace a whole element.
 */
final class RecordingTemplateBuilder implements TemplateBuilder {
  private record Field(@NotNull SmartPsiElementPointer<PsiElement> element, @NotNull Expression expression) {
  }

  private final List<Field> myFields = new ArrayList<>();

  @Override
  public void replaceElement(@NotNull PsiElement element, @NlsSafe String replacementText) {
    replaceElement(element, new ConstantNode(replacementText));
  }

  @Override
  public void replaceElement(@NotNull PsiElement element, Expression expression) {
    myFields.add(new Field(SmartPointerManager.createPointer(element), expression));
  }

  @Override
  public void replaceElement(@NotNull PsiElement element, TextRange rangeWithinElement, String replacementText) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceElement(@NotNull PsiElement element, TextRange rangeWithinElement, Expression expression) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceElement(PsiElement element, @NlsSafe String varName, Expression expression, boolean alwaysStopAt) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceElement(PsiElement element, @NlsSafe String varName, String dependantVariableName, boolean alwaysStopAt) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceRange(TextRange rangeWithinElement, String replacementText) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void replaceRange(TextRange rangeWithinElement, Expression expression) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void runNonInteractively(boolean inline) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void run(@NotNull Editor editor, boolean inline) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TemplateBuilder setScrollToTemplate(boolean scrollToTemplate) {
    return this;
  }

  /**
   * Starts the template with the fields. When there is no field, it puts the caret on the name of the new class.
   *
   * @param updater  the updater of the action
   * @param aClass   the new class
   * @param endAfter the element after which the template puts the caret, or null when the template chooses the
   *                 position itself
   */
  void startTemplate(@NotNull ModPsiUpdater updater, @NotNull PsiClass aClass, @Nullable PsiElement endAfter) {
    if (myFields.isEmpty()) {
      updater.moveCaretTo(ObjectUtils.notNull(aClass.getNameIdentifier(), aClass));
      return;
    }
    ModTemplateBuilder builder = updater.templateBuilder();
    if (endAfter != null) {
      // The new class can be in another file. Move the caret first, so the updater tracks that file.
      updater.moveCaretTo(endAfter);
      builder.finishAt(endAfter.getTextRange().getEndOffset());
    }
    flushTo(builder);
  }

  /**
   * Writes the fields into the builder. It skips the field of an element which is not valid anymore.
   *
   * @param builder the builder which gets the fields
   */
  private void flushTo(@NotNull ModTemplateBuilder builder) {
    for (Field field : myFields) {
      PsiElement element = field.element().getElement();
      if (element != null) {
        builder.field(element, field.expression());
      }
    }
  }
}

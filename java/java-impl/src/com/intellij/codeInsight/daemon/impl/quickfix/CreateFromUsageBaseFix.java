// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.CodeInsightUtil;
import com.intellij.codeInsight.CodeInsightUtilCore;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateEditingListener;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInspection.util.IntentionName;
import com.intellij.java.syntax.parser.JavaKeywords;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.Segment;
import com.intellij.psi.JVMElementFactories;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiSwitchLabelStatement;
import com.intellij.psi.PsiSwitchStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.PsiTypeParameterList;
import com.intellij.psi.codeStyle.JavaCodeStyleSettings;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.VisibilityUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

public abstract class CreateFromUsageBaseFix extends BaseIntentionAction {

  @Override
  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile psiFile) {
    return getAvailableText(project, editor.getCaretModel().getOffset()) != null;
  }

  /**
   * @param project the project of the file
   * @param offset  the offset of the caret
   * @return the text of the fix when the fix applies at the offset, or null when it does not apply
   */
  protected final @IntentionName @Nullable String getAvailableText(@NotNull Project project, int offset) {
    PsiElement element = getElement();
    if (element == null || isValidElement(element)) {
      return null;
    }

    if (!isAvailableImpl(offset)) {
      return null;
    }

    List<PsiClass> targetClasses = filterTargetClasses(
      getTargetClasses(element, false, _ -> false), project);
    if (targetClasses.isEmpty()) {
      return null;
    }
    return getText();
  }

  protected abstract boolean isAvailableImpl(int offset);

  protected abstract boolean isValidElement(PsiElement result);

  /**
   * @param classes the target classes
   * @param project the project of the classes
   * @return the classes in a language which can get a new member
   */
  static @Unmodifiable List<PsiClass> filterTargetClasses(@NotNull List<PsiClass> classes, @NotNull Project project) {
    return ContainerUtil.filter(classes, psiClass -> JVMElementFactories.getFactory(psiClass.getLanguage(), project) != null);
  }

  protected abstract @Nullable PsiElement getElement();

  protected static void setupVisibility(PsiClass parentClass, @NotNull PsiClass targetClass, PsiModifierList list) throws IncorrectOperationException {
    if (targetClass.isInterface() && list.getFirstChild() != null) {
      list.deleteChildRange(list.getFirstChild(), list.getLastChild());
      return;
    }
    if (targetClass.isInterface()) {
      return;
    }
    final String visibility = getVisibility(parentClass, targetClass);
    if (VisibilityUtil.ESCALATE_VISIBILITY.equals(visibility)) {
      list.setModifierProperty(PsiModifier.PRIVATE, true);
      VisibilityUtil.escalateVisibility(list, parentClass);
    } else {
      VisibilityUtil.setVisibility(list, visibility);
    }
  }

  @PsiModifier.ModifierConstant
  protected static String getVisibility(PsiClass parentClass, @NotNull PsiClass targetClass) {
    if (parentClass != null && (parentClass.equals(targetClass) || PsiTreeUtil.isAncestor(targetClass, parentClass, true))) {
      return PsiModifier.PRIVATE;
    } else {
      return JavaCodeStyleSettings.getInstance(targetClass.getContainingFile()).VISIBILITY;
    }
  }

  public static boolean shouldCreateStaticMember(PsiReferenceExpression ref, PsiClass targetClass) {

    PsiExpression qualifierExpression = ref.getQualifierExpression();
    while (qualifierExpression instanceof PsiParenthesizedExpression expression) {
      qualifierExpression = expression.getExpression();
    }

    if (qualifierExpression instanceof PsiReferenceExpression referenceExpression) {
      return referenceExpression.resolve() instanceof PsiClass;
    } else if (qualifierExpression != null) {
      return false;
    } else if (ref instanceof PsiMethodReferenceExpression) {
      return true;
    }
    else {
      assert PsiTreeUtil.isAncestor(targetClass, ref, true);
      PsiModifierListOwner owner = PsiTreeUtil.getParentOfType(ref, PsiModifierListOwner.class);
      if (owner instanceof PsiMethod method && method.isConstructor()) {
        //usages inside delegating constructor call
        PsiExpression run = ref;
        while (run.getParent() instanceof PsiExpression) {
          run = (PsiExpression)run.getParent();
        }
        if (run.getParent() instanceof PsiExpressionList &&
          run.getParent().getParent() instanceof PsiMethodCallExpression) {
          @NonNls String calleeText = ((PsiMethodCallExpression)run.getParent().getParent()).getMethodExpression().getText();
          if (calleeText.equals(JavaKeywords.THIS) || calleeText.equals(JavaKeywords.SUPER)) return true;
        }
      }

      while (owner != null && owner != targetClass) {
        if (owner.hasModifierProperty(PsiModifier.STATIC)) return true;
        owner = PsiTreeUtil.getParentOfType(owner, PsiModifierListOwner.class);
      }
    }

    return false;
  }

  private static @Nullable PsiExpression getQualifier (PsiElement element) {
    if (element instanceof PsiNewExpression newExpression) {
      PsiJavaCodeReferenceElement ref = newExpression.getClassReference();
      if (ref instanceof PsiReferenceExpression expression) {
        return expression.getQualifierExpression();
      }
    } else if (element instanceof PsiReferenceExpression referenceExpression) {
      return referenceExpression.getQualifierExpression();
    } else if (element instanceof PsiMethodCallExpression expression) {
      return expression.getMethodExpression().getQualifierExpression();
    }

    return null;
  }

  public static @NotNull PsiSubstitutor getTargetSubstitutor(@Nullable PsiElement element) {
    if (element instanceof PsiNewExpression expression) {
      PsiJavaCodeReferenceElement reference = expression.getClassOrAnonymousClassReference();
      JavaResolveResult result = reference == null ? JavaResolveResult.EMPTY : reference.advancedResolve(false);
      return result.getSubstitutor();
    }

    PsiExpression qualifier = getQualifier(element);
    if (qualifier != null) {
      PsiType type = qualifier.getType();
      if (type instanceof PsiClassType classType) {
        return classType.resolveGenerics().getSubstitutor();
      }
    }

    return PsiSubstitutor.EMPTY;
  }

  /**
   * @param element               the element which needs a new member
   * @param allowOuterTargetClass whether the outer classes of the class around the element can get the member
   * @param canBeTargetClass      whether a super class of the target class can get the member
   * @return the valid project classes which can get the member
   */
  static @NotNull List<PsiClass> getTargetClasses(PsiElement element,
                                                  boolean allowOuterTargetClass,
                                                  @NotNull Predicate<? super PsiClass> canBeTargetClass) {
    PsiClass psiClass = null;
    PsiExpression qualifier = null;

    if (element instanceof PsiNameValuePair) {
      final PsiAnnotation annotation = PsiTreeUtil.getParentOfType(element, PsiAnnotation.class);
      if (annotation != null) {
        PsiJavaCodeReferenceElement nameRef = annotation.getNameReferenceElement();
        if (nameRef == null) {
          return Collections.emptyList();
        }
        else {
          final PsiElement resolve = nameRef.resolve();
          if (resolve instanceof PsiClass aClass) {
            return Collections.singletonList(aClass);
          }
          else {
            return Collections.emptyList();
          }
        }
      }
    }
    if (element instanceof PsiNewExpression newExpression) {
      PsiJavaCodeReferenceElement ref = newExpression.getClassOrAnonymousClassReference();
      if (ref != null) {
        PsiElement refElement = ref.resolve();
        if (refElement instanceof PsiClass cls) {
          psiClass = cls;
        } else if (ref.getQualifier() instanceof PsiJavaCodeReferenceElement refQualifier) {
          refElement = refQualifier.resolve();
          if (refElement instanceof PsiClass cls) {
            psiClass = cls;
          }
        }
      }
      qualifier = newExpression.getQualifier();
    }
    else if (element instanceof PsiReferenceExpression psiReferenceExpression) {
      qualifier = psiReferenceExpression.getQualifierExpression();
      if (qualifier == null && element instanceof PsiMethodReferenceExpression referenceExpression) {
        final PsiTypeElement qualifierTypeElement = referenceExpression.getQualifierType();
        if (qualifierTypeElement != null) {
          psiClass = PsiUtil.resolveClassInType(qualifierTypeElement.getType());
        }
      } else if (qualifier == null) {
        final PsiElement parent = element.getParent();
        if (parent instanceof PsiSwitchLabelStatement) {
          final PsiSwitchStatement switchStatement = PsiTreeUtil.getParentOfType(parent, PsiSwitchStatement.class);
          if (switchStatement != null) {
            final PsiExpression expression = switchStatement.getExpression();
            if (expression != null) {
              psiClass = PsiUtil.resolveClassInClassTypeOnly(expression.getType());
            }
          }
        }
      }
    }
    else if (element instanceof PsiMethodCallExpression call) {
      final PsiReferenceExpression methodExpression = call.getMethodExpression();
      qualifier = methodExpression.getQualifierExpression();
      final @NonNls String referenceName = methodExpression.getReferenceName();
      if (referenceName == null) return Collections.emptyList();
    }
    boolean allowOuterClasses = false;
    if (qualifier != null) {
      PsiType type = qualifier.getType();
      if (type instanceof PsiClassType classType) {
        psiClass = classType.resolve();
      }

      if (qualifier instanceof PsiJavaCodeReferenceElement referenceElement) {
        final PsiElement resolved = referenceElement.resolve();
        if (resolved instanceof PsiClass aClass) {
          if (psiClass == null) psiClass = aClass;
        }
      }
    } else if (psiClass == null) {
      psiClass = PsiTreeUtil.getParentOfType(element, PsiClass.class);
      allowOuterClasses = true;
    }

    if (psiClass instanceof PsiTypeParameter) {
      PsiClass[] supers = psiClass.getSupers();
      List<PsiClass> filtered = new ArrayList<>();
      for (PsiClass aSuper : supers) {
        if (!canModify(aSuper)) continue;
        if (!(aSuper instanceof PsiTypeParameter)) filtered.add(aSuper);
      }
      return filtered;
    }
    else {
      if (psiClass == null || !canModify(psiClass)) {
        return Collections.emptyList();
      }

      if (!allowOuterClasses || !allowOuterTargetClass) {
        final ArrayList<PsiClass> classes = new ArrayList<>();
        collectSupers(psiClass, classes, canBeTargetClass);
        return classes;
      }

      List<PsiClass> result = new ArrayList<>();

      while (psiClass != null) {
        result.add(psiClass);
        if (psiClass.hasModifierProperty(PsiModifier.STATIC)) break;
        psiClass = PsiTreeUtil.getParentOfType(psiClass, PsiClass.class);
      }
      return result;
    }
  }

  private static void collectSupers(PsiClass psiClass, ArrayList<? super PsiClass> classes,
                                    @NotNull Predicate<? super PsiClass> canBeTargetClass) {
    classes.add(psiClass);

    final PsiClass[] supers = psiClass.getSupers();
    for (PsiClass aSuper : supers) {
      if (classes.contains(aSuper)) continue;
      if (canBeTargetClass.test(aSuper)) {
        collectSupers(aSuper, classes, canBeTargetClass);
      }
    }
  }

  public static void startTemplate(@NotNull Editor editor, final Template template, final @NotNull Project project) {
    startTemplate(editor, template, project, null);
  }

  protected static void startTemplate(final @NotNull Editor editor,
                                      final Template template,
                                      final @NotNull Project project,
                                      final TemplateEditingListener listener) {
    startTemplate(editor, template, project, listener, null);
  }

  public static void startTemplate(final @NotNull Editor editor,
                                   final Template template,
                                   final @NotNull Project project,
                                   final TemplateEditingListener listener,
                                   final @NlsContexts.Command String commandName) {
    Runnable runnable = () -> TemplateManager.getInstance(project).startTemplate(editor, template, listener);
    if (!ApplicationManager.getApplication().isWriteIntentLockAcquired() || IntentionPreviewUtils.isIntentionPreviewActive()) {
      runnable.run();
    } else {
      CommandProcessor.getInstance().executeCommand(project, runnable, commandName, commandName);
    }
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }

  static void setupGenericParameters(PsiClass targetClass, PsiJavaCodeReferenceElement ref) {
    int numParams = ref.getTypeParameters().length;
    if (numParams == 0) return;
    final PsiElementFactory factory = JavaPsiFacade.getElementFactory(ref.getProject());
    final Set<String> typeParamNames = new HashSet<>();
    for (PsiType type : ref.getTypeParameters()) {
      final PsiClass psiClass = PsiUtil.resolveClassInType(type);
      if (psiClass instanceof PsiTypeParameter) {
        typeParamNames.add(psiClass.getName());
      }
    }
    int idx = 0;
    PsiTypeParameterList typeParameterList = Objects.requireNonNull(targetClass.getTypeParameterList());
    for (PsiType type : ref.getTypeParameters()) {
      final PsiClass psiClass = PsiUtil.resolveClassInType(type);
      if (psiClass instanceof PsiTypeParameter) {
        typeParameterList.add(factory.createTypeParameterFromText(psiClass.getName(), null));
      } else {
        while (true) {
          final @NonNls String paramName = idx > 0 ? "T" + idx : "T";
          if (typeParamNames.add(paramName)) {
            typeParameterList.add(factory.createTypeParameterFromText(paramName, null));
            break;
          }
          idx++;
        }
      }
    }
  }

  public static void startTemplate(@NotNull Project project, @NotNull PsiClass aClass, @NotNull Template template, @Nls @NotNull String text) {
    aClass = CodeInsightUtilCore.forcePsiPostprocessAndRestoreElement(aClass);
    template.setToReformat(true);

    final Editor editor = CodeInsightUtil.positionCursor(project, Objects.requireNonNull(aClass).getContainingFile(), aClass);
    if (editor == null) return;

    Segment textRange = aClass.getTextRange();
    editor.getDocument().deleteString(textRange.getStartOffset(), textRange.getEndOffset());
    startTemplate(editor, template, project, null, text);
  }
}

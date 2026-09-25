// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiMethodReferenceExpression;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.infos.MethodCandidateInfo;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Narrows the class candidates of a type reference by what the code does with that type.
 */
final class ImportClassUsageNarrowing {
  private ImportClassUsageNarrowing() {
  }

  static @NotNull Collection<PsiClass> narrow(@NotNull Collection<PsiClass> candidates, @NotNull PsiJavaCodeReferenceElement reference) {
    return narrowByStaticMembers(narrowByUsedMembers(narrowByReturnedType(candidates, reference), reference), reference);
  }

  private static @NotNull Collection<PsiClass> narrowByStaticMembers(@NotNull Collection<PsiClass> candidates,
                                                                     @NotNull PsiJavaCodeReferenceElement reference) {
    if (candidates.size() < 2) return candidates;
    String name = reference.getReferenceName();
    if (name == null) return candidates;

    List<PsiReferenceExpression> usedMembers = new ArrayList<>();
    for (PsiReferenceExpression expression : PsiTreeUtil.findChildrenOfType(reference.getContainingFile(), PsiReferenceExpression.class)) {
      if (!(expression.getQualifierExpression() instanceof PsiReferenceExpression qualifier)) continue;
      if (qualifier.isQualified() || !name.equals(qualifier.getReferenceName()) || qualifier.resolve() != null) continue;
      if (mayInheritField(qualifier)) continue;
      usedMembers.add(expression);
    }
    if (usedMembers.isEmpty()) return candidates;

    List<PsiClass> narrowed = ContainerUtil.filter(candidates, candidate -> ContainerUtil.and(usedMembers, member -> hasMember(candidate, member, true)));
    return narrowed.isEmpty() ? candidates : narrowed;
  }

  private static boolean mayInheritField(@NotNull PsiElement place) {
    for (PsiClass psiClass = PsiTreeUtil.getParentOfType(place, PsiClass.class); psiClass != null;
         psiClass = PsiTreeUtil.getParentOfType(psiClass, PsiClass.class)) {
      if (ContainerUtil.exists(psiClass.getExtendsListTypes(), type -> type.resolve() == null)) return true;
      if (ContainerUtil.exists(psiClass.getImplementsListTypes(), type -> type.resolve() == null)) return true;
      if (psiClass instanceof PsiAnonymousClass anonymous && anonymous.getBaseClassType().resolve() == null) return true;
    }
    return false;
  }

  private static @NotNull Collection<PsiClass> narrowByUsedMembers(@NotNull Collection<PsiClass> candidates,
                                                                   @NotNull PsiJavaCodeReferenceElement reference) {
    if (candidates.size() < 2) return candidates;
    if (!(reference.getParent() instanceof PsiTypeElement typeElement)) return candidates;
    if (!(typeElement.getParent() instanceof PsiVariable variable)) return candidates;

    List<PsiReferenceExpression> usedMembers = membersUsedOn(variable);
    if (usedMembers.isEmpty()) return candidates;

    List<PsiClass> narrowed = ContainerUtil.filter(candidates, candidate -> ContainerUtil.and(usedMembers, member -> hasMember(candidate, member, false)));
    return narrowed.isEmpty() ? candidates : narrowed;
  }

  private static @NotNull List<PsiReferenceExpression> membersUsedOn(@NotNull PsiVariable variable) {
    PsiElement scope = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class, PsiClass.class);
    if (scope == null) return List.of();

    List<PsiReferenceExpression> members = new ArrayList<>();
    for (PsiReferenceExpression expression : PsiTreeUtil.findChildrenOfType(scope, PsiReferenceExpression.class)) {
      if (!(expression.getQualifierExpression() instanceof PsiReferenceExpression qualifier)) continue;
      if (qualifier.resolve() != variable) continue;
      if (expression.getReferenceName() != null) members.add(expression);
    }
    return members;
  }

  private static boolean hasMember(@NotNull PsiClass psiClass, @NotNull PsiReferenceExpression member, boolean staticOnly) {
    String memberName = member.getReferenceName();
    if (memberName == null) return true;
    PsiMethodCallExpression call = member.getParent() instanceof PsiMethodCallExpression parent && parent.getMethodExpression() == member
                                   ? parent : null;
    if (call == null) {
      PsiField field = psiClass.findFieldByName(memberName, true);
      if (field != null && isStaticIfNeeded(field, staticOnly)) return true;
      if (psiClass.findInnerClassByName(memberName, true) != null) return true;
    }
    boolean staticMethodOnly = staticOnly && !(member instanceof PsiMethodReferenceExpression);
    return ContainerUtil.exists(psiClass.findMethodsByName(memberName, true), method ->
      isStaticIfNeeded(method, staticMethodOnly) && (call == null || acceptsArguments(method, call)));
  }

  private static boolean isStaticIfNeeded(@NotNull PsiMember member, boolean staticOnly) {
    return !staticOnly || member.hasModifierProperty(PsiModifier.STATIC);
  }

  private static boolean acceptsArguments(@NotNull PsiMethod method, @NotNull PsiMethodCallExpression call) {
    PsiType[] argumentTypes = call.getArgumentList().getExpressionTypes();
    if (ArrayUtil.contains(null, argumentTypes)) return true;
    PsiSubstitutor substitutor = JavaPsiFacade.getElementFactory(method.getProject()).createRawSubstitutor(method);
    return PsiUtil.getApplicabilityLevel(method, substitutor, argumentTypes, PsiUtil.getLanguageLevel(call))
           != MethodCandidateInfo.ApplicabilityLevel.NOT_APPLICABLE;
  }

  private static @NotNull Collection<PsiClass> narrowByReturnedType(@NotNull Collection<PsiClass> candidates,
                                                                    @NotNull PsiJavaCodeReferenceElement reference) {
    if (candidates.size() < 2) return candidates;
    List<PsiType> returnedTypes = returnedTypesOf(reference);
    if (returnedTypes.isEmpty()) return candidates;

    PsiElementFactory factory = JavaPsiFacade.getElementFactory(reference.getProject());
    List<PsiClass> narrowed = ContainerUtil.filter(candidates, candidate -> {
      PsiClassType candidateType = factory.createType(candidate);
      return ContainerUtil.and(returnedTypes, returnedType -> TypeConversionUtil.isAssignable(candidateType, returnedType));
    });
    return narrowed.isEmpty() ? candidates : narrowed;
  }

  private static @NotNull List<PsiType> returnedTypesOf(@NotNull PsiJavaCodeReferenceElement reference) {
    if (!(reference.getParent() instanceof PsiTypeElement typeElement)) return List.of();
    if (!(typeElement.getParent() instanceof PsiMethod method)) return List.of();
    if (method.getReturnTypeElement() != typeElement) return List.of();
    PsiCodeBlock body = method.getBody();
    if (body == null) return List.of();

    List<PsiType> types = new ArrayList<>();
    for (PsiReturnStatement statement : PsiTreeUtil.findChildrenOfType(body, PsiReturnStatement.class)) {
      if (PsiTreeUtil.getParentOfType(statement, PsiMethod.class, PsiLambdaExpression.class) != method) continue;
      PsiExpression returned = statement.getReturnValue();
      PsiType type = returned == null ? null : returned.getType();
      if (type == null || PsiTypes.nullType().equals(type)) continue;
      types.add(type);
    }
    return types;
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Narrows the class candidates of a type reference by what the code does with that type.
 */
final class ImportClassUsageNarrowing {
  private ImportClassUsageNarrowing() {
  }

  static @NotNull Collection<PsiClass> narrow(@NotNull Collection<PsiClass> candidates, @NotNull PsiJavaCodeReferenceElement reference) {
    return narrowByUsedMembers(narrowByReturnedType(candidates, reference), reference);
  }

  private static @NotNull Collection<PsiClass> narrowByUsedMembers(@NotNull Collection<PsiClass> candidates,
                                                                   @NotNull PsiJavaCodeReferenceElement reference) {
    if (candidates.size() < 2) return candidates;
    if (!(reference.getParent() instanceof PsiTypeElement typeElement)) return candidates;
    if (!(typeElement.getParent() instanceof PsiVariable variable)) return candidates;

    Set<String> usedMembers = membersUsedOn(variable);
    if (usedMembers.isEmpty()) return candidates;

    List<PsiClass> narrowed = ContainerUtil.filter(candidates, candidate -> ContainerUtil.and(usedMembers, member -> hasMember(candidate, member)));
    return narrowed.isEmpty() ? candidates : narrowed;
  }

  private static @NotNull Set<String> membersUsedOn(@NotNull PsiVariable variable) {
    PsiElement scope = PsiTreeUtil.getParentOfType(variable, PsiCodeBlock.class, PsiClass.class);
    if (scope == null) return Set.of();

    Set<String> members = new HashSet<>();
    for (PsiReferenceExpression expression : PsiTreeUtil.findChildrenOfType(scope, PsiReferenceExpression.class)) {
      if (!(expression.getQualifierExpression() instanceof PsiReferenceExpression qualifier)) continue;
      if (qualifier.resolve() != variable) continue;
      String member = expression.getReferenceName();
      if (member != null) members.add(member);
    }
    return members;
  }

  private static boolean hasMember(@NotNull PsiClass psiClass, @NotNull String memberName) {
    if (psiClass.findFieldByName(memberName, true) != null) return true;
    if (psiClass.findInnerClassByName(memberName, true) != null) return true;
    return psiClass.findMethodsByName(memberName, true).length != 0;
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

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.psi.impl.auxiliary.annotation;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiAnnotationOwner;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiNameValuePair;
import com.intellij.psi.PsiQualifiedReference;
import com.intellij.psi.PsiReference;
import com.intellij.psi.StubBasedPsiElement;
import com.intellij.psi.impl.PsiImplUtil;
import com.intellij.psi.impl.light.LightClassReference;
import com.intellij.psi.impl.source.tree.java.PsiAnnotationImpl;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.util.ArrayUtil;
import com.intellij.util.PairFunction;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.config.GroovyConfigUtils;
import org.jetbrains.plugins.groovy.lang.parser.GroovyEmptyStubElementTypes;
import org.jetbrains.plugins.groovy.lang.parser.GroovyStubElementTypes;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementVisitor;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElementFactory;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation.GrAnnotation;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation.GrAnnotationArgumentList;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrField;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrLoopStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariableDeclaration;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.params.GrParameter;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.typedef.members.GrMethod;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.imports.GrImportStatement;
import org.jetbrains.plugins.groovy.lang.psi.api.toplevel.packaging.GrPackageDefinition;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrCodeReferenceElement;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeElement;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeParameter;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrStubElementBase;
import org.jetbrains.plugins.groovy.lang.psi.stubs.GrAnnotationStub;
import org.jetbrains.plugins.groovy.lang.resolve.ResolveUtil;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import static org.jetbrains.plugins.groovy.lang.resolve.imports.GroovyImports.getAliasedFullyQualifiedNames;

/**
 * @author Dmitry.Krasilschikov
 */
public class GrAnnotationImpl extends GrStubElementBase<GrAnnotationStub> implements GrAnnotation, StubBasedPsiElement<GrAnnotationStub> {

  private static final Set<TargetType> DEFAULT_TARGETS = Collections.unmodifiableSet(EnumSet.of(
    TargetType.PACKAGE, TargetType.TYPE, TargetType.ANNOTATION_TYPE, TargetType.FIELD, TargetType.METHOD, TargetType.CONSTRUCTOR,
    TargetType.PARAMETER, TargetType.LOCAL_VARIABLE, TargetType.MODULE, TargetType.RECORD_COMPONENT));
  private static final PairFunction<Project, String, PsiAnnotation> ANNOTATION_CREATOR =
    (project, text) -> GroovyPsiElementFactory.getInstance(project).createAnnotationFromText(text);

  public GrAnnotationImpl(@NotNull ASTNode node) {
    super(node);
  }

  public GrAnnotationImpl(GrAnnotationStub stub) {
    super(stub, GroovyStubElementTypes.ANNOTATION);
  }

  @Override
  public void accept(@NotNull GroovyElementVisitor visitor) {
    visitor.visitAnnotation(this);
  }

  @Override
  public String toString() {
    return "Annotation";
  }

  @Override
  public @NotNull GrAnnotationArgumentList getParameterList() {
    return getRequiredStubOrPsiChild(GroovyEmptyStubElementTypes.ANNOTATION_ARGUMENT_LIST);
  }

  @Override
  public @Nullable @NonNls String getQualifiedName() {
    final GrAnnotationStub stub = getStub();
    if (stub != null) {
      return stub.getPsiElement().getQualifiedName();
    }

    return getClassReference().resolve() instanceof PsiClass aClass ? aClass.getQualifiedName() : null;
  }

  @Override
  public @Nullable PsiJavaCodeReferenceElement getNameReferenceElement() {
    final GroovyResolveResult resolveResult = getClassReference().advancedResolve();
    if (!(resolveResult.getElement() instanceof PsiClass aClass)) return null;

    return new LightClassReference(getManager(), getClassReference().getText(), aClass, resolveResult.getSubstitutor());
  }

  @Override
  public @Nullable PsiAnnotationMemberValue findAttributeValue(@Nullable String attributeName) {
    return PsiImplUtil.findAttributeValue(this, attributeName);
  }

  @Override
  public @Nullable PsiAnnotationMemberValue findDeclaredAttributeValue(@NonNls String attributeName) {
    return PsiImplUtil.findDeclaredAttributeValue(this, attributeName);
  }

  @Override
  public <T extends PsiAnnotationMemberValue> T setDeclaredAttributeValue(@Nullable @NonNls String attributeName, T value) {
    //noinspection unchecked
    return (T)PsiImplUtil.setDeclaredAttributeValue(this, attributeName, value, ANNOTATION_CREATOR);
  }

  @Override
  public @NotNull GrCodeReferenceElement getClassReference() {
    final GrAnnotationStub stub = getStub();
    return stub != null ? stub.getPsiElement().getClassReference() : findNotNullChildByClass(GrCodeReferenceElement.class);
  }

  @Override
  public @NotNull String getShortName() {
    final GrAnnotationStub stub = getStub();
    if (stub != null) {
      return PsiAnnotationImpl.getAnnotationShortName(stub.getText());
    }

    final String referenceName = getClassReference().getReferenceName();
    assert referenceName != null;
    return referenceName;
  }

  @Override
  public @Nullable PsiAnnotationOwner getOwner() {
    return getParent() instanceof PsiAnnotationOwner owner ? owner : null;
  }

  @Override
  public boolean hasQualifiedName(@NotNull String qualifiedName) {
    return mayHaveQualifiedName(qualifiedName) && qualifiedName.equals(getQualifiedName());
  }

  private boolean mayHaveQualifiedName(@NotNull String qualifiedName) {
    String shortName = getShortName();
    return shortName.equals(StringUtil.getShortName(qualifiedName)) ||
           getAliasedFullyQualifiedNames(this, shortName).contains(qualifiedName);
  }

  public static TargetType @NotNull [] getApplicableElementTypeFields(PsiElement owner) {
    if (owner instanceof PsiClass aClass) {
      if (aClass.isAnnotationType()) {
        return addTypeUseIfApplicable(owner, TargetType.ANNOTATION_TYPE, TargetType.TYPE);
      }
      else if (aClass instanceof GrTypeParameter) {
        return addTypeUseIfApplicable(owner, TargetType.TYPE_PARAMETER);
      }
      else {
        return addTypeUseIfApplicable(owner, TargetType.TYPE);
      }
    }
    if (owner instanceof GrMethod method) {
      return addTypeUseIfApplicable(owner, method.isConstructor() ? TargetType.CONSTRUCTOR : TargetType.METHOD);
    }
    if (owner instanceof GrVariableDeclaration declaration) {
      final GrVariable[] variables = declaration.getVariables();
      if (variables.length == 0) {
        return TargetType.EMPTY_ARRAY;
      }
      return variables[0] instanceof GrField || ResolveUtil.isScriptField(variables[0])
             ? addTypeUseIfApplicable(owner, TargetType.FIELD)
             : addTypeUseIfApplicable(owner, TargetType.LOCAL_VARIABLE);
    }
    if (owner instanceof GrParameter) {
      return addTypeUseIfApplicable(owner, TargetType.PARAMETER);
    }
    if (owner instanceof GrPackageDefinition) {
      return new TargetType[]{TargetType.PACKAGE};
    }
    if (owner instanceof GrTypeElement) {
      return new TargetType[]{TargetType.TYPE_USE};
    }
    if (owner instanceof GrCodeReferenceElement) {
      return new TargetType[]{TargetType.TYPE_USE};
    }
    if (GroovyConfigUtils.isAtLeastGroovy60(owner)) {
      if (owner instanceof GrImportStatement) {
        return new TargetType[]{TargetType.IMPORT};
      }
      if (owner instanceof GrLoopStatement) {
        return new TargetType[]{TargetType.LOOP};
      }
    }

    return TargetType.EMPTY_ARRAY;
  }

  private static TargetType[] addTypeUseIfApplicable(@NotNull PsiElement element, TargetType @NotNull ... baseTargetTypeArray) {
    return GroovyConfigUtils.isAtLeastGroovy40(element) ? ArrayUtil.append(baseTargetTypeArray, TargetType.TYPE_USE) : baseTargetTypeArray;
  }

  public static boolean isAnnotationApplicableTo(GrAnnotation annotation, TargetType @NotNull ... elementTypeFields) {
    if (elementTypeFields.length != 0) {
      PsiClass annotationType = annotation.resolveAnnotationType();
      if (annotationType != null) {
        return findAnnotationTarget(annotationType, elementTypeFields) != null;
      }
    }

    return true;
  }

  public static @Nullable TargetType findAnnotationTarget(@NotNull PsiClass annotationType, TargetType @NotNull ... types) {
    if (types.length != 0) {
      Set<TargetType> targets = getAnnotationTargets(annotationType);
      if (targets != null) {
        for (TargetType type : types) {
          if (type != TargetType.UNKNOWN && targets.contains(type)) {
            return type;
          }
        }
        return null;
      }
    }

    return TargetType.UNKNOWN;
  }

  private static @Nullable Set<TargetType> getAnnotationTargets(@NotNull PsiClass annotationType) {
    if (!annotationType.isAnnotationType()) return null;
    PsiModifierList modifierList = annotationType.getModifierList();
    if (modifierList == null) return null;

    return CachedValuesManager.getCachedValue(modifierList, () ->
      CachedValueProvider.Result.create(calcAnnotationTargets(modifierList), PsiModificationTracker.MODIFICATION_COUNT));
  }

  private static @NotNull Set<TargetType> calcAnnotationTargets(PsiModifierList modifierList) {
    PsiAnnotation target = modifierList.findAnnotation(CommonClassNames.JAVA_LANG_ANNOTATION_TARGET);
    PsiAnnotation extendedTarget = modifierList.findAnnotation("groovy.lang.annotation.ExtendedTarget");
    if (target == null && extendedTarget == null) {
      return DEFAULT_TARGETS;  // if omitted it is applicable to all but TYPE_USE, TYPE_PARAMETERS, IMPORT and LOOP targets
    }

    Set<TargetType> targets = EnumSet.noneOf(TargetType.class);
    if (target != null) {
      PsiNameValuePair attribute = AnnotationUtil.findDeclaredAttribute(target, null);
      if (attribute == null) return targets;
      extractRequiredAnnotationTargets(attribute.getDetachedValue(), targets);
    }
    if (extendedTarget != null) {
      PsiNameValuePair attribute = AnnotationUtil.findDeclaredAttribute(extendedTarget, null);
      if (attribute == null) return targets;
      extractRequiredAnnotationTargets(attribute.getDetachedValue(), targets);
    }
    return targets;
  }

  private static void extractRequiredAnnotationTargets(@Nullable PsiAnnotationMemberValue value, Set<TargetType> targets) {
    if (value instanceof PsiReference ref) {
      ContainerUtil.addIfNotNull(targets, translateTargetRef(ref));
    }
    else if (value instanceof PsiArrayInitializerMemberValue memberValue) {
      for (PsiAnnotationMemberValue initializer : memberValue.getInitializers()) {
        if (initializer instanceof PsiReference ref) {
          ContainerUtil.addIfNotNull(targets, translateTargetRef(ref));
        }
      }
    }
  }

  private static @Nullable TargetType translateTargetRef(@NotNull PsiReference reference) {
    if (reference instanceof PsiQualifiedReference ref) {
      String name = ref.getReferenceName();
      if (name != null) {
        try {
          return TargetType.valueOf(name);
        }
        catch (IllegalArgumentException _) {}
      }
    }

    return null;
  }
}

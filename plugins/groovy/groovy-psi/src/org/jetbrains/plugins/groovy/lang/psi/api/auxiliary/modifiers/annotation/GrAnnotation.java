// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.modifiers.annotation;

import com.intellij.psi.PsiAnnotation;
import com.intellij.util.ArrayFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.api.auxiliary.GrCondition;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrCodeReferenceElement;

/**
 * @author Dmitry.Krasilschikov
 */
public interface GrAnnotation extends GrCondition, PsiAnnotation, GrAnnotationMemberValue {

  GrAnnotation[] EMPTY_ARRAY = new GrAnnotation[0];
  ArrayFactory<GrAnnotation> ARRAY_FACTORY = count -> count == 0 ? EMPTY_ARRAY : new GrAnnotation[count];

  @NotNull
  GrCodeReferenceElement getClassReference();

  @NotNull
  String getShortName();

  @Override
  @NotNull
  GrAnnotationArgumentList getParameterList();

  enum TargetType {
    // see java.lang.annotation.ElementType and groovy.lang.annotation.ExtendedElementType
    TYPE, FIELD, METHOD, PARAMETER, CONSTRUCTOR, LOCAL_VARIABLE, ANNOTATION_TYPE, PACKAGE, TYPE_USE, TYPE_PARAMETER, MODULE, RECORD_COMPONENT,
    IMPORT, LOOP,
    UNKNOWN;

    public static final TargetType[] EMPTY_ARRAY = {};
  }
}

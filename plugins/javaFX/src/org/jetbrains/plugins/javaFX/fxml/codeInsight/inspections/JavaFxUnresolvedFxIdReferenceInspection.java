// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.javaFX.fxml.codeInsight.inspections;

import com.intellij.codeInspection.IntentionWrapper;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.XmlSuppressableInspectionTool;
import com.intellij.lang.LanguageNamesValidation;
import com.intellij.lang.jvm.JvmModifier;
import com.intellij.lang.jvm.actions.AnnotationRequest;
import com.intellij.lang.jvm.actions.AnnotationRequestsKt;
import com.intellij.lang.jvm.actions.CreateFieldRequest;
import com.intellij.lang.jvm.actions.ExpectedType;
import com.intellij.lang.jvm.actions.ExpectedTypesKt;
import com.intellij.lang.jvm.actions.FieldRequestsKt;
import com.intellij.lang.jvm.actions.JvmElementActionFactories;
import com.intellij.lang.jvm.util.JvmUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJvmSubstitutor;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.XmlElementVisitor;
import com.intellij.psi.codeStyle.JavaCodeStyleSettings;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.VisibilityUtil;
import com.intellij.xml.XmlElementDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.javaFX.JavaFXBundle;
import org.jetbrains.plugins.javaFX.fxml.FxmlConstants;
import org.jetbrains.plugins.javaFX.fxml.JavaFxCommonNames;
import org.jetbrains.plugins.javaFX.fxml.JavaFxFileTypeFactory;
import org.jetbrains.plugins.javaFX.fxml.JavaFxPsiUtil;
import org.jetbrains.plugins.javaFX.fxml.descriptors.JavaFxBuiltInTagDescriptor;
import org.jetbrains.plugins.javaFX.fxml.descriptors.JavaFxClassTagDescriptorBase;
import org.jetbrains.plugins.javaFX.fxml.refs.JavaFxFieldIdReferenceProvider;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class JavaFxUnresolvedFxIdReferenceInspection extends XmlSuppressableInspectionTool {
  @Override
  public @NotNull PsiElementVisitor buildVisitor(final @NotNull ProblemsHolder holder,
                                                 final boolean isOnTheFly,
                                                 @NotNull LocalInspectionToolSession session) {
    if (!JavaFxFileTypeFactory.isFxml(session.getFile())) return PsiElementVisitor.EMPTY_VISITOR;

    return new XmlElementVisitor() {
      @Override
      public void visitXmlAttribute(@NotNull XmlAttribute attribute) {
        super.visitXmlAttribute(attribute);
        if (FxmlConstants.FX_ID.equals(attribute.getName())) {
          final XmlAttributeValue valueElement = attribute.getValueElement();
          if (valueElement != null && valueElement.getTextLength() > 0) {
            final PsiClass controllerClass = JavaFxPsiUtil.getControllerClass(attribute.getContainingFile());
            if (controllerClass != null) {
              final PsiReference reference = valueElement.getReference();
              if (reference instanceof JavaFxFieldIdReferenceProvider.JavaFxControllerFieldRef ref && ref.isUnresolved()) {
                final PsiClass fieldClass =
                  checkContext(ref.getXmlAttributeValue());
                if (fieldClass != null) {
                  final String text = reference.getCanonicalText();
                  boolean validName = LanguageNamesValidation.isIdentifier(fieldClass.getLanguage(), text, fieldClass.getProject());
                  holder.registerProblem(reference.getElement(), reference.getRangeInElement(), JavaFXBundle.message("inspection.javafx.unresolved.fx.id.reference.problem"),
                                         isOnTheFly && validName ?
                                         createFixes(ref, holder.getFile()) : LocalQuickFix.EMPTY_ARRAY);
                }
              }
            }
          }
        }
      }
    };
  }

  private static LocalQuickFix @NotNull [] createFixes(JavaFxFieldIdReferenceProvider.JavaFxControllerFieldRef reference, PsiFile file) {
    
    @PsiModifier.ModifierConstant
    String visibility = JavaCodeStyleSettings.getInstance(file).VISIBILITY;
   
    Collection<AnnotationRequest> annotations; 
    if (!PsiModifier.PUBLIC.equals(visibility)) {
      annotations = Collections.singletonList(AnnotationRequestsKt.annotationRequest(JavaFxCommonNames.JAVAFX_FXML_ANNOTATION));
    }
    else {
      annotations = Collections.emptyList();
    }

    JvmModifier modifier = JvmUtil.getAccessModifier(VisibilityUtil.getAccessLevel(visibility));
    List<ExpectedType> expectedTypes = ExpectedTypesKt.expectedTypes(JavaPsiFacade.getElementFactory(file.getProject()).createType(checkContext(reference.getXmlAttributeValue())), ExpectedType.Kind.SUBTYPE);
    CreateFieldRequest request = FieldRequestsKt.fieldRequest(reference.getCanonicalText(), 
                                                              annotations, 
                                                              Collections.singletonList(modifier),
                                                              expectedTypes,
                                                              new PsiJvmSubstitutor(file.getProject(), PsiSubstitutor.EMPTY), null, false);
    return IntentionWrapper.wrapToQuickFixes(JvmElementActionFactories.createAddFieldActions(reference.getAClass(), request), file).toArray(LocalQuickFix.EMPTY_ARRAY);
  }

  private static PsiClass checkContext(final XmlAttributeValue attributeValue) {
    if (attributeValue == null) return null;
    final PsiElement parent = attributeValue.getParent();
    if (parent instanceof XmlAttribute attribute) {
      return checkClass(attribute.getParent());
    }
    return null;
  }

  private static PsiClass checkClass(XmlTag tag) {
    if (tag != null) {
      final XmlElementDescriptor descriptor = tag.getDescriptor();
      if (descriptor instanceof JavaFxClassTagDescriptorBase) {
        final PsiElement declaration = descriptor.getDeclaration();
        if (declaration instanceof PsiClass aClass) {
          return aClass;
        }
      } else if (descriptor instanceof JavaFxBuiltInTagDescriptor) {
        final XmlTag includedRoot = JavaFxBuiltInTagDescriptor.getIncludedRoot(tag);
        if (includedRoot != null && !includedRoot.equals(tag)) {
          return checkClass(includedRoot);
        }
      }
    }
    return null;
  }
}

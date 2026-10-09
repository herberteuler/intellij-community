// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInspection;

import com.intellij.codeInsight.Nullability;
import com.intellij.codeInsight.NullabilitySource;
import com.intellij.codeInsight.TypeNullability;
import com.intellij.codeInspection.dataFlow.NullabilityProblemKind;
import com.intellij.lang.ASTNode;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.SyntaxTraverser;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.ref.GCUtil;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static org.junit.Assert.assertNotEquals;

/**
 * Checks {@link NullabilityProblemKind#sameNullability}.
 * <p>
 * The stub of a reference list builds its type annotations from text. The copy of an annotation made by
 * {@link #copyFromText} is the same kind of PSI instance.
 */
public final class SameNullabilityTest extends LightJavaCodeInsightFixtureTestCase {
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    myFixture.addClass("""
      package foo;

      import java.lang.annotation.*;

      @Target(ElementType.TYPE_USE)
      public @interface Nullable {
        String value() default "";
      }
      """);
    myFixture.configureByText("A.java", """
      import org.jetbrains.annotations.*;

      @NotNullByDefault
      class A<T extends @Nullable Object, U extends @foo.Nullable Object> {}

      @NotNullByDefault
      class B {}
      """);
  }

  public void testStubAndAst() {
    PsiFileImpl file = (PsiFileImpl)myFixture.addFileToProject("Stubbed.java", """
      import org.jetbrains.annotations.*;

      class Stubbed {
        <T extends @Nullable Object> T get() { return null; }
      }
      """);
    PsiMethod method = ((PsiJavaFile)file).getClasses()[0].getMethods()[0];
    TypeNullability fromStub = method.getReturnType().getNullability();
    assertFalse(file.isContentsLoaded());

    ASTNode node = file.getNode();
    GCUtil.tryGcSoftlyReachableObjects();
    assertTrue(file.withGreenStubTreeOrAst(_ -> false, _ -> true));
    TypeNullability fromAst = method.getReturnType().getNullability();

    assertEquals(Nullability.NULLABLE, fromStub.nullability());
    assertNotEquals(fromStub, fromAst);
    assertTrue(NullabilityProblemKind.sameNullability(fromStub, fromAst));
    assertNotNull(node);
  }

  public void testExplicitAnnotation() {
    PsiAnnotation original = findAnnotation("@Nullable", 0);
    PsiAnnotation copy = copyFromText(original, original.getText());
    TypeNullability fromOriginal = explicit(Nullability.NULLABLE, original).inherited();
    TypeNullability fromCopy = explicit(Nullability.NULLABLE, copy).inherited();
    assertNotEquals(fromOriginal, fromCopy);
    assertTrue(NullabilityProblemKind.sameNullability(fromOriginal, fromCopy));

    assertFalse(NullabilityProblemKind.sameNullability(fromOriginal, explicit(Nullability.NOT_NULL, copy).inherited()));
    assertFalse(NullabilityProblemKind.sameNullability(fromOriginal, explicit(Nullability.NULLABLE, copy)));
    assertFalse(NullabilityProblemKind.sameNullability(fromOriginal,
                                                       explicit(Nullability.NULLABLE, findAnnotation("@foo.Nullable", 0)).inherited()));
  }

  public void testAttributes() {
    PsiAnnotation original = findAnnotation("@foo.Nullable", 0);
    TypeNullability first = explicit(Nullability.NULLABLE, copyFromText(original, "@foo.Nullable(\"first\")"));
    TypeNullability named = explicit(Nullability.NULLABLE, copyFromText(original, "@foo.Nullable(value = \"first\")"));
    TypeNullability second = explicit(Nullability.NULLABLE, copyFromText(original, "@foo.Nullable(\"second\")"));
    TypeNullability defaultValue = explicit(Nullability.NULLABLE, copyFromText(original, "@foo.Nullable(\"\")"));
    assertTrue(NullabilityProblemKind.sameNullability(first, named));
    assertFalse(NullabilityProblemKind.sameNullability(first, second));
    assertTrue(NullabilityProblemKind.sameNullability(explicit(Nullability.NULLABLE, original), defaultValue));
  }

  public void testContainerAndMultiSource() {
    PsiAnnotation onA = findAnnotation("@NotNullByDefault", 0);
    PsiAnnotation onB = findAnnotation("@NotNullByDefault", 1);
    assertTrue(NullabilityProblemKind.sameNullability(container(onA), container(onA)));
    assertFalse(NullabilityProblemKind.sameNullability(container(onA), container(onB)));

    PsiAnnotation jetBrains = findAnnotation("@Nullable", 0);
    PsiAnnotation foo = findAnnotation("@foo.Nullable", 0);
    TypeNullability originals = multi(jetBrains, foo);
    assertTrue(NullabilityProblemKind.sameNullability(originals, multi(copyFromText(foo, foo.getText()),
                                                                       copyFromText(jetBrains, jetBrains.getText()))));
    assertFalse(NullabilityProblemKind.sameNullability(originals, multi(jetBrains, copyFromText(foo, "@foo.Nullable(\"other\")"))));
  }

  private @NotNull PsiAnnotation findAnnotation(@NotNull String text, int index) {
    List<PsiAnnotation> annotations = SyntaxTraverser.psiTraverser(myFixture.getFile())
      .filter(PsiAnnotation.class)
      .filter(annotation -> annotation.getText().equals(text))
      .toList();
    return annotations.get(index);
  }

  /** Creates the annotation from text in the context of {@code original}, the way a source stub does. */
  private @NotNull PsiAnnotation copyFromText(@NotNull PsiAnnotation original, @NotNull String text) {
    return JavaPsiFacade.getElementFactory(getProject()).createAnnotationFromText(text, original);
  }

  private static @NotNull TypeNullability explicit(@NotNull Nullability nullability, @NotNull PsiAnnotation annotation) {
    return new TypeNullability(nullability, new NullabilitySource.ExplicitAnnotation(annotation));
  }

  private static @NotNull TypeNullability multi(@NotNull PsiAnnotation first, @NotNull PsiAnnotation second) {
    return new TypeNullability(Nullability.NULLABLE, NullabilitySource.multiSource(
      List.of(new NullabilitySource.ExplicitAnnotation(first), new NullabilitySource.ExplicitAnnotation(second))));
  }

  private static @NotNull TypeNullability container(@NotNull PsiAnnotation annotation) {
    return new TypeNullability(Nullability.NOT_NULL, new NullabilitySource.ContainerAnnotation(annotation));
  }
}

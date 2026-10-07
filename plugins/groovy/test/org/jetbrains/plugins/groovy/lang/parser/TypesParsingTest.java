// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.parser;

public class TypesParsingTest extends GroovyParsingTestCase {
  @Override
  public String getTestDataPath() {
    return super.getTestDataPath() + "types";
  }

  public void testAnn_def1() { doTest(); }
  public void testAnn_def2() { doTest(); }
  public void testAnn_def3() { doTest(); }

  public void testIdentifierAfterAnnotationMethod() { doTest(); }

  public void testDefault1() { doTest(); }
  public void testDefault2() { doTest(); }

  public void testType1() { doTest(); }
  public void testType2() { doTest(); }
  public void testType3() { doTest(); }
  public void testType4() { doTest(); }
  public void testType5() { doTest(); }
  public void testType6() { doTest(); }
  public void testType7() { doTest(); }
  public void testType8() { doTest(); }
  public void testType9() { doTest(); }
  public void testType10() { doTest(); }
  public void testType11() { doTest(); }
  public void testType12() { doTest(); }
  public void testType13() { doTest(); }
  public void testType14() { doTest(); }
  public void testType15() { doTest(); }
  public void testType16() { doTest(); }
  public void testType17() { doTest(); }

  public void testIdentifierInsteadOfImplements() { doTest(); }

  public void testInnerEnum() { doTest(); }

  public void testNewlineBeforeClassBrace() { doTest(); }
  public void testNewLineBeforeClassBraceAfterExtends() { doTest(); }
  public void testNewLineBeforeClassBraceAfterImplements() { doTest(); }
  public void testNewlineBeforeExtends() { doTest(); }
  public void testNewLineAfterMethodModifiers() { doTest(); }
  public void testNewLineAfterLAngleInTypeArgumentList() { doTest(); }
  public void testNewLineBeforeRAngleInTypeArgumentList() { doTest(); }
  public void testNewLineBetweenTypeArguments() { doTest(); }
  public void testNewLineBetweenTypeArgumentsError() { doTest(); }
  public void testNewLineBetweenExtendsImplements() { doTest(); }

  public void testStaticInitializer() { doTest(); }

  public void testInterfaceWithGroovyDoc() { doTest(); }

  public void testIncorrectParam1() { doTest(); }
  public void testIncorrectParameter2() { doTest(); }
  public void testIncorrectParam3() { doTest(); }

  public void testEmptyTypeArgs() { doTest(); }

  public void testIncompleteConstructor() { doTest(); }

  public void testWeakKeywordType1() { doTest(); }

  public void testWeakKeywordType2() { doTest(); }

  public void testMembers$identifierOnly() { doTest(); }
  public void testMembers$capitalIdentifierOnly() { doTest(); }
  public void testMembers$constructorIdentifierOnly() { doTest(); }
  public void testMembers$modifierListOnly() { doTest(); }
  public void testMembers$modifierListAndIdentifier() { doTest(); }
  public void testMembers$modifierListAndCapitalIdentifier() { doTest(); }
  public void testMembers$modifierListAndConstructorIdentifier() { doTest(); }
  public void testMembers$modifierListAndPrimitive() { doTest(); }
  public void testMembers$modifierListAndRefQualified() { doTest(); }
  public void testMembers$modifierListAndRefTypeArgs() { doTest(); }
  public void testMembers$modifierListAndTypeParameters() { doTest(); }
  public void testMembers$modifierListTypeParametersAndIdentifier() { doTest(); }
  public void testMembers$modifierListTypeParametersAndCapitalIdentifier() { doTest(); }
  public void testMembers$modifierListTypeParametersAndConstructorIdentifier() { doTest(); }
  public void testMembers$modifierListTypeParametersAndPrimitive() { doTest(); }
  public void testMembers$modifierListTypeParametersAndRefQualified() { doTest(); }
  public void testMembers$modifierListTypeParametersAndRefTypeArgs() { doTest(); }
  public void testMembers$modifierListTypeParametersIdentifierAndLeftParen() { doTest(); }
  public void testMembers$modifierListTypeParametersCapitalIdentifierAndLeftParen() { doTest(); }
  public void testMembers$modifierListTypeParametersConstructorIdentifierAndLeftParen() { doTest(); }
  public void testMembers$capitalIdentifierAndLeftParen() { doTest(); }
  public void testMembers$constructorIdentifierAndLeftParen() { doTest(); }
  public void testMembers$identifierAndLeftParen() { doTest(); }
  public void testMembers$constructorAfterInnerClass() { doTest(); }
  public void testMembers$varField1() { doTest(); }
  public void testMembers$varField2() { doTest(); }
  public void testMembers$varField3() { doTest(); }
  public void testMembers$varField4() { doTest(); }
  public void testMembers$varField5() { doTest(); }
  public void testMembers$varField6() { doTest(); }
  public void testMembers$varField7() { doTest(); }
  public void testMembers$varField8() { doTest(); }

  public void testLowercaseTypeElement() { doTest(); }
}

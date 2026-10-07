// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.parser;

public class ExpressionsParsingTest extends GroovyParsingTestCase {
  @Override
  public String getTestDataPath() {
    return super.getTestDataPath() + "expressions";
  }

  public void testArguments$carg1() { doTest(); }
  public void testArguments$carg2() { doTest(); }
  public void testArguments$carg3() { doTest(); }
  public void testArguments$cargs1() { doTest(); }
  public void testArguments$cargs2() { doTest(); }
  public void testArguments$cargs3() { doTest(); }
  public void testArguments$cargs4() { doTest(); }
  public void testArguments$cargs5() { doTest(); }
  public void testArguments$cargs6() { doTest(); }

  public void testArithmetic$add1() { doTest(); }
  public void testArithmetic$add2() { doTest(); }
  public void testArithmetic$addbug1() { doTest(); }
  public void testArithmetic$arif1() { doTest(); }
  public void testArithmetic$mul1() { doTest(); }
  public void testArithmetic$mul2() { doTest(); }
  public void testArithmetic$mul3() { doTest(); }
  public void testArithmetic$mul4() { doTest(); }
  public void testArithmetic$post1() { doTest(); }
  public void testArithmetic$sh1() { doTest(); }
  public void testArithmetic$shift5() { doTest(); }
  public void testArithmetic$shift6() { doTest(); }
  public void testArithmetic$newLineInsideParentheses() { doTest(); }
  public void testArithmetic$newLineInsideIfStatement() { doTest(); }
  public void testArithmetic$un1() { doTest(); }

  public void testAss1() { doTest(); }
  public void testAss2() { doTest(); }
  public void testAss3() { doTest(); }

  public void testClosures$appended() { doTest(); }
  public void testClosures$closparam1() { doTest(); }
  public void testClosures$closparam2() { doTest(); }
  public void testClosures$closparam3() { doTest(); }
  public void testClosures$closparam4() { doTest(); }
  public void testClosures$closparam5() { doTest(); }
  public void testClosures$closparam6() { doTest(); }
  public void testClosures$final_error() { doTest(); }
  public void testClosures$param6() { doTest(); }
  public void testClosures$param7() { doTest(); }
  public void testClosures$withDefaultParam1() { doTest(); }
  public void testClosures$withDefaultParam2() { doTest(); }
  public void _testClosures$withDefaultParam3() { doTest(); }
  public void _testClosures$withDefaultParam4() { doTest(); }
  public void testClosures$withDefaultParam5() { doTest(); }

  public void testConditional$con1() { doTest(); }
  public void testConditional$con2() { doTest(); }
  public void testConditional$elvis1() { doTest(); }
  public void testConditional$elvis2() { doTest(); }
  public void testConditional$elvisNlBeforeOperator() { doTest(); }
  public void testConditional$ternaryQuestionOnly() { doTest(); }
  public void testConditional$ternaryWithoutElse() { doTest(); }
  public void testConditional$ternaryWithoutThen() { doTest(); }
  public void testConditional$ternaryWithoutThenElse() { doTest(); }
  public void testConditional$ternaryNLBeforeColon() { doTest(); }
  public void testConditional$ternaryNLBeforeElse() { doTest(); }
  public void testConditional$ternaryNLBeforeQuestion() { doTest(); }
  public void testConditional$ternaryNLBeforeThen() { doTest(); }

  public void testErrors$err_final() { doTest(); }

  public void testGstring$daniel_sun() { doTest(); }
  public void testGstring$gravy16532() { doTest("gstring/gravy-1653-2.test"); }
  public void testGstring$grvy1653() { doTest("gstring/grvy-1653.test"); }

  public void testGstring$gstr3() { doTest(); }
  public void testGstring$standTrooper() { doTest(); }
  public void testGstring$str1() { doTest(); }
  public void testGstring$str2() { doTest(); }
  public void testGstring$str3() { doTest(); }
  public void testGstring$str4() { doTest(); }
  public void testGstring$str5() { doTest(); }
  public void testGstring$str6() { doTest(); }
  public void testGstring$str7() { doTest(); }
  public void testGstring$str8() { doTest(); }
  public void testGstring$str9() { doTest(); }

  public void testString$singleQuoted() { doTest(); }
  public void testString$tripleSingleQuoted$ok() { doTest(); }
  public void testString$tripleSingleQuoted$err0() { doTest(); }
  public void testString$tripleSingleQuoted$err1() { doTest(); }
  public void testString$tripleSingleQuoted$err2() { doTest(); }
  public void testString$tripleSingleQuoted$err3() { doTest(); }
  public void testString$tripleSingleQuoted$err4() { doTest(); }
  public void testString$tripleSingleQuoted$err5() { doTest(); }
  public void testString$tripleSingleQuoted$unfinished0() { doTest(); }
  public void testString$tripleSingleQuoted$unfinished1() { doTest(); }
  public void testString$tripleSingleQuoted$unfinished2() { doTest(); }
  public void testString$tripleSingleQuoted$unfinished3() { doTest(); }

  public void testGstring$str_error1() { doTest(); }
  public void testGstring$str_error2() { doTest(); }
  public void testGstring$str_error3() { doTest(); }
  public void testGstring$str_error4() { doTest(); }
  public void testGstring$str_error5() { doTest(); }
  public void testGstring$str_error6() { doTest(); }
  public void testGstring$str_error7() { doTest(); }
  public void testGstring$str_error8() { doTest(); }
  public void testGstring$triple$triple1() { doTest(); }
  public void testGstring$triple$triple2() { doTest(); }
  public void testGstring$triple$triple3() { doTest(); }
  public void testGstring$triple$triple4() { doTest(); }
  public void testGstring$triple$quote_and_slash() { doTest(); }
  public void testGstring$ugly_lexer() { doTest(); }
  public void testGstring$this() { doTest(); }
  public void testGstring$newline() { doTest(); }

  public void testMapLiteral() { doTest(); }

  public void testMapLiteralNewline() { doTest(); }

  public void testMapKeys() { doTest(); }

  public void testNamedArgumentKeys() { doTest(); }

  public void testExpressionlabelWithoutExpression() { doTest(); }

  public void testNew$arr_decl() { doTest(); }
  public void testNew$emptyTypeArgs() { doTest(); }
  public void testNew$noArgumentList() { doTest(); }
  public void testNew$emptyArrayInitializer() { doTest(); }
  public void testNew$arrayInitializer() { doTest(); }
  public void testNew$arrayInitializerTrailingComma() { doTest(); }
  public void testNew$nlInsideArrayDeclaration() { doTest(); }
  public void testNew$nlBeforeArrayDeclaration() { doTest(); }
  public void testNew$nlInsideArrayDeclarationWithExpression() { doTest(); }
  public void testNew$nestedArrayInitializer() { doTest(); }
  public void testNew$emptyNestedArrayInitializer() { doTest(); }
  public void testNew$nestedArrayInitializerWithClosure() { doTest(); }
  public void testNew$nestedArrayInitializerWithLambda() { doTest(); }
  public void testNew$nestedArrayInitializerCommaAfterLastElement() { doTest(); }
  public void testNew$nestedArrayInitializerNewLinesInside() { doTest(); }
  public void testNew$noInitializer() { doTest(); }
  public void testNew$noClosingBrace() { doTest(); }
  public void testNew$closureAfterArrayDeclaration() { doTest(); }
  public void testNew$closureAfterArrayInitializer() { doTest(); }
  public void testNew$newLine() { doTest(); }
  public void testNew$newLine2() { doTest(); }
  public void testNew$newLine3() { doTest(); }
  public void testNew$newLine4() { doTest(); }
  public void testNew$newLine5() { doTest(); }
  public void testNew$newLine6() { doTest(); }
  public void testNew$newLine7() { doTest(); }
  public void testNew$newLine8() { doTest(); }
  public void testNew$newLine9() { doTest(); }
  public void testNew$newLine10() { doTest(); }
  public void testNew$newLine11() { doTest(); }
  public void testNew$newLine12() { doTest(); }
  public void testNew$newLine13() { doTest(); }
  public void testNew$newLine14() { doTest(); }
  public void testNew$newLine15() { doTest(); }
  public void testNew$newLine16() { doTest(); }
  public void testNew$newLine17() { doTest(); }
  public void testNew$newLine18() { doTest(); }
  public void testNew$newLine19() { doTest(); }
  public void testNew$newLine20() { doTest(); }
  public void testNew$newLine21() { doTest(); }
  public void testNew$newLine22() { doTest(); }
  public void testNew$newLine23() { doTest(); }
  public void testNew$newLine24() { doTest(); }

  public void testAnonymous$anonymous() { doTest(); }
  public void testAnonymous$anonymous1() { doTest(); }
  public void testAnonymous$anonymous2() { doTest(); }
  public void testAnonymous$anonymous3() { doTest(); }
  public void testAnonymous$anonymous4() { doTest(); }
  public void testAnonymous$anonymous5() { doTest(); }
  public void testAnonymous$anonymous6() { doTest(); }
  public void testAnonymous$anonymous7() { doTest(); }
  public void testAnonymous$anonymous8() { doTest(); }
  public void testAnonymous$anonymous9() { doTest(); }
  public void testAnonymous$anonymous10() { doTest(); }
  public void testAnonymous$anonymous11() { doTest(); }
  public void testAnonymous$anonymous12() { doTest(); }
  public void testAnonymous$anonymous13() { doTest(); }
  public void testAnonymous$anonymous14() { doTest(); }
  public void testAnonymous$anonymous15() { doTest(); }
  public void testAnonymous$anonymous16() { doTest(); }
  public void testAnonymous$anonymous17() { doTest(); }
  public void testAnonymous$newlineBeforeBodyInCall() { doTest(); }
  public void testAnonymous$newLineInsideParentheses() { doTest(); }
  public void testAnonymous$newLineInsideIfStatement() { doTest(); }

  public void testNumbers() { doTest(); }

  public void testParenthed$exprInParenth() { doTest(); }
  public void testParenthed$paren1() { doTest(); }
  public void testParenthed$paren2() { doTest(); }
  public void testParenthed$paren3() { doTest(); }
  public void testParenthed$paren4() { doTest(); }
  public void testParenthed$paren5() { doTest(); }
  public void testParenthed$paren6() { doTest(); }
  public void testParenthed$newLineAfterOpeningParenthesis() { doTest(); }
  public void testParenthed$newLineBeforeClosingParenthesis() { doTest(); }
  public void testParenthed$capitalNamedArgument() { doTest(); }
  public void testParenthed$capitalListArgument() { doTest(); }

  public void testPath$method$ass4() { doTest(); }
  public void testPath$method$clazz1() { doTest(); }
  public void testPath$method$clazz2() { doTest(); }
  public void testPath$method$clos1() { doTest(); }
  public void testPath$method$clos2() { doTest(); }
  public void testPath$method$clos3() { doTest(); }
  public void testPath$method$clos4() { doTest(); }
  public void testPath$method$ind1() { doTest(); }
  public void testPath$method$ind2() { doTest(); }
  public void testPath$method$ind3() { doTest(); }
  public void testPath$method$method1() { doTest(); }
  public void testPath$method$method10() { doTest(); }
  public void testPath$method$method11() { doTest(); }
  public void testPath$method$method12() { doTest(); }
  public void testPath$method$method13() { doTest(); }
  public void testPath$method$method2() { doTest(); }
  public void testPath$method$method3() { doTest(); }
  public void testPath$method$method4() { doTest(); }
  public void testPath$method$method5() { doTest(); }
  public void testPath$method$method6() { doTest(); }
  public void testPath$method$method7() { doTest(); }
  public void testPath$method$method8() { doTest(); }
  public void testPath$method$method9() { doTest(); }
  public void testPath$method$newLineBeforeOperatorInCall() { doTest(); }
  public void testPath$method$method14() { doTest(); }
  public void testPath$method$method15() { doTest(); }
  public void testPath$path1() { doTest(); }
  public void testPath$path13() { doTest(); }
  public void testPath$path14() { doTest(); }
  public void testPath$path15() { doTest(); }
  public void testPath$path2() { doTest(); }
  public void testPath$path4() { doTest(); }
  public void testPath$path5() { doTest(); }
  public void testPath$path6() { doTest(); }
  public void testPath$path7() { doTest(); }
  public void testPath$path8() { doTest(); }
  public void testPath$path9() { doTest(); }
  public void testPath$path16() { doTest(); }
  public void testPath$path17() { doTest(); }
  public void testPath$path18() { doTest(); }
  public void testPath$path19() { doTest(); }
  public void testPath$regexp() { doTest(); }
  public void testPath$typeVsExpr() { doTest(); }
  public void testPath$stringMethodCall1() { doTest(); }
  public void testPath$stringMethodCall2() { doTest(); }
  public void testPath$stringMethodCall3() { doTest(); }

  public void testReferences$ref1() { doTest(); }
  public void testReferences$ref2() { doTest(); }
  public void testReferences$ref3() { doTest(); }
  public void testReferences$ref4() { doTest(); }
  public void testReferences$ref5() { doTest(); }
  public void testReferences$ref6() { doTest(); }
  public void testReferences$ref7() { doTest(); }
  public void testReferences$ref8() { doTest(); }
  public void testReferences$ref9() { doTest(); }
  public void testReferences$ref10() { doTest(); }
  public void testReferences$ref11() { doTest(); }
  public void testReferences$ref12() { doTest(); }
  public void testReferences$keywords() { doTest(); }
  public void testReferences$emptyTypeArgs() { doTest(); }
  public void testReferences$dots() { doTest(); }

  public void testRegex$chen() { doTest(); }
  public void testRegex$GRVY1509err() { doTest("regex/GRVY-1509err.test"); }
  public void testRegex$GRVY1509norm() { doTest("regex/GRVY-1509norm.test"); }
  public void testRegex$GRVY1509test() { doTest("regex/GRVY-1509test.test"); }
  public void testRegex$regex1() { doTest(); }
  public void testRegex$regex10() { doTest(); }
  public void testRegex$regex11() { doTest(); }
  public void testRegex$regex12() { doTest(); }
  public void testRegex$regex13() { doTest(); }
  public void testRegex$regex14() { doTest(); }
  public void testRegex$regex15() { doTest(); }
  public void testRegex$regex16() { doTest(); }
  public void testRegex$regex17() { doTest(); }
  public void testRegex$regex18() { doTest(); }
  public void testRegex$regex19() { doTest(); }
  public void testRegex$regex2() { doTest(); }
  public void testRegex$regex20() { doTest(); }
  public void testRegex$regex21() { doTest(); }
  public void testRegex$regex22() { doTest(); }
  public void testRegex$regex23() { doTest(); }
  public void testRegex$regex24() { doTest(); }
  public void testRegex$regex25() { doTest(); }
  public void testRegex$regex3() { doTest(); }
  public void testRegex$regex33() { doTest(); }
  public void testRegex$regex4() { doTest(); }
  public void testRegex$regex5() { doTest(); }
  public void testRegex$regex6() { doTest(); }
  public void testRegex$regex7() { doTest(); }
  public void testRegex$regex8() { doTest(); }
  public void testRegex$regex9() { doTest(); }
  public void testRegex$regex_begin() { doTest(); }
  public void testRegex$regex_begin2() { doTest(); }
  public void testRegex$slashyEq() { doTest(); }
  public void testRegex$multiLineSlashy() { doTest(); }
  public void testRegex$dollarSlashy() { doTest(); }
  public void testRegex$dollarSlashy2() { doTest(); }
  public void testRegex$dollarSlashy3() { doTest(); }
  public void testRegex$dollarSlashy4() { doTest(); }
  public void testRegex$dollarSlashy5() { doTest(); }
  public void testRegex$dollarSlashy6() { doTest(); }
  public void testRegex$dollarSlashy7() { doTest(); }
  public void testRegex$dollarSlashy8() { doTest(); }
  public void testRegex$dollarSlashy9() { doTest(); }
  public void testRegex$dollarSlashy10() { doTest(); }
  public void testRegex$dollarSlashy11() { doTest(); }
  public void testRegex$dollarSlashyCode() { doTest(); }
  public void testRegex$dollarSlashyCodeUnfinished() { doTest(); }
  public void testRegex$dollarSlashyEof() { doTest(); }
  public void testRegex$dollarSlashyRegex() { doTest(); }
  public void testRegex$dollarSlashyRegexFinishedTwice() { doTest(); }
  public void testRegex$dollarSlashyRegexUnfinished() { doTest(); }
  public void testRegex$dollarSlashyUnfinished() { doTest(); }
  public void testRegex$dollarSlashyWindowsPaths() { doTest(); }
  public void testRegex$dollarSlashyXml() { doTest(); }
  public void testRegex$dollarSlashyDouble() { doTest(); }
  public void testRegex$dollarSlashyTriple() { doTest(); }
  public void testRegex$dollarSlashyUltimate() { doTest(); }
  public void testRegex$afterNewLine() { doTest(); }
  public void testRegex$afterDollarSlashyString() { doTest(); }
  public void testRegex$afterDoubleQuotedString() { doTest(); }
  public void testRegex$afterSingleQuotedString() { doTest(); }
  public void testRegex$afterSlashyString() { doTest(); }
  public void testRegex$afterTripleSingleQuotedString() { doTest(); }
  public void testRegex$afterTripleDoubleQuotedString() { doTest(); }

  public void testRelational$eq1() { doTest(); }
  public void testRelational$inst0() { doTest(); }
  public void testRelational$inst1() { doTest(); }
  public void testRelational$inst2() { doTest(); }
  public void testRelational$rel1() { doTest(); }
  public void testRelational$newlineAfterOperator() { doTest(); }
  public void testRelational$noRValue() { doTest(); }
  public void testRelational$exclamationAfterExpression() { doTest(); }
  public void testRelational$inNegated() { doTest(); }
  public void testRelational$inNegatedWithSpace() { doTest(); }
  public void testRelational$inNegatedIdentifier() { doTest(); }
  public void testRelational$instanceOfNegated() { doTest(); }
  public void testRelational$instanceOfNegatedWithSpace() { doTest(); }
  public void testRelational$instanceOfNegatedIdentifier() { doTest(); }

  public void testSpecial$grvy1173() { doTest(); }
  public void testSpecial$list1() { doTest(); }
  public void testSpecial$list2() { doTest(); }
  public void testSpecial$list3() { doTest(); }
  public void testSpecial$map1() { doTest(); }
  public void testSpecial$map2() { doTest(); }
  public void testSpecial$map3() { doTest(); }
  public void testSpecial$map4() { doTest(); }
  public void testSpecial$map5() { doTest(); }
  public void testSpecial$map6() { doTest(); }
  public void testSpecial$map7() { doTest(); }
  public void testSpecial$map8() { doTest(); }
  public void testSpecial$paren13() { doTest(); }

  public void testTypecast$castToObject() { doTest(); }
  public void testTypecast$una1() { doTest(); }
  public void testTypecast$una2() { doTest(); }
  public void testTypecast$una3() { doTest(); }
  public void testTypecast$una4() { doTest(); }
  public void testTypecast$una5() { doTest(); }
  public void testTypecast$una6() { doTest(); }
  public void testTypecast$elvis() { doTest(); }
  public void testTypecast$equality() { doTest(); }
  public void testTypecast$parenthesized() { doTest(); }
  public void testTypecast$noExpression() { doTest(); }
  public void testTypecast$parenthesizedOperand() { doTest(); }
  public void testTypecast$parenthesizedQualifier() { doTest(); }
  public void testTypecast$parenthesizedOperandError() { doTest(); }
  public void testTypecast$nested() { doTest(); }
  public void testTypecast$vsMethodCall() { doTest(); }
  public void testTypecast$conditional() { doTest(); }

  public void testAtHang() { doTest(); }

  public void testDollar() { doTest(); }

  public void testNoArrowClosure() { doTest(); }

  public void testNoArrowClosure2() { doTest(); }

  public void testPropertyAccessError() { doTest(); }

  public void testThis$qualifiedThis() { doTest(); }

  public void testSuper$qualifiedSuper() { doTest(); }

  public void testThis$this() { doTest(); }

  public void testSuper$super() { doTest(); }

  public void testBinary$implicationSimple() { doTest(); }
  public void testBinary$implicationWithNewLineAfter() { doTest(); }
  public void testBinary$implicationWithNewLineBefore() { doTest(); }
  public void testBinary$implicationRightAssociativity() { doTest(); }
  public void testBinary$implicationLowPriority() { doTest(); }
  public void testBinary$identity() { doTest(); }
  public void testBinary$elvisAssign() { doTest(); }
  public void testBinary$elvisAssignNewLine() { doTest(); }
  public void testBinary$elvisAssignWithoutRValue() { doTest(); }
  public void testBinary$assignmentError() { doTest(); }

  public void testCommandExpr$closureArg() { doTest(); }
  public void testCommandExpr$simple() { doTest(); }
  public void testCommandExpr$callArg1() { doTest(); }
  public void testCommandExpr$callArg2() { doTest(); }
  public void testCommandExpr$threeArgs1() { doTest(); }
  public void testCommandExpr$threeArgs2() { doTest(); }
  public void testCommandExpr$threeArgs3() { doTest(); }
  public void testCommandExpr$fourArgs() { doTest(); }
  public void testCommandExpr$fiveArgs() { doTest(); }
  public void testCommandExpr$multiArgs() { doTest(); }
  public void testCommandExpr$RHS() { doTest(); }
  public void testCommandExpr$oddArgCount() { doTest(); }
  public void testCommandExpr$indexAccess1() { doTest(); }
  public void testCommandExpr$indexAccess2() { doTest(); }
  public void testCommandExpr$indexAccess3() { doTest(); }
  public void testCommandExpr$indexAccess4() { doTest(); }
  public void testCommandExpr$closureArg2() { doTest(); }
  public void testCommandExpr$closureArg3() { doTest(); }
  public void testCommandExpr$closureArg4() { doTest(); }
  public void testCommandExpr$closureArg5() { doTest(); }
  public void testCommandExpr$not() { doTest(); }
  public void testCommandExpr$methodCall() { doTest(); }
  public void testCommandExpr$indexProperty() { doTest(); }
  public void testCommandExpr$instanceof() { doTest(); }
  public void testCommandExpr$instanceof2() { doTest(); }
  public void testCommandExpr$in() { doTest(); }
  public void testCommandExpr$as() { doTest(); }
  public void testCommandExpr$arrayAccess() { doTest(); }
  public void testCommandExpr$keywords() { doTest(); }
  public void testCommandExpr$literalInvoked() { doTest(); }
  public void testCommandExpr$literalInvokedWithUnfinishedLiteral() { doTest(); }
  public void testCommandExpr$slashyInvoked() { doTest(); }
  public void testCommandExpr$safeIndex() { doTest(); }
  public void testCommandExpr$safeIndexEmpty() { doTest(); }
  public void testCommandExpr$safeIndexEmptyMap() { doTest(); }
  public void testCommandExpr$safeIndexLBrack() { doTest(); }
  public void testCommandExpr$safeIndexMap() { doTest(); }

  public void testDiamond() { doTest(); }

  public void testDiamondErrors() { doTest(); }

  public void testSpacesInStringAfterSlash() { doTest(); }

  public void testDiamondInPathRefElement() { doTest(); }

  public void testNewMethodName() { doTest(); }

  public void testRefElementsWithKeywords() { doTest(); }

  public void test_finish_argument_list_on_keyword_occurrence() { doTest("finishArgumentListOnKeywordOccurrence.test"); }

  public void testConditionalExpressionWithLineFeed() { doTest(); }

  public void testSpecial$mapHang() { doTest(); }

  public void testIndexpropertyWithUnfinishedInvokedExpression() { doTest(); }

  public void testIndex$safeIndex() { doTest(); }
  public void testIndex$safeIndexEmpty() { doTest(); }
  public void testIndex$safeIndexEmptyMap() { doTest(); }
  public void testIndex$safeIndexLBrack() { doTest(); }
  public void testIndex$safeIndexMap() { doTest(); }
  public void testIndex$safeIndexNoRBrack() { doTest(); }
  public void testIndex$safeIndexVsTernary() { doTest(); }
  public void testIndex$safeIndexVsTernary2() { doTest(); }
  public void testIndex$safeIndexVsTernary3() { doTest(); }
  public void testIndex$safeIndexVsTernary4() { doTest(); }
  public void testIndex$safeIndexVsTernary5() { doTest(); }
  public void testIndex$safeIndexNewLineAfterQ() { doTest(); }
  public void testIndex$safeIndexNewLineBeforeQ() { doTest(); }

  public void testNl$binary() { doTest(); }
  public void testNl$cast() { doTest(); }
  public void testNl$index() { doTest(); }
  public void testNl$postfixDec() { doTest(); }
  public void testNl$postfixInc() { doTest(); }
  public void testNl$unary() { doTest(); }

  public void testLambda$parenthesizedAdd() { doTest(); }
  public void testLambda$standalone1() { doTest(); }
  public void testLambda$standalone2() { doTest(); }
  public void testLambda$standalone3() { doTest(); }
  public void testLambda$standalone4() { doTest(); }
  public void testLambda$standalone5() { doTest(); }
  public void testLambda$standalone6() { doTest(); }
  public void testLambda$standalone7() { doTest(); }
  public void testLambda$standalone8() { doTest(); }
  public void testLambda$standalone9() { doTest(); }
  public void testLambda$standalone10() { doTest(); }
  public void testLambda$standalone11() { doTest(); }
  public void testLambda$standalone12() { doTest(); }
  public void testLambda$closureLike() { doTest(); }
  public void testLambda$nestedLambda() { doTest(); }
  public void testLambda$nestedLambda2() { doTest(); }
  public void testLambda$nestedLambda3() { doTest(); }
  public void testLambda$nestedLambda4() { doTest(); }
  public void testLambda$nestedLambda5() { doTest(); }
  public void testLambda$nestedLambda6() { doTest(); }
  public void testLambda$assign1() { doTest(); }
  public void testLambda$assign2() { doTest(); }
  public void testLambda$assign3() { doTest(); }
  public void testLambda$assign4() { doTest(); }
  public void testLambda$assign5() { doTest(); }
  public void testLambda$assign6() { doTest(); }
  public void testLambda$assign7() { doTest(); }
  public void testLambda$assign8() { doTest(); }
  public void testLambda$methodCall1() { doTest(); }
  public void testLambda$methodCall2() { doTest(); }
  public void testLambda$methodCall3() { doTest(); }
  public void testLambda$methodCall4() { doTest(); }
  public void testLambda$methodCall5() { doTest(); }
  public void testLambda$methodCall6() { doTest(); }
  public void testLambda$methodCall7() { doTest(); }
  public void testLambda$methodCall8() { doTest(); }
  public void testLambda$methodCall9() { doTest(); }
  public void testLambda$methodCall10() { doTest(); }
  public void testLambda$methodCall11() { doTest(); }
  public void testLambda$methodCall12() { doTest(); }
  public void testLambda$methodCall13() { doTest(); }
  public void testLambda$methodCall14() { doTest(); }
  public void testLambda$methodCall15() { doTest(); }
  public void testLambda$methodCall16() { doTest(); }
  public void testLambda$methodCall17() { doTest(); }
  public void testLambda$methodCall18() { doTest(); }
  public void testLambda$methodCall19() { doTest(); }
  public void testLambda$command1() { doTest(); }
  public void testLambda$command2() { doTest(); }
  public void testLambda$command3() { doTest(); }
  public void testLambda$command4() { doTest(); }
  public void testLambda$command5() { doTest(); }
  public void testLambda$command6() { doTest(); }
  public void testLambda$command7() { doTest(); }
  public void testLambda$command8() { doTest(); }
  public void testLambda$command9() { doTest(); }
  public void testLambda$command10() { doTest(); }
  public void testLambda$command11() { doTest(); }
  public void testLambda$command12() { doTest(); }
  public void testLambda$commandInLambda() { doTest(); }
  public void testLambda$implicitReturn1() { doTest(); }
  public void testLambda$implicitReturn2() { doTest(); }
  public void testLambda$implicitReturn3() { doTest(); }
  public void testLambda$implicitReturn4() { doTest(); }
  public void testLambda$return1() { doTest(); }
  public void testLambda$return2() { doTest(); }
  public void testLambda$return3() { doTest(); }
  public void testLambda$return4() { doTest(); }

  public void testTypeAnnotations$field1() { doTest(); }
  public void testTypeAnnotations$field2() { doTest(); }
  public void testTypeAnnotations$methodSignature() { doTest(); }
  public void testTypeAnnotations$methodSignature2() { doTest(); }
  public void testTypeAnnotations$methodSignature3() { doTest(); }
  public void testTypeAnnotations$classDecl1() { doTest(); }
  public void testTypeAnnotations$classDecl2() { doTest(); }
  public void testTypeAnnotations$tryCatch() { doTest(); }
  public void testTypeAnnotations$cast() { doTest(); }
  public void testTypeAnnotations$newExpression1() { doTest(); }
  public void testTypeAnnotations$newExpression2() { doTest(); }
  public void testTypeAnnotations$newExpression3() { doTest(); }
}

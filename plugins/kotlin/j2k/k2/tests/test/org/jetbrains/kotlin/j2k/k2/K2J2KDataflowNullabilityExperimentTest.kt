// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.j2k.k2

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.base.test.TestRoot
import org.jetbrains.kotlin.idea.test.runAll
import org.jetbrains.kotlin.j2k.ConverterSettings
import org.jetbrains.kotlin.j2k.J2KDataflowNullability
import org.jetbrains.kotlin.j2k.J2KNullityInferrer
import org.jetbrains.kotlin.j2k.Nullability

@TestRoot("j2k/k2/tests")
class K2J2KDataflowNullabilityTest : K2JavaToKotlinConverterSingleFileTestGenerated.Nullability() {
    override fun setUp() {
        super.setUp()
        enableDataflowNullability(closedWorld = false)
    }

    override fun tearDown() {
        runAll({ disableDataflowNullability() }, { super.tearDown() })
    }

    override fun fileToKotlin(text: String, settings: ConverterSettings): String {
        val testName = "${javaClass.simpleName}.$name"
        reportDecisionChanges(testName, copyOf(createJavaFile(text)))
        return super.fileToKotlin(text, settings).also { reportOutput(testName, it) }
    }
}

@TestRoot("j2k/k2/tests")
class K2J2KDataflowNullabilityGenericsTest : K2JavaToKotlinConverterSingleFileTestGenerated.NullabilityGenerics() {
    override fun setUp() {
        super.setUp()
        enableDataflowNullability(closedWorld = false)
    }

    override fun tearDown() {
        runAll({ disableDataflowNullability() }, { super.tearDown() })
    }

    override fun fileToKotlin(text: String, settings: ConverterSettings): String {
        val testName = "${javaClass.simpleName}.$name"
        reportDecisionChanges(testName, copyOf(createJavaFile(text)))
        return super.fileToKotlin(text, settings).also { reportOutput(testName, it) }
    }
}

@TestRoot("j2k/k2/tests")
class K2J2KDataflowNullabilityClosedWorldTest : K2JavaToKotlinConverterSingleFileTestGenerated.Nullability() {
    override fun setUp() {
        super.setUp()
        enableDataflowNullability(closedWorld = true)
    }

    override fun tearDown() {
        runAll({ disableDataflowNullability() }, { super.tearDown() })
    }

    override fun fileToKotlin(text: String, settings: ConverterSettings): String {
        val testName = "${javaClass.simpleName}.$name"
        reportDecisionChanges(testName, copyOf(createJavaFile(text)))
        return super.fileToKotlin(text, settings).also { reportOutput(testName, it) }
    }
}

@TestRoot("j2k/k2/tests")
class K2J2KDataflowNullabilityGenericsClosedWorldTest : K2JavaToKotlinConverterSingleFileTestGenerated.NullabilityGenerics() {
    override fun setUp() {
        super.setUp()
        enableDataflowNullability(closedWorld = true)
    }

    override fun tearDown() {
        runAll({ disableDataflowNullability() }, { super.tearDown() })
    }

    override fun fileToKotlin(text: String, settings: ConverterSettings): String {
        val testName = "${javaClass.simpleName}.$name"
        reportDecisionChanges(testName, copyOf(createJavaFile(text)))
        return super.fileToKotlin(text, settings).also { reportOutput(testName, it) }
    }
}

private fun enableDataflowNullability(closedWorld: Boolean) {
    System.setProperty(J2KDataflowNullability.ENABLED_PROPERTY, "true")
    System.setProperty(J2KDataflowNullability.CLOSED_WORLD_PROPERTY, closedWorld.toString())
}

private fun disableDataflowNullability() {
    System.clearProperty(J2KDataflowNullability.ENABLED_PROPERTY)
    System.clearProperty(J2KDataflowNullability.CLOSED_WORLD_PROPERTY)
}

private fun copyOf(javaFile: Any): PsiFile = (javaFile as PsiElement).copy() as PsiFile

private fun reportOutput(testName: String, kotlinText: String) {
    println("[j2k-dataflow-output-begin] $testName")
    println(kotlinText)
    println("[j2k-dataflow-output-end] $testName")
}

@OptIn(KaAllowAnalysisOnEdt::class)
private fun reportDecisionChanges(testName: String, file: PsiFile) {
    val oldInferrer = J2KNullityInferrer().apply { allowAnalysisOnEdt { collect(file) } }
    val decisions = allowAnalysisOnEdt { J2KDataflowNullability(file).decisions }
    var changed = 0
    for ((owner, new) in decisions) {
        val type = (owner as? PsiVariable)?.type ?: (owner as? PsiMethod)?.returnType ?: continue
        val old = when (type) {
            in oldInferrer.nullableTypes -> Nullability.Nullable
            in oldInferrer.notNullTypes -> Nullability.NotNull
            else -> Nullability.Default
        }
        if (old == new) continue
        changed++
        println("[j2k-dataflow] $testName ${describe(owner)}: $old -> $new")
    }
    println("[j2k-dataflow-summary] $testName slots=${decisions.size} changed=$changed")
}

private fun describe(owner: PsiModifierListOwner): String = when (owner) {
    is PsiField -> "field ${owner.containingClass?.name}.${owner.name}"
    is PsiMethod -> "return ${owner.containingClass?.name}.${owner.name}()"
    is PsiParameter -> "param ${(owner.declarationScope as? PsiMethod)?.name}(${owner.name})"
    is PsiLocalVariable -> "local ${PsiTreeUtil.getParentOfType(owner, PsiMethod::class.java)?.name}:${owner.name}"
    else -> owner.toString()
}

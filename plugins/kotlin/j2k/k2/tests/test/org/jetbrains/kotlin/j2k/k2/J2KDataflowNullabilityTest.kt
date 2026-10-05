// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.j2k.k2

import com.intellij.psi.PsiNamedElement
import com.intellij.testFramework.LightProjectDescriptor
import org.jetbrains.kotlin.analysis.api.permissions.KaAllowAnalysisOnEdt
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase
import org.jetbrains.kotlin.j2k.J2KDataflowNullability
import org.jetbrains.kotlin.j2k.J2K_PROJECT_DESCRIPTOR
import org.jetbrains.kotlin.j2k.Nullability

class J2KDataflowNullabilityTest : KotlinLightCodeInsightFixtureTestCase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = J2K_PROJECT_DESCRIPTOR

    fun testFieldAssignedOnOnePathStaysNullable() {
        val decisions = decisionsByName(
            """
            public class C {
                private String assignedOnOnePath;
                private String assignedOnEveryPath;

                public C(boolean c) {
                    if (c) assignedOnOnePath = "x";
                    assignedOnEveryPath = "y";
                }
            }
            """
        )
        assertEquals(Nullability.Nullable, decisions["assignedOnOnePath"])
        assertEquals(Nullability.NotNull, decisions["assignedOnEveryPath"])
    }

    fun testLibraryMethodAnnotationReachesLocal() {
        val decisions = decisionsByName(
            """
            public class C {
                void test() {
                    Integer boxed = Integer.valueOf(100);
                }
            }
            """
        )
        assertEquals(Nullability.NotNull, decisions["boxed"])
    }

    @OptIn(KaAllowAnalysisOnEdt::class)
    private fun decisionsByName(javaCode: String): Map<String?, Nullability> {
        val file = myFixture.configureByText("C.java", javaCode.trimIndent())
        return allowAnalysisOnEdt { J2KDataflowNullability(file).decisions }.mapKeys { (it.key as? PsiNamedElement)?.name }
    }
}

// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixFactory
import org.jetbrains.kotlin.idea.quickfix.RemoveModifierFixBase
import org.jetbrains.kotlin.idea.quickfix.RemoveSupertypeFix
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

internal object RemoveSupertypeFixFactory {

    val removeSupertypeFixFactory = KotlinQuickFixFactory.ModCommandBased { diagnostic: KaFirDiagnostic.ManyClassesInSupertypeList ->
        createRemoveSupertypeFix(diagnostic.psi)
    }

    val valueClassCannotExtendIdentityClassesFixFactory =
        KotlinQuickFixFactory.ModCommandBased { diagnostic: KaFirDiagnostic.ValueClassCannotExtendIdentityClasses ->
            createRemoveSupertypeFix(diagnostic.psi) + createRemoveValueModifierFix(diagnostic.psi)
        }

    private fun createRemoveSupertypeFix(element: PsiElement): List<RemoveSupertypeFix> {
        val superType = element.getStrictParentOfType<KtSuperTypeListEntry>() ?: return emptyList()

        return listOf(RemoveSupertypeFix(superType))
    }

    private fun createRemoveValueModifierFix(element: PsiElement): List<RemoveModifierFixBase> {
        val valueClass = element.getStrictParentOfType<KtClass>()
            ?.takeIf { it.hasModifier(KtTokens.VALUE_KEYWORD) }
            ?: return emptyList()

        return listOf(RemoveModifierFixBase(valueClass, KtTokens.VALUE_KEYWORD, isRedundant = false))
    }
}

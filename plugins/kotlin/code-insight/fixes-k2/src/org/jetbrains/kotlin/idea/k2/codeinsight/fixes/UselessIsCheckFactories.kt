// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.fixes

import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.analysis.api.fir.diagnostics.KaFirDiagnostic
import org.jetbrains.kotlin.analysis.api.resolution.single
import org.jetbrains.kotlin.analysis.api.resolution.tryResolveCall
import org.jetbrains.kotlin.analysis.api.resolution.variable
import org.jetbrains.kotlin.analysis.api.types.isMarkedNullable
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinPsiUpdateModCommandAction
import org.jetbrains.kotlin.idea.codeinsight.api.applicators.fixes.KotlinQuickFixFactory
import org.jetbrains.kotlin.idea.codeinsight.intentions.branchedTransformations.generateNewConditionWithSubject
import org.jetbrains.kotlin.idea.quickfix.RemoveUselessIsCheckFix
import org.jetbrains.kotlin.idea.quickfix.RemoveUselessIsCheckFixForWhen
import org.jetbrains.kotlin.idea.quickfix.ReplaceIsCheckWithNullCheckFix
import org.jetbrains.kotlin.idea.util.CommentSaver
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNullableType
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtWhenCondition
import org.jetbrains.kotlin.psi.KtWhenConditionIsPattern
import org.jetbrains.kotlin.psi.KtWhenEntry
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.buildExpression
import org.jetbrains.kotlin.psi.createExpressionByPattern
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

internal object UselessIsCheckFactories {
    val uselessIsCheckFactory =
        prepareRemoveUselessIsCheckFix<KaFirDiagnostic.UselessIsCheck> { compileTimeCheckResult }

    val impossibleIsCheckWarningFactory =
        prepareRemoveUselessIsCheckFix<KaFirDiagnostic.ImpossibleIsCheckWarning> { compileTimeCheckResult }

    val impossibleIsCheckErrorFactory =
        prepareRemoveUselessIsCheckFix<KaFirDiagnostic.ImpossibleIsCheckError> { compileTimeCheckResult }

    val impossibleIsCheckDeprecationWarningFactory =
        prepareRemoveUselessIsCheckFix<KaFirDiagnostic.ImpossibleIsCheckDeprecationWarning> { compileTimeCheckResult }

    val impossibleIsCheckDeprecationErrorFactory =
        prepareRemoveUselessIsCheckFix<KaFirDiagnostic.ImpossibleIsCheckDeprecationError> { compileTimeCheckResult }

    val impossibleIsCheckRelyingOnNullWarningFactory =
        prepareReplaceIsCheckWithNullCheckFix<KaFirDiagnostic.ImpossibleIsCheckRelyingOnNullWarning>()

    val impossibleIsCheckRelyingOnNullErrorFactory =
        prepareReplaceIsCheckWithNullCheckFix<KaFirDiagnostic.ImpossibleIsCheckRelyingOnNullError>()

    private fun <T: KaFirDiagnostic<KtElement>> prepareReplaceIsCheckWithNullCheckFix() =
        KotlinQuickFixFactory.ModCommandBased { diagnostic: T ->
            val element = diagnostic.psi.takeIf { it.isWritable } ?: return@ModCommandBased emptyList()
            val expression = element.getNonStrictParentOfType<KtIsExpression>()
            if (expression != null) {
                return@ModCommandBased listOf(ReplaceIsCheckWithNullCheckFix(expression))
            }
            val condition = element.getNonStrictParentOfType<KtWhenConditionIsPattern>()
                ?: return@ModCommandBased emptyList()
            listOf(ReplaceWhenIsCheckWithNullCheckFix(condition))
        }

    private inline fun <T: KaFirDiagnostic<KtElement>> prepareRemoveUselessIsCheckFix(crossinline compileTimeCheckResult: T.() -> Boolean) =
        KotlinQuickFixFactory.ModCommandBased { diagnostic: T ->
            val element = diagnostic.psi.takeIf { it.isWritable } ?: return@ModCommandBased emptyList()
            val expression = element.getNonStrictParentOfType<KtIsExpression>() ?: return@ModCommandBased emptyList()
            listOf(RemoveUselessIsCheckFix(expression, diagnostic.compileTimeCheckResult()))
        }

    val uselessWhenCheckFactory =
        prepareRemoveUselessIsCheckFixForWhen<KaFirDiagnostic.UselessIsCheck> { compileTimeCheckResult }

    val impossibleWhenCheckWarningFactory =
        prepareRemoveUselessIsCheckFixForWhen<KaFirDiagnostic.ImpossibleIsCheckWarning>(replaceNullableCheckWithNullCheck = true) {
            compileTimeCheckResult
        }

    val impossibleWhenCheckErrorFactory =
        prepareRemoveUselessIsCheckFixForWhen<KaFirDiagnostic.ImpossibleIsCheckError>(replaceNullableCheckWithNullCheck = true) {
            compileTimeCheckResult
        }

    val impossibleWhenCheckDeprecationWarningFactory =
        prepareRemoveUselessIsCheckFixForWhen<KaFirDiagnostic.ImpossibleIsCheckDeprecationWarning> { compileTimeCheckResult }

    val impossibleWhenCheckDeprecationErrorFactory =
        prepareRemoveUselessIsCheckFixForWhen<KaFirDiagnostic.ImpossibleIsCheckDeprecationError> { compileTimeCheckResult }

    private inline fun <T: KaFirDiagnostic<KtElement>> prepareRemoveUselessIsCheckFixForWhen(
        replaceNullableCheckWithNullCheck: Boolean = false,
        crossinline compileTimeCheckResult: T.() -> Boolean,
    ) =
        KotlinQuickFixFactory.ModCommandBased { diagnostic: T ->
            val element = diagnostic.psi.takeIf { it.isWritable } ?: return@ModCommandBased emptyList()
            val expression = element.getNonStrictParentOfType<KtWhenConditionIsPattern>() ?: return@ModCommandBased emptyList()
            if (replaceNullableCheckWithNullCheck) {
                val subjectExpression = element.getNonStrictParentOfType<KtWhenExpression>()?.subjectExpression
                if (
                    expression.typeReference?.typeElement is KtNullableType ||
                    (subjectExpression as? KtNameReferenceExpression)?.tryResolveCall()?.single?.variable?.signature?.returnType?.isMarkedNullable == true
                ) {
                    return@ModCommandBased listOf(ReplaceWhenIsCheckWithNullCheckFix(expression))
                }
            }
            if (expression.typeReference?.typeElement is KtNullableType) return@ModCommandBased emptyList()
            if (expression.getStrictParentOfType<KtWhenEntry>()?.guard != null) return@ModCommandBased emptyList()
            listOf(RemoveUselessIsCheckFixForWhen(expression, diagnostic.compileTimeCheckResult()))
        }

}

private class ReplaceWhenIsCheckWithNullCheckFix(
    element: KtWhenConditionIsPattern,
) : KotlinPsiUpdateModCommandAction.ElementContextless<KtWhenConditionIsPattern>(element) {
    override fun getFamilyName(): @IntentionFamilyName String = KotlinBundle.message("replace.is.check.with.null.check")

    override fun invoke(
        context: ActionContext,
        element: KtWhenConditionIsPattern,
        updater: ModPsiUpdater,
    ) {
        val whenEntry = element.parent as? KtWhenEntry ?: return
        val whenExpression = whenEntry.parent as? KtWhenExpression ?: return
        val subject = whenExpression.subjectExpression ?: return

        val commentSaver = CommentSaver(whenExpression, saveLineBreaks = true)
        val newExpression = KtPsiFactory(context.project).buildExpression {
            appendFixedText("when {\n")
            for (entry in whenExpression.entries) {
                val branchExpression = entry.expression
                if (entry.isElse) {
                    appendFixedText("else")
                } else {
                    appendExpressions(
                        entry.conditions.map { it.generateNullCheckCondition(subject, element) },
                        separator = "||",
                    )
                    val guardExpression = entry.guard?.getExpression()
                    if (guardExpression != null) {
                        appendFixedText("&&")
                        appendExpression(guardExpression)
                    }
                }
                appendFixedText("->")
                appendExpression(branchExpression)
                appendFixedText("\n")
            }
            appendFixedText("}")
        }

        val result = whenExpression.replace(newExpression)
        commentSaver.restore(result)
    }

    private fun KtWhenCondition.generateNullCheckCondition(
        subject: KtExpression,
        conditionToReplace: KtWhenConditionIsPattern,
    ): KtExpression {
        if (this != conditionToReplace) return generateNewConditionWithSubject(subject, isNullableSubject = true)

        val operator = if (conditionToReplace.isNegated) "!=" else "=="
        return KtPsiFactory(project).createExpressionByPattern("$0 $operator null", subject)
    }
}

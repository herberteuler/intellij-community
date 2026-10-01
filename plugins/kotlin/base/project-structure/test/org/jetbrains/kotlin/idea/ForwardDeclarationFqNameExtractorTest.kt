// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea

import org.jetbrains.kotlin.idea.base.projectStructure.forwardDeclarations.KotlinForwardDeclarationsFqNameExtractor
import org.jetbrains.kotlin.name.FqName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForwardDeclarationFqNameExtractorTest {
    @Test
    fun testPackageGrouping() {
        val declarationFqNames = listOf(
            FqName("cnames.structs.foo"),
            FqName("cnames.structs.bar"),
            FqName("cnames.structs.baz"),
            FqName("objcnames.classes.boo"),
            FqName("objcnames.protocols.vee"),
        )

        assertEquals(
            mapOf(
                FqName("cnames.structs") to listOf(
                    FqName("cnames.structs.foo"),
                    FqName("cnames.structs.bar"),
                    FqName("cnames.structs.baz"),
                ),
                FqName("objcnames.classes") to listOf(
                    FqName("objcnames.classes.boo"),
                ),
                FqName("objcnames.protocols") to listOf(
                    FqName("objcnames.protocols.vee"),
                ),
            ),
            KotlinForwardDeclarationsFqNameExtractor.groupByPackage(declarationFqNames)
        )
    }

    @Test
    fun testTopLevelFqNamesAndRoot() {
        val declarationFqNames = listOf(
            FqName("Top1"),
            FqName("Top2"),
            FqName(""),
            FqName("non.top.level.Foo"),
            FqName("non.top.level.Bar"),
        )

        assertEquals(
            mapOf(
                FqName.ROOT to listOf(
                    FqName("Top1"),
                    FqName("Top2"),
                ),
                FqName("non.top.level") to listOf(
                    FqName("non.top.level.Foo"),
                    FqName("non.top.level.Bar"),
                )
            ),
            KotlinForwardDeclarationsFqNameExtractor.groupByPackage(declarationFqNames)
        )
    }
}

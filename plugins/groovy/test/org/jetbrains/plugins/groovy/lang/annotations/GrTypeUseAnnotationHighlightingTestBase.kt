// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.lang.annotations

import com.intellij.testFramework.LightProjectDescriptor
import org.jetbrains.plugins.groovy.GroovyProjectDescriptors
import org.jetbrains.plugins.groovy.lang.highlighting.GrHighlightingTestBase

abstract class GrTypeUseAnnotationHighlightingTestBase : GrHighlightingTestBase() {
  override fun setUp() {
    super.setUp()
    myFixture.addClass("""
      import java.lang.annotation.ElementType
      import java.lang.annotation.Target

      @Target({ElementType.TYPE_USE})
      @interface ExampleAnno {}
    """.trimIndent())
  }

  class Gr3TypeUseAnnotationHighlightingTest : GrTypeUseAnnotationHighlightingTestBase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = GroovyProjectDescriptors.GROOVY_3_0

    fun testAnnotationClass() = doTestHighlighting("""
        @<error descr="'@ExampleAnno' annotation not allowed on annotation type">ExampleAnno</error>
        @interface InnerAnnotation {}
    """.trimIndent())

    fun testNoHighlightingForTypeUse() = doTestHighlighting("""
    @<error descr="'@ExampleAnno' annotation not allowed on type">ExampleAnno</error>
    class Main2 {
        @<error descr="'@ExampleAnno' annotation not allowed on field">ExampleAnno</error>
        private String field = null;
        
         @<error descr="'@ExampleAnno' annotation not allowed on constructor">ExampleAnno</error>
        Main2() {
        }
    
        static void main(String[] args) {
            method("string")
        }
    
        @<error descr="'@ExampleAnno' annotation not allowed on method">ExampleAnno</error>
        static String method(@<error descr="'@ExampleAnno' annotation not allowed on parameter">ExampleAnno</error> String s) {
            @<error descr="'@ExampleAnno' annotation not allowed on local variable">ExampleAnno</error> String t = "r";
        }
    }
  """.trimIndent())
  }

  class Gr4TypeUseAnnotationHighlightingTest : GrTypeUseAnnotationHighlightingTestBase() {
    override fun getProjectDescriptor(): LightProjectDescriptor = GroovyProjectDescriptors.GROOVY_4_0

    fun testAnnotationClass() = doTestHighlighting("""
        @ExampleAnno
        @interface InnerAnnotation {}
    """.trimIndent())

    fun testNoHighlightingForTypeUse() = doTestHighlighting("""
    @ExampleAnno
    class Main2 {
        @ExampleAnno
        private String field = null;
        
        @ExampleAnno
        Main2() {
        }

        static void main(String[] args) {
            method("string")
        }

        @ExampleAnno
        static String method(@ExampleAnno String s) {
            @ExampleAnno String t = "r";
        }
    }
  """.trimIndent())
  }
}
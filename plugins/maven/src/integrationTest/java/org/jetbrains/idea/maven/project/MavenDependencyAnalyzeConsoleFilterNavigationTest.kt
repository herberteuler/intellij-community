// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project

import com.intellij.maven.testFramework.fixtures.MavenVersionArguments
import com.intellij.maven.testFramework.fixtures.createModulePom
import com.intellij.maven.testFramework.fixtures.createProjectPom
import com.intellij.maven.testFramework.fixtures.importProjectsAsync
import com.intellij.maven.testFramework.fixtures.mavenDomFixture
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.idea.maven.project.MavenDependencyAnalyzeConsoleFilter.DependencyHyperlinkInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedClass
import org.junit.jupiter.params.provider.ArgumentsSource

/**
 * Verifies that the hyperlinks produced by [MavenDependencyAnalyzeConsoleFilter] for `dependency:analyze` output resolve
 * to the correct navigation target: the `<dependency>` declaration in the module named by the analyze goal header, or the
 * module `pom.xml` itself when the coordinate is used but not declared there.
 */
@TestApplication
@ParameterizedClass
@ArgumentsSource(MavenVersionArguments::class)
class MavenDependencyAnalyzeConsoleFilterNavigationTest(mavenVersion: String, modelVersion: String) {

  private val maven by mavenDomFixture(
    mavenVersion = mavenVersion,
    modelVersion = modelVersion
  )

  @Test
  fun testNavigatesToDependencyDeclarations() = runBlocking {
    val parentPom = maven.createProjectPom(
      """
        <groupId>test</groupId>
        <artifactId>project</artifactId>
        <version>1</version>
        <packaging>pom</packaging>
        <modules>
          <module>m1</module>
        </modules>
        """.trimIndent())

    val m1Pom = maven.createModulePom(
      "m1",
      """
        <parent>
          <groupId>test</groupId>
          <artifactId>project</artifactId>
          <version>1</version>
        </parent>
        <artifactId>m1</artifactId>
        <dependencies>
          <dependency>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-lang3</artifactId>
            <version>3.18.0</version>
          </dependency>
        </dependencies>
        """.trimIndent())

    maven.importProjectsAsync(parentPom, m1Pom)

    // The dependency is declared in the module named by the analyze goal header: navigate straight to its <artifactId>.
    assertNavigatesToArtifactId(m1Pom, "<artifactId>commons-lang3",
                                DependencyHyperlinkInfo("org.apache.commons", "commons-lang3", "m1"))

    // No goal header seen yet (module is null): the declaration is still found by scanning the imported projects.
    assertNavigatesToArtifactId(m1Pom, "<artifactId>commons-lang3",
                                DependencyHyperlinkInfo("org.apache.commons", "commons-lang3", null))

    // A "used undeclared" coordinate is absent from every pom, so the link falls back to the module's root <artifactId>.
    assertNavigatesToArtifactId(m1Pom, "<artifactId>m1</artifactId>",
                                DependencyHyperlinkInfo("com.google.guava", "guava", "m1"))

    // Unknown module and unknown coordinate: there is nothing to navigate to.
    val noTarget = readAction {
      DependencyHyperlinkInfo("com.google.guava", "guava", "unknown-module").findTargetNavigatable(maven.project)
    }
    assertNull(noTarget, "An unknown module and coordinate should not resolve to any navigation target")
  }

  /**
   * Resolves [info] to its navigation target, navigates to it, and asserts that the caret lands in [expectedFile] right
   * after the `<artifactId>` open tag identified by [artifactIdAnchor] (matching the offset math of `MavenNavigationUtil`).
   */
  private suspend fun assertNavigatesToArtifactId(
    expectedFile: VirtualFile,
    artifactIdAnchor: String,
    info: DependencyHyperlinkInfo,
  ) {
    val navigatable: Navigatable? = readAction { info.findTargetNavigatable(maven.project) }
    assertNotNull(navigatable, "Expected a navigation target for ${info.groupId}:${info.artifactId}")
    assertTrue(navigatable!!.canNavigate(), "The navigation target should be navigable")

    val (actualFile, actualOffset) = withContext(Dispatchers.EDT) {
      writeIntentReadAction {
        navigatable.navigate(true)
        val editor = FileEditorManager.getInstance(maven.project).selectedTextEditor!!
        val file = FileDocumentManager.getInstance().getFile(editor.document)!!
        file to editor.caretModel.offset
      }
    }

    assertEquals(expectedFile, actualFile, "Navigated to the wrong pom.xml")

    val documentText = readAction { FileDocumentManager.getInstance().getDocument(expectedFile)!!.text }
    val expectedOffset = documentText.indexOf(artifactIdAnchor) + "<artifactId>".length
    assertEquals(expectedOffset, actualOffset, "The caret landed at the wrong offset")
  }
}

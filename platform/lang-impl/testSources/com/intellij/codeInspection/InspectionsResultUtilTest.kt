// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInspection.ex.InspectionToolWrapper
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.LoggedErrorProcessor
import org.assertj.core.api.Assertions.assertThat
import org.jdom.Element
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

class InspectionsResultUtilTest {
  @TempDir
  lateinit var tempDir: Path

  @Test
  fun `description with illegal character is written as valid XML`() {
    val root = describe(tool(description = "<html>Before\u001BAfter</html>"))

    assertThat(inspectionOf(root).text).isEqualTo("<html>Before?After</html>")
  }

  /** A display name goes through `writeAttribute`, which is a separate call from the description. */
  @Test
  fun `display name with illegal character is written as valid XML`() {
    val root = describe(tool(displayName = "Before\u001BAfter"))

    assertThat(inspectionOf(root).getAttributeValue("displayName")).isEqualTo("Before?After")
  }

  @Test
  fun `group name with illegal character is written as valid XML`() {
    val root = describe(tool(groupDisplayName = "Before\u001BAfter"))

    assertThat(root.getChild("group").getAttributeValue("name")).isEqualTo("Before?After")
  }

  /** The profile name comes from the caller, not from an inspection, and it has its own call. */
  @Test
  fun `profile name with illegal character is written as valid XML`() {
    val root = describe(tool(), profileName = "Before\u001BAfter")

    assertThat(root.getAttributeValue("profile")).isEqualTo("Before?After")
  }

  /**
   * A `char` wise sanitizer drops a surrogate pair. A description can hold one. The text also holds an
   * illegal character, because the sanitizer returns early when every character is legal.
   */
  @Test
  fun `character outside the basic multilingual plane survives`() {
    val root = describe(tool(description = "an emoji 😀 stays\u001B"))

    assertThat(inspectionOf(root).text).isEqualTo("an emoji 😀 stays?")
  }

  /**
   * `XmlStringUtil.escapeIllegalXmlChars` is the other helper on offer, and it turns a hash into two.
   * Nothing on this path reverses such an escape, so this test rejects a move to that helper.
   */
  @Test
  fun `hash character survives`() {
    val root = describe(tool(description = "see #ref for detail\u001B"))

    assertThat(inspectionOf(root).text).isEqualTo("see #ref for detail?")
  }

  /**
   * A description is multi-line HTML. A sanitizer which drops a line feed still writes parseable XML, so a
   * parse alone cannot find that fault. XML 1.0 permits a line feed and a tab, and both must survive.
   */
  @Test
  fun `line feed and tab survive`() {
    val root = describe(tool(description = "first line\n\tindented line\u001B"))

    assertThat(inspectionOf(root).text).isEqualTo("first line\n\tindented line?")
  }

  /** A warning must place a replacement, so it names the inspection and the field. */
  @Test
  fun `the warning names the inspection and the field`() {
    val warnings = collectWarnings {
      describe(tool(displayName = "Before\u001BAfter"))
    }

    assertThat(warnings).anySatisfy {
      assertThat(it).contains("TestInspection/@displayName")
    }
  }

  /** The profile name belongs to no inspection, so its warning names the field alone. */
  @Test
  fun `the warning of the profile name names no inspection`() {
    val warnings = collectWarnings {
      describe(tool(), profileName = "Before\u001BAfter")
    }

    assertThat(warnings).anySatisfy {
      assertThat(it).contains("@profile").doesNotContain("TestInspection")
    }
  }

  /**
   * A group name belongs to no inspection, and a group follows the inspections of the group before it. So
   * the writer must forget the context when an inspection ends. Both group names hold an illegal character,
   * because the groups reach the writer in no fixed order.
   */
  @Test
  fun `the warning of a group name names no inspection`() {
    val warnings = collectWarnings {
      describeAll(
        tool(shortName = "FirstInspection", groupDisplayName = "First group\u001B"),
        tool(shortName = "SecondInspection", groupDisplayName = "Second group\u001B"),
      )
    }

    assertThat(warnings).filteredOn { it.contains("@name") }.hasSize(2).allSatisfy {
      assertThat(it).doesNotContain("Inspection/")
    }
  }

  /** A short name can hold the same character, so it must not reach the log raw either. */
  @Test
  fun `a short name in the missing description error is sanitized`() {
    val errors = mutableListOf<String>()
    LoggedErrorProcessor.executeWith<Throwable>(object : LoggedErrorProcessor() {
      override fun processError(category: String, message: String, details: Array<String>, t: Throwable?): Set<Action> {
        errors.add(message)
        return emptySet()
      }
    }) {
      describe(tool(shortName = "Before\u001BAfter", description = null))
    }

    assertThat(errors).anySatisfy {
      assertThat(it).contains("Before?After").doesNotContain("\u001B")
    }
  }

  /** `SanitizingXmlWriter` detects a replacement by identity, so a legal text must come back unchanged. */
  @Test
  fun `a legal text comes back as the same instance`() {
    val text = "a legal text"

    assertThat(ProblemDescriptorUtil.sanitizeIllegalXmlChars(text)).isSameAs(text)
  }

  private fun collectWarnings(body: () -> Unit): List<String> {
    val warnings = mutableListOf<String>()
    LoggedErrorProcessor.executeWith<Throwable>(object : LoggedErrorProcessor() {
      override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
        warnings.add(message)
        return false
      }
    }) {
      body()
    }
    return warnings
  }

  private fun tool(
    shortName: String = "TestInspection",
    displayName: String = "Test inspection",
    groupDisplayName: String = "Test group",
    description: String? = "Test description",
  ): InspectionToolWrapper<*, *> {
    val tool = mock<InspectionToolWrapper<*, *>>()
    whenever(tool.groupDisplayName).thenReturn(groupDisplayName)
    whenever(tool.groupPath).thenReturn(emptyArray())
    whenever(tool.shortName).thenReturn(shortName)
    whenever(tool.defaultLevel).thenReturn(HighlightDisplayLevel.WARNING)
    whenever(tool.displayName).thenReturn(displayName)
    whenever(tool.loadDescription()).thenReturn(description)
    return tool
  }

  private fun describe(tool: InspectionToolWrapper<*, *>, profileName: String = "Test profile"): Element =
    describeAll(tool, profileName = profileName)

  private fun describeAll(vararg tools: InspectionToolWrapper<*, *>, profileName: String = "Test profile"): Element {
    val profile = mock<InspectionProfile>()
    whenever(profile.getInspectionTools(null)).thenReturn(tools.toList())
    val output = tempDir.resolve("descriptions.xml")

    InspectionsResultUtil.describeInspections(output, profileName, profile)

    return JDOMUtil.load(Files.readString(output))
  }

  private fun inspectionOf(root: Element): Element = root.getChild("group").getChild("inspection")
}

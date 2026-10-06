package org.intellij.plugins.markdown.editor.tables

import com.intellij.idea.TestFor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.testFramework.LightPlatformCodeInsightTestCase
import org.intellij.plugins.markdown.MarkdownTestingUtil
import org.intellij.plugins.markdown.formatter.MarkdownFormatterTest.Companion.performReformatting
import org.intellij.plugins.markdown.formatter.MarkdownFormatterTest.Companion.runWithTemporaryStyleSettings
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import org.intellij.plugins.markdown.lang.MarkdownFileType
import org.intellij.plugins.markdown.lang.formatter.settings.MarkdownCustomCodeStyleSettings
import org.intellij.plugins.markdown.lang.formatter.settings.TableStyle
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
@TestFor(issues = ["IDEA-298828"])
class MarkdownTablePostFormatProcessorTest: LightPlatformCodeInsightTestCase() {
  @Test
  fun `single table`() = doTest()

  @Test
  fun `multiple tables`() = doTest()

  @Test
  fun `table without end newline`() = doTest()

  @Test
  fun `chinese table test`() = doTest()

  @Test
  fun `emoji table`() = doTest()

  @Test
  fun `table with colored boxes`() = doTest()

  @Test
  fun `emoji sequence table`() = doTest()

  @Test
  fun `table inside list item`() = doTest()

  @Test
  fun `table with tabs`() = doTest()

  @Test
  fun `table with tab inside cell text`() = doTest(tabSize = 4)

  @Test
  fun `tabs in code spans preserve alignment`() {
    for (tabSize in listOf(2, 4, 8)) {
      runWithTemporaryStyleSettings(project) { settings ->
        settings.getIndentOptions(MarkdownFileType.INSTANCE).TAB_SIZE = tabSize
        val leftContent = " `a\tb`" + " ".repeat(if (tabSize == 2) 7 else 5)
        val rightContent = if (tabSize == 8) "    `a\tb`     " else "        `a\tb` "
        for ((separator, content) in listOf(
          ":-------------" to leftContent,
          "-------------:" to rightContent,
          ":------------:" to "    `a\tb`     ",
        )) {
          doStyleTest(
            TableStyle.ALIGNED,
            "| abcdefghijkl | x |\n|$separator|---|\n| `a\tb` | y |",
            "| abcdefghijkl | x |\n|$separator|---|\n|$content| y |",
          )
          val table = checkNotNull(TableUtils.findTable(file, 0))
          assertTrue(TableModificationUtils.run { table.isCorrectlyFormatted(TableStyle.ALIGNED) })
        }
      }
    }
  }

  @Test
  fun `compact table`() = doStyleTest(
    TableStyle.COMPACT,
    """
    |Character|Meaning|
    |---------|-------|
    |Y|Yes|
    |N|No|
    """.trimIndent(),
    """
    | Character | Meaning |
    | --- | --- |
    | Y | Yes |
    | N | No |
    """.trimIndent()
  )

  @Test
  fun `tight table`() = doStyleTest(
    TableStyle.TIGHT,
    """
    | Character | Meaning |
    | --- | --- |
    | Y | Yes |
    | N | No |
    """.trimIndent(),
    """
    |Character|Meaning|
    |---|---|
    |Y|Yes|
    |N|No|
    """.trimIndent()
  )

  @Test
  fun `table style is applied when reformatting a file`() {
    runWithTemporaryStyleSettings(project) { settings ->
      settings.getCustomSettings(MarkdownCustomCodeStyleSettings::class.java).FORMAT_TABLES = true
      withTableStyle(project, TableStyle.COMPACT) {
        configureFromFileText(
          "some.md",
          """
          |Character|Meaning|
          |---------|-------|
          |Y|Yes|
          """.trimIndent()
        )
        WriteCommandAction.runWriteCommandAction(project) {
          CodeStyleManager.getInstance(project).reformat(file)
        }
        checkResultByText(
          """
          | Character | Meaning |
          | --- | --- |
          | Y | Yes |
          """.trimIndent()
        )
      }
    }
  }

  @Test
  fun `reformat table after wrapped block quote does not throw`() {
    // language=Markdown
    val text = """
      > This is a long sentence that contains formatted text at a place that will be _reformatted_ and when it is reformatted, it will be reformatted in a way that
      > is not the way it should be done.

      | one | two | three | four |
      |-----|-----|-------|------|
      | 1 | 2 | 3 | 4 |
      | 5 | 6 | 7 | 8 |
    """.trimIndent()
    runWithTemporaryStyleSettings(project) { settings ->
      settings.getCommonSettings(MarkdownLanguage.INSTANCE).RIGHT_MARGIN = 80
      settings.getCustomSettings(MarkdownCustomCodeStyleSettings::class.java).apply {
        FORMAT_TABLES = true
        INSERT_QUOTE_ARROWS_ON_WRAP = true
      }
      configureFromFileText("some.md", text)
      performReformatting(project, file)
    }
  }

  private fun doTest(tabSize: Int? = null) {
    val before = getTestName(true) + ".before.md"
    val after = getTestName(true) + ".after.md"
    runWithTemporaryStyleSettings(project) { settings ->
      settings.apply {
        if (tabSize != null) {
          getIndentOptions(MarkdownFileType.INSTANCE).TAB_SIZE = tabSize
        }
        getCustomSettings(MarkdownCustomCodeStyleSettings::class.java).apply {
          FORMAT_TABLES = true
        }
      }
      configureByFile(before)
      performReformatting(project, file)
      checkResultByFile(after)
      //check idempotence of formatter
      performReformatting(project, file)
      checkResultByFile(after)
    }
  }

  private fun doStyleTest(style: TableStyle, before: String, after: String) {
    runWithTemporaryStyleSettings(project) { settings ->
      settings.getCustomSettings(MarkdownCustomCodeStyleSettings::class.java).FORMAT_TABLES = true
      withTableStyle(project, style) {
        configureFromFileText("some.md", before)
        performReformatting(project, file)
        checkResultByText(after)
        performReformatting(project, file)
        checkResultByText(after)
      }
    }
  }

  override fun getTestDataPath(): String {
    return MarkdownTestingUtil.TEST_DATA_PATH + "/editor/tables/format/"
  }

  override fun getTestName(lowercaseFirstLetter: Boolean): String {
    val name = super.getTestName(lowercaseFirstLetter)
    return name.trimStart().replace(' ', '_')
  }
}

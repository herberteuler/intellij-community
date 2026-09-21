// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project;

import com.intellij.execution.filters.Filter;
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase;
import org.jetbrains.idea.maven.project.MavenDependencyAnalyzeConsoleFilter.DependencyHyperlinkInfo;
import org.junit.Assert;

import java.util.List;

/**
 * Parsing/highlighting tests for {@link MavenDependencyAnalyzeConsoleFilter} (IDEA-394030). These do not need an
 * imported Maven project: they verify which spans become hyperlinks and what coordinate/module each link captures.
 */
public class MavenDependencyAnalyzeConsoleFilterTest extends CodeInsightFixtureTestCase {

  private MavenDependencyAnalyzeConsoleFilter myFilter;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    myFilter = new MavenDependencyAnalyzeConsoleFilter();
  }

  public void testHighlightsCoordinateWithErrorPrefix() {
    String coordinate = "org.apache.commons:commons-lang3:jar:3.18.0:compile";
    DependencyHyperlinkInfo info = assertSingleLink("[ERROR]    " + coordinate, coordinate);
    Assert.assertEquals("org.apache.commons", info.getGroupId());
    Assert.assertEquals("commons-lang3", info.getArtifactId());
  }

  public void testHighlightsCoordinateWithWarningPrefix() {
    String coordinate = "org.slf4j:slf4j-api:jar:2.0.9:compile";
    DependencyHyperlinkInfo info = assertSingleLink("[WARNING]    " + coordinate, coordinate);
    Assert.assertEquals("org.slf4j", info.getGroupId());
    Assert.assertEquals("slf4j-api", info.getArtifactId());
  }

  public void testHighlightsCoordinateWithoutPrefix() {
    String coordinate = "com.google.guava:guava:jar:33.0.0-jre:compile";
    DependencyHyperlinkInfo info = assertSingleLink("   " + coordinate, coordinate);
    Assert.assertEquals("com.google.guava", info.getGroupId());
    Assert.assertEquals("guava", info.getArtifactId());
  }

  public void testHighlightsCoordinateWithClassifier() {
    String coordinate = "org.example:demo:test-jar:tests:1.0.0:test";
    DependencyHyperlinkInfo info = assertSingleLink("[ERROR]    " + coordinate, coordinate);
    Assert.assertEquals("org.example", info.getGroupId());
    Assert.assertEquals("demo", info.getArtifactId());
  }

  public void testModuleTrackingFromAnalyzeOnlyHeader() {
    assertNoLink("[INFO] --- dependency:3.7.0:analyze-only (default-cli) @ my-module ---");
    DependencyHyperlinkInfo info = assertSingleLink("[ERROR]    org.example:used:jar:1.0.0:compile",
                                                    "org.example:used:jar:1.0.0:compile");
    Assert.assertEquals("my-module", info.getModuleArtifactId());
  }

  public void testModuleTrackingFromPluginPrefixedAnalyzeHeader() {
    assertNoLink("[INFO] --- maven-dependency-plugin:3.7.0:analyze (default-cli) @ core-module ---");
    DependencyHyperlinkInfo info = assertSingleLink("[WARNING]    org.example:used:jar:1.0.0:compile",
                                                    "org.example:used:jar:1.0.0:compile");
    Assert.assertEquals("core-module", info.getModuleArtifactId());
  }

  public void testModuleIsNullBeforeAnyHeader() {
    DependencyHyperlinkInfo info = assertSingleLink("[ERROR]    org.example:used:jar:1.0.0:compile",
                                                    "org.example:used:jar:1.0.0:compile");
    Assert.assertNull(info.getModuleArtifactId());
  }

  public void testOffsetsAccountForTerminalEntireLength() {
    String coordinate = "org.apache.commons:commons-lang3:jar:3.18.0:compile";
    String line = "[ERROR]    " + coordinate;
    // Emulate the built-in terminal, where the flushed buffer is longer than the current line.
    int precedingOutput = 4096;
    int entireLength = precedingOutput + line.length();

    Filter.Result result = myFilter.applyFilter(line, entireLength);
    Assert.assertNotNull(result);
    Filter.ResultItem item = result.getResultItems().get(0);
    Assert.assertEquals(precedingOutput + line.indexOf(coordinate), item.getHighlightStartOffset());
    Assert.assertEquals(precedingOutput + line.indexOf(coordinate) + coordinate.length(), item.getHighlightEndOffset());
  }

  public void testAnalyzeHeaderProducesNoLink() {
    assertNoLink("[INFO] --- dependency:3.7.0:analyze-only (default-cli) @ my-module ---");
  }

  public void testPlainTextProducesNoLink() {
    assertNoLink("[INFO] Building my-module 1.0.0");
    assertNoLink("[INFO] BUILD SUCCESS");
  }

  public void testLineWithColonButNotCoordinateProducesNoLink() {
    assertNoLink("[INFO] Total time:  3.456 s");
    assertNoLink("[INFO] Finished at: 2026-09-21T12:00:00+02:00");
  }

  public void testUnknownScopeProducesNoLink() {
    assertNoLink("[ERROR]    org.example:demo:jar:1.0.0:bogus");
  }

  public void testTooFewSegmentsProduceNoLink() {
    assertNoLink("[ERROR]    org.example:demo:compile");
  }

  private DependencyHyperlinkInfo assertSingleLink(String line, String expectedCoordinate) {
    Filter.Result result = myFilter.applyFilter(line, line.length());
    Assert.assertNotNull("Expected a hyperlink for: " + line, result);
    List<Filter.ResultItem> items = result.getResultItems();
    Assert.assertEquals(1, items.size());
    Filter.ResultItem item = items.get(0);
    int start = item.getHighlightStartOffset();
    int end = item.getHighlightEndOffset();
    Assert.assertEquals("Highlight should cover exactly the coordinate", expectedCoordinate, line.substring(start, end));
    Assert.assertTrue(item.getHyperlinkInfo() instanceof DependencyHyperlinkInfo);
    return (DependencyHyperlinkInfo)item.getHyperlinkInfo();
  }

  private void assertNoLink(String line) {
    Assert.assertNull("Did not expect a hyperlink for: " + line, myFilter.applyFilter(line, line.length()));
  }
}

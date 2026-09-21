// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.maven.project;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.OpenFileHyperlinkInfo;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase;
import org.junit.Assert;

/**
 * Verifies the deterministic, import-free parts of {@link MavenModelProblemFilter}: parsing of the
 * {@code @ [<modelId>, <source>, ]line N[, column N]} location suffix, the highlighted span, the resolved
 * line/column, and that non-location lines produce no hyperlink.
 * <p>
 * Resolution that requires an imported reactor (an "own pom" problem via the model header, a cross-model
 * problem via the model id) is covered by {@code MavenModelProblemNavigationTest}.
 */
public class MavenModelProblemFilterTest extends CodeInsightFixtureTestCase {

  private MavenModelProblemFilter myFilter;
  private VirtualFile myPom;
  private String myPomPath;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    myFilter = new MavenModelProblemFilter(myFixture.getProject());
    myPom = myFixture.configureByText("pom.xml",
                                      """
                                        <project>
                                            <modelVersion>4.0.0</modelVersion>
                                            <groupId>org.example</groupId>
                                            <artifactId>app</artifactId>
                                            <version>1.0</version>
                                            <build>
                                                <plugins>
                                                    <plugin>
                                                        <groupId>org.apache.maven.plugins</groupId>
                                                        <artifactId>maven-jar-plugin</artifactId>
                                                    </plugin>
                                                </plugins>
                                            </build>
                                        </project>
                                        """).getVirtualFile();
    myPomPath = myPom.getPath();
  }

  public void testNavigatesToExplicitSourcePathWithLineAndColumn() {
    String line = "[WARNING] 'build.plugins.plugin.version' for org.apache.maven.plugins:maven-jar-plugin is missing. "
                  + "@ org.example:app:pom:1.0, " + myPomPath + ", line 8, column 13";

    Filter.Result result = myFilter.applyFilter(line, line.length());
    Assert.assertNotNull("Expected a hyperlink for a model problem with an explicit source path", result);

    Filter.ResultItem item = single(result);
    Assert.assertTrue(item.getHyperlinkInfo() instanceof OpenFileHyperlinkInfo);
    OpenFileHyperlinkInfo info = (OpenFileHyperlinkInfo)item.getHyperlinkInfo();
    Assert.assertEquals(myPom, info.getVirtualFile());

    // the whole "@ ... line 8, column 13" tail is highlighted
    Assert.assertEquals(line.indexOf("@ "), item.getHighlightStartOffset());
    Assert.assertEquals(line.length(), item.getHighlightEndOffset());

    // line/column are reported 0-based to the descriptor
    assertLineColumn(info, 7, 12);
  }

  public void testNavigatesToExplicitSourcePathWithoutColumn() {
    String line = "[WARNING] expression '${pom.version}' is deprecated. @ org.example:app:pom:1.0, " + myPomPath + ", line 3";

    Filter.Result result = myFilter.applyFilter(line, line.length());
    Assert.assertNotNull(result);

    Filter.ResultItem item = single(result);
    OpenFileHyperlinkInfo info = (OpenFileHyperlinkInfo)item.getHyperlinkInfo();
    Assert.assertEquals(myPom, info.getVirtualFile());
    Assert.assertEquals(line.indexOf("@ "), item.getHighlightStartOffset());
    Assert.assertEquals(line.length(), item.getHighlightEndOffset());
    assertLineColumn(info, 2, 0);
  }

  public void testModelHeaderLineIsNotAHyperlink() {
    String line = "[WARNING] Some problems were encountered while building the effective model for org.example:app:jar:1.0";
    Assert.assertNull(myFilter.applyFilter(line, line.length()));
  }

  public void testUnrelatedLineIsNotAHyperlink() {
    Assert.assertNull(myFilter.applyFilter("[INFO] BUILD SUCCESS", 20));
  }

  public void testUnknownModelWithoutSourceIsNotAHyperlink() {
    // a model id that is not part of any imported project and no explicit source: nothing to navigate to
    String line = "[WARNING] 'x' is missing. @ org.example:unknown:jar:9, line 2, column 3";
    Assert.assertNull(myFilter.applyFilter(line, line.length()));
  }

  private static void assertLineColumn(OpenFileHyperlinkInfo info, int expectedLine, int expectedColumn) {
    OpenFileDescriptor descriptor = info.getDescriptor();
    Assert.assertNotNull(descriptor);
    if (descriptor.getLine() >= 0) {
      Assert.assertEquals(expectedLine, descriptor.getLine());
      Assert.assertEquals(expectedColumn, descriptor.getColumn());
      return;
    }
    // the descriptor is offset-based once the document is loaded: map the offset back to line/column
    Document document = FileDocumentManager.getInstance().getDocument(descriptor.getFile());
    Assert.assertNotNull(document);
    int offset = descriptor.getOffset();
    int actualLine = document.getLineNumber(offset);
    int actualColumn = offset - document.getLineStartOffset(actualLine);
    Assert.assertEquals(expectedLine, actualLine);
    Assert.assertEquals(expectedColumn, actualColumn);
  }

  private static Filter.ResultItem single(Filter.Result result) {
    Assert.assertEquals(1, result.getResultItems().size());
    return result.getResultItems().get(0);
  }
}

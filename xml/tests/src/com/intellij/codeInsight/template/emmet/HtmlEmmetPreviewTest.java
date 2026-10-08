// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.emmet;

import com.intellij.lang.html.HTMLLanguage;
import com.intellij.lang.injection.InjectedLanguageManager;
import com.intellij.lang.injection.MultiHostInjector;
import com.intellij.lang.injection.MultiHostRegistrar;
import com.intellij.psi.ElementManipulators;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiLanguageInjectionHost;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class HtmlEmmetPreviewTest extends EmmetPreviewTestBase {

  public void testPrimitiveAbbreviation() {
    myFixture.configureByText("test.html", "di<caret>");
    myFixture.type("v");
    assertNull(getPreview());
  }

  public void testPrimitiveAbbreviationWithEmptyClass() {
    myFixture.configureByText("test.html", "div<caret>");
    myFixture.type(".");
    assertNull(getPreview());
  }

  public void testAbbreviationWithNonEmptyClass() {
    myFixture.configureByText("test.html", "div.<caret>");
    myFixture.type("c");
    assertPreview("<div class=\"c\"></div>");
  }

  public void testAbbreviationWithNesting() {
    myFixture.configureByText("test.html", "div>di<caret>");
    myFixture.type("v");
    assertPreview("""
                    <div>
                        <div></div>
                    </div>""");
  }

  public void testAbbreviationWithFilter() {
    myFixture.configureByText("test.html", "div#id>div.class<caret>");
    myFixture.type("|c");
    assertPreview("""
                    <div id="id">
                        <div class="class"></div>
                        <!-- /.class -->
                    </div>
                    <!-- /#id -->""");
    myFixture.type("|s");
    assertPreview("""
                    <div id="id">
                        <div class="class"></div>
                        <!-- /.class -->
                    </div>
                    <!-- /#id -->""");
  }
  
  public void testPreviewXhtml() {
    myFixture.configureByText("test.xhtml", "div>b<caret>");
    myFixture.type("r");
    assertPreview("<div><br/></div>");
  }

  public void testNoPreviewInXmlAttributeWithoutInjection() {
    myFixture.configureByText("test.xml", "<root html=\"div.<caret>\"/>");
    myFixture.type("c");
    assertNoPreview();
  }

  public void testPreviewInInjectedHtml() {
    injectHtmlIntoAttribute();
    myFixture.setCaresAboutInjection(false);
    myFixture.configureByText("test.xml", "<root html=\"div.<caret>\"/>");
    PsiElement injected = InjectedLanguageManager.getInstance(getProject())
      .findInjectedElementAt(myFixture.getFile(), myFixture.getCaretOffset() - 1);
    assertNotNull(injected);
    assertEquals(HTMLLanguage.INSTANCE, injected.getContainingFile().getLanguage());

    myFixture.type("c");
    assertPreview("<div class='c'></div>");
  }

  private void injectHtmlIntoAttribute() {
    MultiHostInjector injector = new MultiHostInjector() {
      @Override
      public void getLanguagesToInject(@NotNull MultiHostRegistrar registrar, @NotNull PsiElement context) {
        if (!(context instanceof XmlAttributeValue value) || !(value.getParent() instanceof XmlAttribute attribute)) return;
        if (!"html".equals(attribute.getName())) return;
        registrar.startInjecting(HTMLLanguage.INSTANCE)
          .addPlace(null, null, (PsiLanguageInjectionHost)value, ElementManipulators.getValueTextRange(value))
          .doneInjecting();
      }

      @Override
      public @NotNull List<? extends Class<? extends PsiElement>> elementsToInjectIn() {
        return List.of(XmlAttributeValue.class);
      }
    };
    InjectedLanguageManager.getInstance(getProject()).registerMultiHostInjector(injector, getTestRootDisposable());
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection

import com.intellij.testFramework.LoggedErrorProcessor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.StringWriter
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

class SanitizingXmlWriterTest {
  /** A missed override reaches the delegate raw. */
  @Test
  fun `every write which carries a value is sanitized`() {
    val delegate = mock<XMLStreamWriter>()
    val writer = SanitizingXmlWriter(delegate)

    writer.writeAttribute("plain", DIRTY)
    writer.writeAttribute("urn:test", "namespaced", DIRTY)
    writer.writeAttribute("p", "urn:test", "prefixed", DIRTY)
    writer.writeCharacters(DIRTY)
    writer.writeCData(DIRTY)
    writer.writeComment(DIRTY)
    writer.writeProcessingInstruction("target", DIRTY)

    verify(delegate).writeAttribute("plain", CLEAN)
    verify(delegate).writeAttribute("urn:test", "namespaced", CLEAN)
    verify(delegate).writeAttribute("p", "urn:test", "prefixed", CLEAN)
    verify(delegate).writeCharacters(CLEAN)
    verify(delegate).writeCData(CLEAN)
    verify(delegate).writeComment(CLEAN)
    verify(delegate).writeProcessingInstruction("target", CLEAN)
  }

  /** A name states the structure of the document, so a replacement in it would break the document. */
  @Test
  fun `a name reaches the delegate unchanged`() {
    val delegate = mock<XMLStreamWriter>()
    val writer = SanitizingXmlWriter(delegate)

    writer.writeStartElement("element\u001Bname")
    writer.writeAttribute("attribute\u001Bname", "value")

    verify(delegate).writeStartElement("element\u001Bname")
    verify(delegate).writeAttribute("attribute\u001Bname", "value")
  }

  /**
   * A replacement changes the length, so the char array overload must not reach the delegate raw. The
   * array holds more than the written range, so an implementation which ignores the range fails.
   */
  @Test
  fun `the char array overload is sanitized`() {
    val target = StringWriter()
    val writer = SanitizingXmlWriter(XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(target))
    writer.writeStartElement("root")
    writer.writeCharacters("XX${DIRTY}YY".toCharArray(), 2, DIRTY.length)
    writer.writeEndElement()
    writer.flush()

    assertThat(target.toString()).contains(">$CLEAN<")
  }

  /** A legal value must not raise a warning, or every clean write would report one. */
  @Test
  fun `a legal value raises no warning`() {
    val warnings = collectWarnings {
      SanitizingXmlWriter(mock<XMLStreamWriter>()).writeAttribute("name", "a legal value")
    }

    assertThat(warnings).isEmpty()
  }

  /** The field alone places the value when a caller sets no context. */
  @Test
  fun `a warning names the field`() {
    val warnings = collectWarnings {
      SanitizingXmlWriter(mock<XMLStreamWriter>()).writeAttribute("displayName", DIRTY)
    }

    assertThat(warnings).singleElement().satisfies({
      assertThat(it).contains("@displayName").doesNotContain("/")
    })
  }

  /** A context names the record which holds the field, and the writer joins the two. */
  @Test
  fun `a warning names the context and the field`() {
    val warnings = collectWarnings {
      val writer = SanitizingXmlWriter(mock<XMLStreamWriter>())
      writer.loggingContext = "SomeRecord"
      writer.writeCharacters(DIRTY)
    }

    assertThat(warnings).singleElement().satisfies({
      assertThat(it).contains("SomeRecord/text()")
    })
  }

  /** A context comes from the same unchecked text, so it must not carry an illegal character to the log. */
  @Test
  fun `a context reaches the log sanitized`() {
    val warnings = collectWarnings {
      val writer = SanitizingXmlWriter(mock<XMLStreamWriter>())
      writer.loggingContext = "Before\u001BAfter"
      writer.writeAttribute("name", DIRTY)
    }

    assertThat(warnings).singleElement().satisfies({
      assertThat(it).contains("Before?After/@name").doesNotContain("\u001B")
    })
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

  private companion object {
    private const val DIRTY = "Before\u001BAfter"
    private const val CLEAN = "Before?After"
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInspection

import com.intellij.openapi.diagnostic.Logger
import org.jetbrains.annotations.ApiStatus
import javax.xml.stream.XMLStreamWriter

/**
 * Replaces every character which XML 1.0 cannot represent in a value which it writes.
 *
 * One such character makes the whole document unparseable. Use this writer for text which the caller does
 * not control, such as text from a message bundle, a user, or a third party.
 *
 * It covers an attribute value, character data, a CDATA section, a comment, and the data of a processing
 * instruction. It changes a value, never a name. An element name and an attribute name reach the delegate
 * unchanged.
 *
 * It does not cover a DTD, a namespace declaration, an entity reference, or the prolog. A caller which
 * writes unchecked text there must sanitize that text itself.
 *
 * A null value raises a [NullPointerException]. The delegate accepts a null text and writes nothing, which
 * hides a defect in a caller.
 *
 * A replacement raises a warning which names the field, such as `@displayName`. Set [loggingContext] to
 * add the name of the record which holds the field.
 */
@ApiStatus.Internal
class SanitizingXmlWriter(private val delegate: XMLStreamWriter) : XMLStreamWriter by delegate {
  /**
   * Names the record which supplies the values written now, such as its identifier. A warning reads it,
   * and nothing else does. Leave it null when no such name applies.
   */
  var loggingContext: String? = null

  override fun writeAttribute(localName: String, value: String) {
    delegate.writeAttribute(localName, sanitize(value, "@$localName"))
  }

  override fun writeAttribute(namespaceURI: String, localName: String, value: String) {
    delegate.writeAttribute(namespaceURI, localName, sanitize(value, "@$localName"))
  }

  override fun writeAttribute(prefix: String, namespaceURI: String, localName: String, value: String) {
    val field = if (prefix.isEmpty()) "@$localName" else "@$prefix:$localName"
    delegate.writeAttribute(prefix, namespaceURI, localName, sanitize(value, field))
  }

  override fun writeCharacters(text: String) {
    delegate.writeCharacters(sanitize(text, "text()"))
  }

  override fun writeCharacters(text: CharArray, start: Int, len: Int) {
    // a replacement changes the length, so the text goes through the overload which takes a string
    writeCharacters(String(text, start, len))
  }

  override fun writeCData(data: String) {
    delegate.writeCData(sanitize(data, "text()"))
  }

  override fun writeComment(data: String) {
    delegate.writeComment(sanitize(data, "comment()"))
  }

  override fun writeProcessingInstruction(target: String, data: String) {
    delegate.writeProcessingInstruction(target, sanitize(data, "processing-instruction()"))
  }

  private fun sanitize(value: String, field: String): String {
    val sanitized = ProblemDescriptorUtil.sanitizeIllegalXmlChars(value)
    if (sanitized !== value) {
      LOG.warn("Replaced illegal characters in " + place(field))
    }
    return sanitized
  }

  /** The context can hold an illegal character too, so it reaches the log sanitized. */
  private fun place(field: String): String {
    val context = loggingContext ?: return field
    return ProblemDescriptorUtil.sanitizeIllegalXmlChars(context) + "/" + field
  }

  private companion object {
    private val LOG = Logger.getInstance(SanitizingXmlWriter::class.java)
  }
}

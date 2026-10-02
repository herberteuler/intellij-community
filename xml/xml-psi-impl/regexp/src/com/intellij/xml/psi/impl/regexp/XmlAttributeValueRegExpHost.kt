// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.xml.psi.impl.regexp

import org.intellij.lang.regexp.DefaultRegExpPropertiesProvider
import org.intellij.lang.regexp.RegExpLanguageHost
import org.intellij.lang.regexp.psi.RegExpChar
import org.intellij.lang.regexp.psi.RegExpElement
import org.intellij.lang.regexp.psi.RegExpGroup
import org.intellij.lang.regexp.psi.RegExpNamedGroupRef

/**
 * Supplies the regular expression dialect for a regular expression in an XML attribute value.
 */
internal class XmlAttributeValueRegExpHost : RegExpLanguageHost {
  override fun characterNeedsEscaping(c: Char, isInClass: Boolean): Boolean = c == ']' || c == '}'

  override fun supportsPerl5EmbeddedComments(): Boolean = false

  override fun supportsPossessiveQuantifiers(context: RegExpElement?): Boolean = true

  override fun supportsPythonConditionalRefs(): Boolean = false

  override fun supportsNamedGroupSyntax(group: RegExpGroup?): Boolean = true

  override fun supportsNamedGroupRefSyntax(ref: RegExpNamedGroupRef?): Boolean = true

  override fun supportsExtendedHexCharacter(regExpChar: RegExpChar?): Boolean = false

  override fun isValidCategory(category: String): Boolean {
    if (category.startsWith("Is")) {
      try {
        return Character.UnicodeBlock.forName(category.substring(2)) != null
      }
      catch (_: IllegalArgumentException) {
      }
    }
    return DefaultRegExpPropertiesProvider.getInstance().allKnownProperties.any { it[0] == category }
  }

  override fun getAllKnownProperties(): Array<Array<String>> = DefaultRegExpPropertiesProvider.getInstance().allKnownProperties

  override fun getPropertyDescription(name: String?): String? = DefaultRegExpPropertiesProvider.getInstance().getPropertyDescription(name)

  override fun getKnownCharacterClasses(): Array<Array<String>> = DefaultRegExpPropertiesProvider.getInstance().knownCharacterClasses
}

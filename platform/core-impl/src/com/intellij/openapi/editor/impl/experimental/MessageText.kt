// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.experimental

import com.intellij.openapi.util.text.StringUtil

/** The longest fragment that a message quotes in full, the ellipsis included. */
private const val MAX_QUOTED_CHARS = 40

/** How many characters of the tail a shortened fragment keeps. */
private const val QUOTED_TAIL_CHARS = 12

/**
 * The fragment as a quoted string that is safe to put in a message.
 *
 * An insert fragment can be a whole pasted file, and `toString` reaches exception messages
 * and the log. So a long fragment gives up its middle to an ellipsis and reports its own
 * length instead. A line break becomes an escape, which keeps the message on one line.
 */
internal fun CharSequence.quotedForMessage(): String {
  val text = toString()
  val shortened = StringUtil.shortenTextWithEllipsis(text, MAX_QUOTED_CHARS, QUOTED_TAIL_CHARS)
  val quoted = "\"${StringUtil.escapeStringCharacters(shortened)}\""
  return if (text.length <= MAX_QUOTED_CHARS) {
    quoted
  } else {
    "$quoted (${text.length} chars)"
  }
}

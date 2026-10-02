// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.problemView.security.frontend

import org.jetbrains.annotations.NonNls

/**
 * One row of [ProblemsViewSubTabSelector]: everything the list holding the rows knows about one sub-tab.
 *
 * Immutable, because it is a list element: a sub-tab that has something new to say about itself becomes a new item put
 * in the place of the old one, which is how the list learns that the row changed.
 */
internal class ProblemsViewSubTabItem(
  @param:NonNls val id: String,
  val presentation: ProblemsViewSubTabPresentation,
)

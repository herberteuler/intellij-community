// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.dbt.common

import com.intellij.python.dbt.common.icons.DbtCommonIcons
import com.jetbrains.python.run.PyFrameworkIconProvider
import javax.swing.Icon

internal class DbtFrameworkIconProvider : PyFrameworkIconProvider {
  override val configurationTypeId: String = "DbtRunConfiguration"
  override val icon: Icon
    get() = DbtCommonIcons.Dbt
}

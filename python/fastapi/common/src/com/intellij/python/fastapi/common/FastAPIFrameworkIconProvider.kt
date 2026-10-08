// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.fastapi.common

import com.intellij.python.fastapi.common.icons.FastAPICommonIcons
import com.jetbrains.python.run.PyFrameworkIconProvider
import javax.swing.Icon

internal class FastAPIFrameworkIconProvider : PyFrameworkIconProvider {
  override val configurationTypeId: String = "Python.FastAPI"
  override val icon: Icon
    get() = FastAPICommonIcons.FastAPI
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.django.common

import com.intellij.python.django.common.icons.DjangoCommonIcons
import com.jetbrains.python.run.PyFrameworkIconProvider
import javax.swing.Icon

internal class DjangoFrameworkIconProvider : PyFrameworkIconProvider {
  override val configurationTypeId: String = "Python.DjangoServer"
  override val icon: Icon
    get() = DjangoCommonIcons.Django
}

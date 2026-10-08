// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.django.common

import com.intellij.python.django.common.icons.DjangoCommonIcons
import com.jetbrains.python.PyBundle
import com.jetbrains.python.run.PythonLockedRunConfigurationTypeBase
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class DjangoServerLockedRunConfigurationType : PythonLockedRunConfigurationTypeBase(
  "Python.DjangoServer",
  PyBundle.message("python.run.configuration.django.name"),
  baseIconSupplier = { DjangoCommonIcons.Django },
)

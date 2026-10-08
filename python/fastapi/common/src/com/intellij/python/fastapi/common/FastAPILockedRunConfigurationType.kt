// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.fastapi.common

import com.intellij.python.fastapi.common.icons.FastAPICommonIcons
import com.jetbrains.python.PyBundle
import com.jetbrains.python.run.PythonLockedRunConfigurationTypeBase
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class FastAPILockedRunConfigurationType : PythonLockedRunConfigurationTypeBase(
  "Python.FastAPI",
  PyBundle.message("python.run.configuration.fastapi.name"),
  baseIconSupplier = { FastAPICommonIcons.FastAPI },
)

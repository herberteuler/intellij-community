// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.dbt.common

import com.intellij.python.dbt.common.icons.DbtCommonIcons
import com.jetbrains.python.PyBundle
import com.jetbrains.python.run.PythonLockedRunConfigurationTypeBase
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class DbtRunLockedConfigurationType : PythonLockedRunConfigurationTypeBase(
  "DbtRunConfiguration",
  PyBundle.message("python.run.configuration.dbt.name"),
  baseIconSupplier = { DbtCommonIcons.Dbt },
)

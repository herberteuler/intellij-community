// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.flask.common

import com.intellij.python.flask.common.icons.FlaskCommonIcons
import com.jetbrains.python.PyBundle
import com.jetbrains.python.run.PythonLockedRunConfigurationTypeBase
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
class FlaskServerLockedRunConfigurationType : PythonLockedRunConfigurationTypeBase(
  "Python.FlaskServer",
  PyBundle.message("flask.name"),
  baseIconSupplier = { FlaskCommonIcons.Flask },
)

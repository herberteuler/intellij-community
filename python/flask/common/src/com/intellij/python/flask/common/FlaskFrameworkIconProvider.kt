// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.flask.common

import com.intellij.python.flask.common.icons.FlaskCommonIcons
import com.jetbrains.python.run.PyFrameworkIconProvider
import javax.swing.Icon

internal class FlaskFrameworkIconProvider : PyFrameworkIconProvider {
  override val configurationTypeId: String = "Python.FlaskServer"
  override val icon: Icon
    get() = FlaskCommonIcons.Flask
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.community.common

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.util.PlatformUtils
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
fun isPyCharmFreeMode(): Boolean =
  PlatformUtils.isPyCharm() && PluginManagerCore.isDisabled(PluginManagerCore.ULTIMATE_PLUGIN_ID)

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.jetbrains.annotations.ApiStatus

private val rdLightProjectKey = Key<Boolean>("rd.project.lightProject.frontend")

@get:ApiStatus.Internal
val Project.isRdLightFrontend: Boolean
  get() = getUserData(rdLightProjectKey) ?: false

@ApiStatus.Internal
fun Project.setIsRdLightFrontend(value: Boolean) {
  putUserData(rdLightProjectKey, value)
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:ApiStatus.Internal

package com.intellij.openapi.application

import com.intellij.openapi.util.BuildNumber
import org.jetbrains.annotations.ApiStatus

/**
 * Returns the prefix for [com.intellij.openapi.application.PathManager.getPathsSelector] used by a product with the specified [productCode].
 * 
 * The caller appends the major version, for example 2024.1, to the prefix.
 * The result is the default name of the settings, caches, plugins and logs directories.
 */
fun getPathSelectorPrefixByProductCode(productCode: String): String? {
  return PRODUCT_CODES_TO_PREFIXES[productCode]
}

/**
 * Returns the [com.intellij.openapi.application.PathManager.getPathsSelector] used by a product with the specified [buildNumber].
 * 
 * The function assumes the default naming scheme.
 * In this scheme, the product-specific prefix comes before the major version, for example 2024.1.
 */
fun getPathSelectorByBuildNumber(buildNumber: BuildNumber): String? {
  val prefix = PRODUCT_CODES_TO_PREFIXES[buildNumber.productCode] ?: return null
  val baseline = buildNumber.baselineVersion
  // A 242.* build corresponds to the 2024.2 version.
  val majorVersionNumber = "20${baseline / 10}.${baseline % 10}"
  return "$prefix$majorVersionNumber"
}

private val PRODUCT_CODES_TO_PREFIXES = mapOf(
  "IU" to "IntelliJIdea",
  "IC" to "IdeaIC",
  "IE" to "IdeaIE",
  "RM" to "RubyMine",
  "PY" to "PyCharm",
  "PC" to "PyCharmCE",
  "PE" to "PyCharmEdu",
  "PS" to "PhpStorm",
  "WS" to "WebStorm",
  "OC" to "AppCode",
  "CL" to "CLion",
  "DB" to "DataGrip",
  "RD" to "Rider",
  "GO" to "GoLand",
  "AI" to "AndroidStudio",
  "CWMG" to "CodeWithMeGuest",
  "JBC" to "JetBrainsClient",
  "RR" to "RustRover"
)

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.django.common

import com.intellij.facet.impl.invalid.FacetIgnorer
import com.intellij.facet.impl.invalid.InvalidFacet

internal class DjangoFacetIgnorer : FacetIgnorer {
  override fun isIgnored(facet: InvalidFacet): Boolean = facet.name == "Django"
}

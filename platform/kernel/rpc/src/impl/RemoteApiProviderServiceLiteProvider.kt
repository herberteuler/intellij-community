// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.rpc.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.platform.rpc.RemoteApiProviderService
import com.intellij.platform.rpc.lite.RemoteApiProvider
import fleet.rpc.RemoteApi
import fleet.rpc.RemoteApiDescriptor
import org.jetbrains.annotations.ApiStatus

/**
 * The lite provider that delegates to [RemoteApiProviderService], contributed to [RemoteApiProvider.EP_NAME].
 * `intellij.platform.rpc` registers this provider. The module is not available in a light mode,
 * so the provider appears together with a [RemoteApiProviderService] implementation.
 * The DAP language server does not load `intellij.platform.rpc.xml`, so `dap.xml` registers the provider separately.
 * The service is constructed on the first call.
 */
@ApiStatus.Internal
class RemoteApiProviderServiceLiteProvider : RemoteApiProvider {
  override fun <T : RemoteApi<Unit>> tryResolve(descriptor: RemoteApiDescriptor<T>): T? {
    return service<RemoteApiProviderService>().tryResolve(descriptor)
  }

  override suspend fun <T : RemoteApi<Unit>> resolve(descriptor: RemoteApiDescriptor<T>): T {
    return serviceAsync<RemoteApiProviderService>().resolve(descriptor)
  }
}

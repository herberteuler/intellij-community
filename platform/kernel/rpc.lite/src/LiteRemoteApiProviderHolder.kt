// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.rpc.lite

import com.intellij.openapi.diagnostic.thisLogger
import fleet.rpc.RemoteApi
import fleet.rpc.RemoteApiDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.jetbrains.annotations.ApiStatus

/**
 * Holds the [RemoteApiProvider] of the process once it is installed. The first installed provider stays;
 * a caller that awaits before the installation resumes with it.
 */
@ApiStatus.Internal
class LiteRemoteApiProviderHolder {
  private val provider: CompletableDeferred<RemoteApiProvider> = CompletableDeferred()

  fun install(remoteApiProvider: RemoteApiProvider) {
    if (provider.isCompleted) {
      thisLogger().error(
        "Remote API provider is already installed. " +
        "Tried to setup ${remoteApiProvider::class.java.simpleName}, " +
        "but ${provider.getCompleted().javaClass.simpleName} is installed"
      )
    }
    provider.complete(remoteApiProvider)
  }

  fun isConnected(): Boolean {
    return provider.isCompleted
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  fun <T : RemoteApi<Unit>> tryResolve(descriptor: RemoteApiDescriptor<T>): T? {
    if (!provider.isCompleted) return null
    return provider.getCompleted().tryResolve(descriptor)
  }

  suspend fun <T : RemoteApi<Unit>> awaitConnectionAndResolve(descriptor: RemoteApiDescriptor<T>): T {
    return provider.await().resolve(descriptor)
  }
}

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.rpc.lite

import com.intellij.openapi.extensions.ExtensionPointName
import fleet.rpc.RemoteApi
import fleet.rpc.RemoteApiDescriptor
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
interface RemoteApiProvider {
  suspend fun <T : RemoteApi<Unit>> resolve(descriptor: RemoteApiDescriptor<T>): T

  fun <T : RemoteApi<Unit>> tryResolve(descriptor: RemoteApiDescriptor<T>): T?

  companion object {
    /** The provider of the process. A module that owns an RPC backend contributes one, and [LiteRemoteApiProviderService] installs the first. */
    val EP_NAME: ExtensionPointName<RemoteApiProvider> = ExtensionPointName.create("com.intellij.platform.rpc.lite.remoteApiProvider")
  }
}

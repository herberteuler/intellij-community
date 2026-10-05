// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.rpc.tests

import com.intellij.platform.rpc.lite.RemoteApiProvider
import fleet.rpc.RemoteApi
import fleet.rpc.RemoteApiDescriptor
import fleet.rpc.RpcSignature
import org.assertj.core.api.Assertions.assertThat

/** A remote API that is never called; the tests check only that it is resolved. */
internal class LiteTestApi : RemoteApi<Unit>

/** A lite provider that answers [LiteTestApiDescriptor] with [api], or fails with [failure]. */
internal class LiteTestApiProvider(private val api: LiteTestApi?, private val failure: IllegalStateException? = null) : RemoteApiProvider {
  override fun <T : RemoteApi<Unit>> tryResolve(descriptor: RemoteApiDescriptor<T>): T? {
    assertThat(descriptor).isSameAs(LiteTestApiDescriptor)
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return api as T?
  }

  override suspend fun <T : RemoteApi<Unit>> resolve(descriptor: RemoteApiDescriptor<T>): T {
    return tryResolve(descriptor) ?: error("The test API is not registered")
  }
}

internal object LiteTestApiDescriptor : RemoteApiDescriptor<LiteTestApi> {
  override fun getApiFqn(): String = LiteTestApi::class.java.name

  override fun getSignature(methodName: String): RpcSignature = error("The test does not invoke RPC methods")

  override fun clientStub(proxy: suspend (String, Array<Any?>) -> Any?): LiteTestApi = error("The test does not create RPC stubs")

  override suspend fun call(impl: LiteTestApi, methodName: String, args: Array<Any?>): Nothing = error("The test does not invoke RPC methods")
}

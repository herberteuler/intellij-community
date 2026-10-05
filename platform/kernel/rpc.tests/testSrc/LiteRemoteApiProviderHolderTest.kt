// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.rpc.tests

import com.intellij.platform.rpc.lite.LiteRemoteApiProviderHolder
import com.intellij.testFramework.common.timeoutRunBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows

@Timeout(30)
internal class LiteRemoteApiProviderHolderTest {
  @Test
  fun `provider is unavailable until installed`() {
    val holder = LiteRemoteApiProviderHolder()

    assertThat(holder.isConnected()).isFalse()
    assertThat(holder.tryResolve(LiteTestApiDescriptor)).isNull()
  }

  @Test
  fun `installation releases all waiting callers`(): Unit = timeoutRunBlocking {
    val holder = LiteRemoteApiProviderHolder()
    val requests = List(2) {
      async(start = CoroutineStart.UNDISPATCHED) {
        holder.awaitConnectionAndResolve(LiteTestApiDescriptor)
      }
    }
    assertThat(requests).allMatch { !it.isCompleted }
    assertThat(holder.isConnected()).isFalse()

    val api = LiteTestApi()
    holder.install(LiteTestApiProvider(api))

    assertThat(holder.isConnected()).isTrue()
    for (request in requests) {
      assertThat(request.await()).isSameAs(api)
    }
    assertThat(holder.tryResolve(LiteTestApiDescriptor)).isSameAs(api)
    assertThat(holder.awaitConnectionAndResolve(LiteTestApiDescriptor)).isSameAs(api)
  }

  @Test
  fun `cancelling a caller leaves the provider available`(): Unit = timeoutRunBlocking {
    val holder = LiteRemoteApiProviderHolder()
    val cancelledRequest = async(start = CoroutineStart.UNDISPATCHED) {
      holder.awaitConnectionAndResolve(LiteTestApiDescriptor)
    }
    val remainingRequest = async(start = CoroutineStart.UNDISPATCHED) {
      holder.awaitConnectionAndResolve(LiteTestApiDescriptor)
    }

    cancelledRequest.cancelAndJoin()

    assertThat(holder.isConnected()).isFalse()
    assertThat(remainingRequest.isCompleted).isFalse()
    val api = LiteTestApi()
    holder.install(LiteTestApiProvider(api))
    assertThat(remainingRequest.await()).isSameAs(api)
  }

  @Test
  fun `the first installed provider remains active`(): Unit = timeoutRunBlocking {
    val holder = LiteRemoteApiProviderHolder()
    val api = LiteTestApi()
    holder.install(LiteTestApiProvider(api))
    holder.install(LiteTestApiProvider(LiteTestApi()))

    assertThat(holder.tryResolve(LiteTestApiDescriptor)).isSameAs(api)
    assertThat(holder.awaitConnectionAndResolve(LiteTestApiDescriptor)).isSameAs(api)
  }

  @Test
  fun `a missing API stays unavailable after installation`(): Unit = timeoutRunBlocking {
    val holder = LiteRemoteApiProviderHolder()
    holder.install(LiteTestApiProvider(null))

    assertThat(holder.isConnected()).isTrue()
    assertThat(holder.tryResolve(LiteTestApiDescriptor)).isNull()
    assertThrows<IllegalStateException> {
      holder.awaitConnectionAndResolve(LiteTestApiDescriptor)
    }
  }

  @Test
  fun `provider failures reach the caller`(): Unit = timeoutRunBlocking {
    val holder = LiteRemoteApiProviderHolder()
    val failure = IllegalStateException("The test provider failed")
    holder.install(LiteTestApiProvider(null, failure))

    assertThat(assertThrows<IllegalStateException> {
      holder.tryResolve(LiteTestApiDescriptor)
    }).isSameAs(failure)
    assertThat(assertThrows<IllegalStateException> {
      holder.awaitConnectionAndResolve(LiteTestApiDescriptor)
    }).isSameAs(failure)
  }
}

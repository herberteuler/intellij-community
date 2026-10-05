// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.kernel.backend.baseline

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.platform.kernel.KernelService
import com.intellij.platform.kernel.util.handleEntityTypes
import com.intellij.platform.kernel.util.updateDbInTheEventDispatchThread
import com.intellij.platform.util.coroutines.childScope
import com.jetbrains.rhizomedb.DbContext
import fleet.kernel.change
import fleet.kernel.rebase.initWorkspaceClock
import fleet.kernel.transactor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class BackendKernelService(coroutineScope: CoroutineScope) : KernelService {

  override val kernelCoroutineScope: CompletableDeferred<CoroutineScope> = CompletableDeferred()

  init {
    thisLogger().info("Backend started Kernel")
    coroutineScope.launch {
      change {
        initWorkspaceClock()
      }
      handleEntityTypes(transactor(), this)
      // Create a supervisor child scope to avoid kernel coroutine getting canceled by exceptions coming under `withKernel`
      kernelCoroutineScope.complete(this.childScope(name = "KernelCoroutineScope", supervisor = true))
      updateDbInTheEventDispatchThread(DbContext.threadBound)
    }
  }
}
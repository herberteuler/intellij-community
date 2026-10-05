// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.repo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Emits [Unit] on every change of the receiver repository.
 */
fun GitRepository.changesSignalFlow(): Flow<Unit> = channelFlow {
  project.messageBus
    .connect(this)
    .subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
      if (it == this@changesSignalFlow) {
        trySend(Unit)
      }
    })
  awaitClose()
}

/**
 * Shares [infoFlow] in [cs] and starts with the current [GitRepository.info].
 */
fun GitRepository.infoStateIn(cs: CoroutineScope): StateFlow<GitRepoInfo> = infoFlow().stateIn(cs, SharingStarted.Eagerly, info)

/**
 * Emits the current [GitRepository.info] and then the new info on every change of the receiver repository.
 */
fun GitRepository.infoFlow(): Flow<GitRepoInfo> = channelFlow {
  project.messageBus
    .connect(this)
    .subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
      if (it == this@infoFlow) {
        trySend(it.info)
      }
    })
  send(info)
  awaitClose()
}

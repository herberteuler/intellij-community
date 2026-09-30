// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab

import com.intellij.collaboration.async.withInitial
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsChangeListener
import com.intellij.openapi.project.Project
import git4idea.remote.hosting.GitHostingUrlUtil
import git4idea.remote.hosting.GitHostingUrlUtil.getUriFromRemoteUrl
import git4idea.remote.hosting.HostedGitRepositoriesManager
import git4idea.remote.hosting.discoverServers
import git4idea.remote.hosting.gitRemotesFlow
import git4idea.remote.hosting.mapToServers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import org.jetbrains.plugins.gitlab.api.GitLabServerPath
import org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccountManager
import org.jetbrains.plugins.gitlab.util.GitLabProjectMapping

interface GitLabProjectsManager : HostedGitRepositoriesManager<GitLabProjectMapping>

internal class GitLabProjectsManagerImpl(project: Project, cs: CoroutineScope) : GitLabProjectsManager {

  private fun settingChangeEventsFlow(): Flow<Unit> = callbackFlow {
    val connection = ApplicationManager.getApplication().messageBus.connect(this)
    connection.subscribe(AdvancedSettingsChangeListener.TOPIC, object : AdvancedSettingsChangeListener {
      override fun advancedSettingChanged(id: String, oldValue: Any, newValue: Any) {
        if (id == GITLAB_ALIASES_SETTING_ID) trySend(Unit)
      }
    })
    awaitClose()
  }
  private fun createAliasesFlow(): Flow<Set<String>> = settingChangeEventsFlow().withInitial(Unit).map { readAliasesFromSettings() }

  override val knownRepositoriesState: StateFlow<Set<GitLabProjectMapping>> by lazy {
    val gitRemotesFlow = gitRemotesFlow(project).distinctUntilChanged()
    val defaultServerAliasesFlow = createAliasesFlow().distinctUntilChanged()

    val accountsServersFlow = service<GitLabAccountManager>().accountsState.map { accounts ->
      mutableSetOf(GitLabServerPath.DEFAULT_SERVER) + accounts.map { it.server }
    }.distinctUntilChanged()

    val notDefaultRemotes = gitRemotesFlow.combine(defaultServerAliasesFlow) { remotes, aliases ->
      remotes.filter { getUriFromRemoteUrl(it.url)?.host?.let { host -> aliases.contains(host) } == false }
    }

    val discoveredServersFlow = notDefaultRemotes.discoverServers(accountsServersFlow) { remote ->
      GitHostingUrlUtil.findServerAt(LOG, remote) {
        val server = GitLabServerPath(it.toString())
        val isGitLabServer = service<GitLabServersManager>().checkIsGitLabServer(server)
        if (isGitLabServer) server else null
      }
    }.runningFold(emptySet<GitLabServerPath>()) { accumulator, value ->
      accumulator + value
    }.distinctUntilChanged()

    val serversFlow = accountsServersFlow.combine(discoveredServersFlow) { servers1, servers2 ->
      servers1 + servers2
    }

    val knownRepositoriesFlow = gitRemotesFlow.mapToServers(serversFlow, defaultServerAliasesFlow) { servers, aliases, remote ->
      val remoteUri = getUriFromRemoteUrl(remote.url) ?: return@mapToServers null
      if (aliases.contains(remoteUri.host)) {
        GitLabProjectMapping.create(GitLabServerPath.DEFAULT_SERVER, remote)
      }
      else servers.find { GitHostingUrlUtil.match(it.toURI(), remote.url) }?.let {
        GitLabProjectMapping.create(it, remote)
      }
    }.onEach {
      LOG.debug("New list of known repos: $it")
    }

    knownRepositoriesFlow.stateIn(cs, SharingStarted.Eagerly, emptySet())
  }

  private fun readAliasesFromSettings(): Set<String> =
    AdvancedSettings.getString(GITLAB_ALIASES_SETTING_ID)
      .split(',')
      .asSequence()
      .map { it.trim().lowercase() }
      .filter { it.isNotEmpty() }
      .toSet()

  companion object {
    private val LOG = logger<GitLabProjectsManager>()

    private const val GITLAB_ALIASES_SETTING_ID = "gitlab.aliases"
  }
}
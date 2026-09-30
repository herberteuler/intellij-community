// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab

import com.intellij.collaboration.async.withInitial
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsChangeListener
import com.intellij.openapi.project.Project
import com.intellij.util.io.URLUtil
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitHostingUrlUtil
import git4idea.remote.hosting.GitHostingUrlUtil.getUriFromRemoteUrl
import git4idea.remote.hosting.HostedGitRepositoriesManager
import git4idea.remote.hosting.discoverServers
import git4idea.remote.hosting.gitRemotesFlow
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
import java.net.URI

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

  private fun createAliasesFlow(): Flow<Map<String, GitLabServerPath>> =
    settingChangeEventsFlow().withInitial(Unit).map { parseGitLabHostAliases(AdvancedSettings.getString(GITLAB_ALIASES_SETTING_ID)) }

  override val knownRepositoriesState: StateFlow<Set<GitLabProjectMapping>> by lazy {
    val gitRemotesFlow = gitRemotesFlow(project).distinctUntilChanged()
    val aliasesFlow = createAliasesFlow().distinctUntilChanged()

    val accountsServersFlow = service<GitLabAccountManager>().accountsState.map { accounts ->
      mutableSetOf(GitLabServerPath.DEFAULT_SERVER) + accounts.map { it.server }
    }.distinctUntilChanged()

    val notAliasedRemotes = gitRemotesFlow.combine(aliasesFlow) { remotes, aliases ->
      remotes.filter { remote -> remote.host?.let { it !in aliases } == true }
    }

    val discoveredServersFlow = notAliasedRemotes.discoverServers(accountsServersFlow) { remote ->
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

    val knownRepositoriesFlow = combine(
      gitRemotesFlow, serversFlow, accountsServersFlow, aliasesFlow
    ) { remotes, servers, accountServers, aliases ->
      remotes.mapNotNullTo(mutableSetOf()) { remote ->
        val host = remote.host ?: return@mapNotNullTo null
        val server = aliases[host]?.let { accountServers.findServerForAlias(it) }
                     ?: servers.find { GitHostingUrlUtil.match(it.toURI(), remote.url) }
        server?.let { GitLabProjectMapping.create(it, remote) }
      }
    }.onEach {
      LOG.debug("New list of known repos: $it")
    }

    knownRepositoriesFlow.stateIn(cs, SharingStarted.Eagerly, emptySet())
  }

  private val GitRemoteUrlCoordinates.host: String?
    get() = getUriFromRemoteUrl(url)?.host?.lowercase()

  /**
   * Finds the server for [aliasServer], the server from the alias setting.
   * The function prefers the account server that is equal to [aliasServer].
   * Then it takes the first account server with the same host, so the server URL comes from the account.
   * The function does not check the credentials of the account.
   * Without such an account, the function returns [aliasServer].
   */
  private fun Set<GitLabServerPath>.findServerForAlias(aliasServer: GitLabServerPath): GitLabServerPath =
    find { it == aliasServer } ?: find { it.toURI().host.equals(aliasServer.toURI().host, true) } ?: aliasServer

  companion object {
    private val LOG = logger<GitLabProjectsManager>()

    private const val GITLAB_ALIASES_SETTING_ID = "gitlab.aliases"
  }
}

/**
 * Parses the value of the `gitlab.aliases` setting into a map from an alias host to the GitLab server.
 *
 * The value is a comma-separated list of `<alias>=<server>` and `<alias>` entries.
 * The server is a URL, such as `https://git.example.com:8443/gitlab`, or a host that gets the `https` scheme.
 * An entry without a server maps the alias to [GitLabServerPath.DEFAULT_SERVER].
 * The function ignores an entry with an invalid server.
 */
internal fun parseGitLabHostAliases(value: String): Map<String, GitLabServerPath> =
  value.split(',').mapNotNull { entry ->
    val alias = entry.substringBefore('=').trim().lowercase()
    if (alias.isEmpty()) return@mapNotNull null
    parseAliasServer(entry.substringAfter('=', "").trim())?.let { alias to it }
  }.toMap()

private fun parseAliasServer(value: String): GitLabServerPath? {
  if (value.isEmpty()) return GitLabServerPath.DEFAULT_SERVER

  val url = if (value.contains(URLUtil.SCHEME_SEPARATOR)) value else "${URLUtil.HTTPS_PROTOCOL}${URLUtil.SCHEME_SEPARATOR}$value"
  return runCatching {
    val uri = URI(url.trimEnd('/'))
    val host = requireNotNull(uri.host).lowercase()
    // Only the scheme and the host are case-insensitive, so the path keeps its case.
    GitLabServerPath(URI(uri.scheme.lowercase(), null, host, uri.port, uri.path, null, null).toString())
  }.getOrNull()
}
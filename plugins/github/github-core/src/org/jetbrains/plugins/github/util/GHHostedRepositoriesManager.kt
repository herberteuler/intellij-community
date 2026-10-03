// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.util

import com.intellij.collaboration.async.withInitial
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsChangeListener
import com.intellij.openapi.project.Project
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitHostingUrlUtil
import git4idea.remote.hosting.GitHostingUrlUtil.getUriFromRemoteUrl
import git4idea.remote.hosting.HostedGitRepositoriesManager
import git4idea.remote.hosting.discoverServers
import git4idea.remote.hosting.gitRemotesFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.plugins.github.api.GithubServerPath
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager
import org.jetbrains.plugins.github.exceptions.GithubParseException

@Service(Service.Level.PROJECT)
class GHHostedRepositoriesManager(project: Project, cs: CoroutineScope) : HostedGitRepositoriesManager<GHGitRepositoryMapping> {

  @ExperimentalCoroutinesApi
  @VisibleForTesting
  internal val knownRepositoriesFlow: Flow<Set<GHGitRepositoryMapping>> = createKnownRepositoriesFlow(project)


  private fun settingChangeEventsFlow(): Flow<Unit> = callbackFlow {
    val connection = ApplicationManager.getApplication().messageBus.connect(this)
    connection.subscribe(AdvancedSettingsChangeListener.TOPIC, object : AdvancedSettingsChangeListener {
      override fun advancedSettingChanged(id: String, oldValue: Any, newValue: Any) {
        if (id == GITHUB_ALIASES_SETTING_ID) trySend(Unit)
      }
    })
    awaitClose()
  }

  private fun createAliasesFlow(): Flow<Map<String, GithubServerPath>> =
    settingChangeEventsFlow().withInitial(Unit).map { parseGitHubHostAliases(AdvancedSettings.getString(GITHUB_ALIASES_SETTING_ID)) }

  private fun createKnownRepositoriesFlow(project: Project): Flow<Set<GHGitRepositoryMapping>> {
    val gitRemotesFlow = gitRemotesFlow(project).distinctUntilChanged()
    val aliasesFlow = createAliasesFlow().distinctUntilChanged()

    val accountsServersFlow = service<GHAccountManager>().accountsState.map { accounts ->
      mutableSetOf(GithubServerPath.DEFAULT_SERVER) + accounts.map { it.server }
    }.distinctUntilChanged()

    val notAliasedRemotes = gitRemotesFlow.combine(aliasesFlow) { remotes, aliases ->
      remotes.filter { remote -> remote.host?.let { it !in aliases } == true }
    }
    val discoveredServersFlow = notAliasedRemotes.discoverServers(accountsServersFlow) { remote ->
      GitHostingUrlUtil.findServerAt(LOG, remote) {
        val server = GithubServerPath.from(it.toString())
        if (server.isGheDataResidency) return@findServerAt server

        val metadata = runCatching { serviceAsync<GHEnterpriseServerMetadataLoader>().loadMetadata(server) }.getOrNull()
        if (metadata != null) server else null
      }
    }.runningFold(emptySet<GithubServerPath>()) { accumulator, value ->
      accumulator + value
    }.distinctUntilChanged()

    val serversFlow = accountsServersFlow.combine(discoveredServersFlow) { servers1, servers2 ->
      servers1 + servers2
    }

    return combine(gitRemotesFlow, serversFlow, accountsServersFlow, aliasesFlow) { remotes, servers, accountServers, aliases ->
      remotes.mapNotNullTo(mutableSetOf()) { remote ->
        val host = remote.host ?: return@mapNotNullTo null
        val server = aliases[host]?.let { accountServers.findServerForAlias(it) }
                     ?: servers.find { GitHostingUrlUtil.match(it.toURI(), remote.url) }
        server?.let { GHGitRepositoryMapping.create(it, remote) }
      }
    }.onEach {
      LOG.debug("New list of known repos: $it")
    }
  }

  @ExperimentalCoroutinesApi
  override val knownRepositoriesState: StateFlow<Set<GHGitRepositoryMapping>> =
    knownRepositoriesFlow.stateIn(cs, getStateSharingStartConfig(), emptySet())

  private val GitRemoteUrlCoordinates.host: String?
    get() = getUriFromRemoteUrl(url)?.host?.lowercase()

  /**
   * Prefers the account server with the same URI, then the first account server with the same host.
   * Uses [aliasServer] when no account server matches.
   */
  private fun Set<GithubServerPath>.findServerForAlias(aliasServer: GithubServerPath): GithubServerPath =
    find { it.toURI() == aliasServer.toURI() } ?: find { it.host.equals(aliasServer.host, true) } ?: aliasServer

  companion object {
    private val LOG = logger<GHHostedRepositoriesManager>()

    private const val GITHUB_ALIASES_SETTING_ID = "github.aliases"

    private fun getStateSharingStartConfig() =
      if (ApplicationManager.getApplication().isUnitTestMode) SharingStarted.Eagerly else SharingStarted.Lazily
  }
}

/**
 * Parses the value of the `github.aliases` setting into a map from an alias host to the GitHub server.
 *
 * The value is a comma-separated list of `<alias>=<server>` and `<alias>` entries.
 * The server is a URL, such as `https://git.example.com:8443/github`, or a host.
 * An entry without a server maps the alias to [GithubServerPath.DEFAULT_SERVER].
 * The function ignores an entry with an invalid server.
 */
internal fun parseGitHubHostAliases(value: String): Map<String, GithubServerPath> =
  value.split(',').mapNotNull { entry ->
    val alias = entry.substringBefore('=').trim().lowercase()
    if (alias.isEmpty()) return@mapNotNull null
    parseAliasServer(entry.substringAfter('=', "").trim())?.let { alias to it }
  }.toMap()

private fun parseAliasServer(value: String): GithubServerPath? {
  if (value.isEmpty()) return GithubServerPath.DEFAULT_SERVER

  val server = try {
    GithubServerPath.from(value.trimEnd('/'))
  }
  catch (_: GithubParseException) {
    return null
  }
  // A host with an invalid character passes the parser, but it fails to build the URI.
  if (runCatching { server.toURI() }.isFailure) return null
  return if (server.toURI() == GithubServerPath.DEFAULT_SERVER.toURI()) GithubServerPath.DEFAULT_SERVER else server
}

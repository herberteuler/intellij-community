// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab

import com.intellij.collaboration.async.timeoutRunBlockingWithBackgroundScope
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.advanced.AdvancedSettings
import com.intellij.openapi.options.advanced.AdvancedSettingsImpl
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.replaceService
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.gitlab.api.GitLabProjectCoordinates
import org.jetbrains.plugins.gitlab.api.GitLabServerPath
import org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccountManager
import org.jetbrains.plugins.gitlab.util.GitLabProjectPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val ALIASES_SETTING_ID = "gitlab.aliases"
private const val ALIASED_URL = "git@gitlab-work:group/repo.git"
private const val GITLAB_COM_URL = "git@gitlab.com:upstream/repo.git"

@TestApplication
internal class GitLabProjectsManagerTest {

  private val project by projectFixture()

  @TestDisposable
  private lateinit var disposable: Disposable

  // The servers that the discovery checks, in the order of the checks.
  private val checkedServers = Channel<GitLabServerPath>(Channel.UNLIMITED)

  @BeforeEach
  fun setUp() {
    val accountManager = mockk<GitLabAccountManager> {
      every { accountsState } returns MutableStateFlow(emptySet())
    }
    val serversManager = mockk<GitLabServersManager> {
      coEvery { checkIsGitLabServer(any()) } coAnswers {
        checkedServers.send(firstArg())
        false
      }
    }
    val application = ApplicationManager.getApplication()
    application.replaceService(GitLabAccountManager::class.java, accountManager, disposable)
    application.replaceService(GitLabServersManager::class.java, serversManager, disposable)
  }

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `remote with an alias maps to gitlab com`() = timeoutRunBlockingWithBackgroundScope { bg ->
    setAliases("gitlab.com, gitlab-work")
    registerRemotes(gitRemote("origin", ALIASED_URL))

    val repositories = GitLabProjectsManagerImpl(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    assertThat(repositories.map { it.repository }).containsExactly(defaultServerProject("group"))
  }

  @Test
  fun `remote with an alias skips server discovery`() = timeoutRunBlockingWithBackgroundScope { bg ->
    setAliases("gitlab.com, gitlab-work")
    registerRemotes(gitRemote("origin", ALIASED_URL), gitRemote("other", "git@other-host:other/repo.git"))

    GitLabProjectsManagerImpl(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    // The discovery checks three server URIs for each host.
    assertThat(List(3) { checkedServers.receive().toURI().host }).containsOnly("other-host")
    assertThat(checkedServers.tryReceive().getOrNull()).isNull()
  }

  @Test
  fun `alias matches the host in any case of the setting`() = timeoutRunBlockingWithBackgroundScope { bg ->
    setAliases("GitLab-Work")
    registerRemotes(gitRemote("origin", ALIASED_URL))

    val repositories = GitLabProjectsManagerImpl(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    assertThat(repositories.map { it.repository }).containsExactly(defaultServerProject("group"))
  }

  @Test
  fun `remote without an alias goes to server discovery`() = timeoutRunBlockingWithBackgroundScope { bg ->
    registerRemotes(gitRemote("origin", ALIASED_URL), gitRemote("upstream", GITLAB_COM_URL))

    val repositories = GitLabProjectsManagerImpl(project, bg).knownRepositoriesState.first { it.isNotEmpty() }

    assertThat(repositories.map { it.repository }).containsExactly(defaultServerProject("upstream"))
    assertThat(checkedServers.receive()).isEqualTo(GitLabServerPath("https://gitlab-work"))
  }

  @Test
  fun `change of the aliases setting updates the known repositories`() = timeoutRunBlockingWithBackgroundScope { bg ->
    registerRemotes(gitRemote("origin", ALIASED_URL), gitRemote("upstream", GITLAB_COM_URL))
    val state = GitLabProjectsManagerImpl(project, bg).knownRepositoriesState
    state.first { it.isNotEmpty() }

    setAliases("gitlab.com,gitlab-work")
    val repositories = state.first { it.size == 2 }

    assertThat(repositories.map { it.repository })
      .containsExactlyInAnyOrder(defaultServerProject("group"), defaultServerProject("upstream"))
  }

  private fun setAliases(value: String) {
    (AdvancedSettings.getInstance() as AdvancedSettingsImpl).setSetting(ALIASES_SETTING_ID, value, disposable)
  }

  private fun registerRemotes(vararg gitRemotes: GitRemote) {
    val repository = mockk<GitRepository> {
      every { remotes } returns gitRemotes.toList()
    }
    val repositoryManager = mockk<GitRepositoryManager>(relaxUnitFun = true) {
      every { repositories } returns listOf(repository)
    }
    project.replaceService(GitRepositoryManager::class.java, repositoryManager, disposable)
  }

  private fun gitRemote(name: String, url: String): GitRemote =
    GitRemote(name = name, urls = listOf(url), pushUrls = listOf(url), fetchRefSpecs = listOf(), pushRefSpecs = listOf())

  private fun defaultServerProject(owner: String): GitLabProjectCoordinates =
    GitLabProjectCoordinates(GitLabServerPath.DEFAULT_SERVER, GitLabProjectPath(owner, "repo"))
}

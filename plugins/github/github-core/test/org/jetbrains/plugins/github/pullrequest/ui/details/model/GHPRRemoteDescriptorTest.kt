// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.pullrequest.ui.details.model

import git4idea.commands.Git
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitRemoteBranchesUtil
import git4idea.remote.hosting.GitRemoteBranchesUtil.findOrCreateRemote
import git4idea.repo.GitRemote
import git4idea.repo.GitRepoInfo
import git4idea.repo.GitRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.github.api.data.GHRepository
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequest
import org.jetbrains.plugins.github.pullrequest.ui.details.model.GHPRBranchesViewModel.Companion.getHeadRemoteDescriptor
import org.junit.jupiter.api.Test
import java.net.URI

internal class GHPRRemoteDescriptorTest {
  @Test
  fun `creating a fork remote preserves the SSH alias`() {
    val origin = gitRemote("git@github-work:group/repo.git")
    val coordinates = remoteCoordinates(origin)
    val repository = coordinates.repository
    val remotes = mutableListOf(origin)
    every { repository.remotes } returns remotes
    every { repository.update() } returns Unit
    val descriptor = pullRequest().getHeadRemoteDescriptor(coordinates)!!
    val addedUrl = slot<String>()
    val git = mockk<Git> {
      every { addRemote(repository, "fork-owner", capture(addedUrl)) } answers {
        remotes.add(gitRemote(addedUrl.captured, "fork-owner"))
        mockk()
      }
    }

    val createdRemote = git.findOrCreateRemote(repository, descriptor)

    verify(exactly = 1) { git.addRemote(repository, "fork-owner", "git@github-work:fork-owner/repo.git") }
    assertThat(createdRemote?.firstUrl).isEqualTo("git@github-work:fork-owner/repo.git")
    assertThat(descriptor.httpUrl).isEqualTo("https://github.com/fork-owner/repo")
  }

  @Test
  fun `SSH alias uses its configured port when the remote has no explicit port`() {
    val coordinates = remoteCoordinates(gitRemote("git@github-work:group/repo.git"))
    val details = pullRequest(sshUrl = "ssh://git@github.com:2222/fork-owner/repo.git")

    val descriptor = details.getHeadRemoteDescriptor(coordinates)!!

    assertThat(descriptor.sshUrl).isEqualTo("git@github-work:fork-owner/repo.git")
  }

  @Test
  fun `SSH alias keeps the explicit remote port`() {
    val coordinates = remoteCoordinates(gitRemote("ssh://git@github-work:2222/group/repo.git"))

    val descriptor = pullRequest().getHeadRemoteDescriptor(coordinates)!!

    assertThat(descriptor.sshUrl).isEqualTo("ssh://git@github-work:2222/fork-owner/repo.git")
  }

  @Test
  fun `SSH URL keeps the project port when the host stays the same`() {
    val coordinates = remoteCoordinates(gitRemote("git@github.com:group/repo.git"))
    val details = pullRequest(sshUrl = "ssh://git@github.com:2222/fork-owner/repo.git")

    val descriptor = details.getHeadRemoteDescriptor(coordinates)!!

    assertThat(descriptor.sshUrl).isEqualTo("ssh://git@github.com:2222/fork-owner/repo.git")
  }

  @Test
  fun `matching prefers the SSH alias over the canonical host`() {
    val origin = gitRemote("git@github-work:group/repo.git")
    val canonicalFork = gitRemote("git@github.com:fork-owner/repo.git", "canonical")
    val aliasedFork = gitRemote("git@github-work:fork-owner/repo.git", "fork")
    val coordinates = remoteCoordinates(origin)
    every { coordinates.repository.info.remotes } returns listOf(origin, canonicalFork, aliasedFork)

    val descriptor = pullRequest().getHeadRemoteDescriptor(coordinates)!!

    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, descriptor)).isEqualTo(aliasedFork)
  }

  @Test
  fun `HTTP matching keeps the server web path and the API URLs`() {
    val origin = gitRemote("https://git.example.com/github/group/repo.git")
    val otherFork = gitRemote("https://git.example.com/other/fork-owner/repo.git", "other-fork")
    val fork = gitRemote("https://git.example.com/github/fork-owner/repo.git", "fork")
    val coordinates = remoteCoordinates(origin)
    every { coordinates.repository.info.remotes } returns listOf(origin, otherFork, fork)
    val details = pullRequest(
      httpUrl = "https://git.example.com/github/fork-owner/repo",
      sshUrl = "git@git.example.com:fork-owner/repo.git",
    )

    val descriptor = details.getHeadRemoteDescriptor(coordinates)!!

    assertThat(descriptor.serverUri).isEqualTo(URI("https://git.example.com/github/"))
    assertThat(descriptor.httpUrl).isEqualTo("https://git.example.com/github/fork-owner/repo")
    assertThat(descriptor.sshUrl).isEqualTo("git@git.example.com:fork-owner/repo.git")
    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, descriptor)).isEqualTo(fork)
  }

  private fun pullRequest(
    httpUrl: String = "https://github.com/fork-owner/repo",
    sshUrl: String = "git@github.com:fork-owner/repo.git",
  ): GHPullRequest {
    val repository = mockk<GHRepository> {
      every { owner } returns mockk { every { login } returns "fork-owner" }
      every { nameWithOwner } returns "fork-owner/repo"
      every { url } returns httpUrl
      every { this@mockk.sshUrl } returns sshUrl
    }
    return mockk { every { headRepository } returns repository }
  }

  private fun gitRemote(url: String, name: String = "origin"): GitRemote =
    GitRemote(name = name, urls = listOf(url), pushUrls = listOf(url), fetchRefSpecs = listOf(), pushRefSpecs = listOf())

  private fun remoteCoordinates(remote: GitRemote): GitRemoteUrlCoordinates {
    val repositoryInfo = mockk<GitRepoInfo> { every { remotes } returns listOf(remote) }
    val repository = mockk<GitRepository> { every { info } returns repositoryInfo }
    return GitRemoteUrlCoordinates(remote.firstUrl!!, remote, repository)
  }
}

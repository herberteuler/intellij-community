// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.data

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
import io.mockk.unmockkAll
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.plugins.gitlab.util.GitLabProjectPath
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI

private const val ALIASED_URL = "git@gitlab-work:group/repo.git"

internal class GitLabMergeRequestRemoteDescriptorTest {

  @AfterEach
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `server URI is the host of the remote without a path`() {
    val coordinates = remoteCoordinates(gitRemote(ALIASED_URL))

    val descriptor = projectDetails("group").getRemoteDescriptor(coordinates)

    assertThat(descriptor.serverUri).isEqualTo(URI("https://gitlab-work/"))
    assertThat(descriptor.name).isEqualTo("group")
    assertThat(descriptor.path).isEqualTo("group/repo")
  }

  @Test
  fun `server URI of an HTTP remote keeps the web path from the project HTTP URL`() {
    val coordinates = remoteCoordinates(gitRemote("https://example.com/gitlab/group/repo.git"))
    val project = projectDetails("group").copy(httpUrlToRepo = "https://example.com/gitlab/group/repo.git")

    val descriptor = project.getRemoteDescriptor(coordinates)

    assertThat(descriptor.serverUri).isEqualTo(URI("https://example.com/gitlab/"))
  }

  @Test
  fun `server URI of an HTTP remote is the root when the project HTTP URL has no web path`() {
    val coordinates = remoteCoordinates(gitRemote("https://example.com/gitlab/group/repo.git"))

    val descriptor = projectDetails("group").getRemoteDescriptor(coordinates)

    assertThat(descriptor.serverUri).isEqualTo(URI("https://example.com/"))
  }

  @Test
  fun `HTTP fork remote matching keeps the server web path`() {
    val origin = gitRemote("https://example.com/gitlab/group/repo.git")
    val otherFork = gitRemote("https://example.com/other/fork-owner/repo.git", "other-fork")
    val fork = gitRemote("https://example.com/gitlab/fork-owner/repo.git", "fork")
    val coordinates = remoteCoordinates(origin)
    every { coordinates.repository.info.remotes } returns listOf(origin, otherFork, fork)
    val source = GitLabMergeRequestFullDetails.ProjectDetails(
      path = GitLabProjectPath("fork-owner", "repo"),
      httpUrlToRepo = "https://example.com/gitlab/fork-owner/repo.git",
      sshUrlToRepo = "git@example.com:fork-owner/repo.git",
    )
    val descriptor = source.getRemoteDescriptor(coordinates)

    assertThat(descriptor.serverUri).isEqualTo(URI("https://example.com/gitlab/"))
    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, descriptor)).isEqualTo(fork)
  }

  @Test
  fun `source descriptor finds the remote with an SSH alias`() {
    val origin = gitRemote(ALIASED_URL)
    val coordinates = remoteCoordinates(origin)
    val project = projectDetails("group")
    val details = mergeRequestDetails(source = project, target = project)

    val descriptor = details.getSourceRemoteDescriptor(coordinates)

    assertThat(descriptor).isNotNull
    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, descriptor!!)).isEqualTo(origin)
  }

  @Test
  fun `target descriptor of a fork finds the remote with an SSH alias`() {
    val origin = gitRemote(ALIASED_URL)
    val coordinates = remoteCoordinates(origin)
    val details = mergeRequestDetails(source = projectDetails("fork-owner"), target = projectDetails("group"))

    val sourceDescriptor = details.getSourceRemoteDescriptor(coordinates)!!
    val targetDescriptor = details.getTargetRemoteDescriptor(coordinates)

    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, targetDescriptor)).isEqualTo(origin)
    assertThat(GitRemoteBranchesUtil.findRemote(coordinates.repository, sourceDescriptor)).isNull()
  }

  @Test
  fun `creating a fork remote preserves the SSH alias`() {
    val origin = gitRemote(ALIASED_URL)
    val coordinates = remoteCoordinates(origin)
    val repository = coordinates.repository
    val remotes = mutableListOf(origin)
    every { repository.remotes } returns remotes
    every { repository.update() } returns Unit
    val details = mergeRequestDetails(source = projectDetails("fork-owner"), target = projectDetails("group"))
    val descriptor = details.getSourceRemoteDescriptor(coordinates)!!
    val addedUrl = slot<String>()
    val git = mockk<Git> {
      every { addRemote(repository, "fork-owner", capture(addedUrl)) } answers {
        remotes.add(gitRemote(addedUrl.captured, "fork-owner"))
        mockk()
      }
    }

    val createdRemote = git.findOrCreateRemote(repository, descriptor)

    verify(exactly = 1) { git.addRemote(repository, "fork-owner", any()) }
    assertThat(createdRemote).isNotNull
    assertThat(addedUrl.captured).isEqualTo("git@gitlab-work:fork-owner/repo.git")
  }

  @Test
  fun `SSH alias drops the project port when the remote has no explicit port`() {
    val coordinates = remoteCoordinates(gitRemote(ALIASED_URL))
    val project = projectDetails("fork-owner").copy(sshUrlToRepo = "ssh://git@gitlab.com:2222/fork-owner/repo.git")

    val descriptor = project.getRemoteDescriptor(coordinates)

    assertThat(descriptor.sshUrl).isEqualTo("git@gitlab-work:fork-owner/repo.git")
  }

  @Test
  fun `SSH URL keeps the project port with the same host`() {
    val coordinates = remoteCoordinates(gitRemote("git@gitlab.com:group/repo.git"))
    val project = projectDetails("fork-owner").copy(sshUrlToRepo = "ssh://git@gitlab.com:2222/fork-owner/repo.git")

    val descriptor = project.getRemoteDescriptor(coordinates)

    assertThat(descriptor.sshUrl).isEqualTo("ssh://git@gitlab.com:2222/fork-owner/repo.git")
  }

  @Test
  fun `SSH alias keeps the explicit remote port for an SCP project URL`() {
    val coordinates = remoteCoordinates(gitRemote("ssh://git@gitlab-work:2222/group/repo.git"))

    val descriptor = projectDetails("fork-owner").getRemoteDescriptor(coordinates)

    assertThat(descriptor.sshUrl).isEqualTo("ssh://git@gitlab-work:2222/fork-owner/repo.git")
  }

  @Test
  fun `SSH URL keeps the explicit remote port with the same host`() {
    val coordinates = remoteCoordinates(gitRemote("ssh://git@gitlab.com:2222/group/repo.git"))

    val descriptor = projectDetails("fork-owner").getRemoteDescriptor(coordinates)

    assertThat(descriptor.sshUrl).isEqualTo("ssh://git@gitlab.com:2222/fork-owner/repo.git")
  }

  @Test
  fun `SSH alias keeps the HTTP URL from the API`() {
    val coordinates = remoteCoordinates(gitRemote(ALIASED_URL))
    val project = projectDetails("fork-owner")

    val descriptor = project.getRemoteDescriptor(coordinates)

    assertThat(descriptor.httpUrl).isEqualTo("https://gitlab.com/fork-owner/repo.git")
    assertThat(descriptor.sshUrl).isEqualTo("git@gitlab-work:fork-owner/repo.git")
  }

  @Test
  fun `creating an HTTP fork remote uses the URL from the API`() {
    val origin = gitRemote("http://gitlab.com:8080/group/repo.git")
    val coordinates = remoteCoordinates(origin)
    val repository = coordinates.repository
    val remotes = mutableListOf(origin)
    every { repository.remotes } returns remotes
    every { repository.update() } returns Unit
    val descriptor = projectDetails("fork-owner").getRemoteDescriptor(coordinates)
    val addedUrl = slot<String>()
    val git = mockk<Git> {
      every { addRemote(repository, "fork-owner", capture(addedUrl)) } answers {
        remotes.add(gitRemote(addedUrl.captured, "fork-owner"))
        mockk()
      }
    }

    val createdRemote = git.findOrCreateRemote(repository, descriptor)

    verify(exactly = 1) { git.addRemote(repository, "fork-owner", "https://gitlab.com/fork-owner/repo.git") }
    assertThat(createdRemote?.firstUrl).isEqualTo("https://gitlab.com/fork-owner/repo.git")
    assertThat(descriptor.sshUrl).isEqualTo("git@gitlab.com:fork-owner/repo.git")
  }

  @Test
  fun `source descriptor is null without a source project`() {
    val coordinates = remoteCoordinates(gitRemote(ALIASED_URL))
    val details = mergeRequestDetails(source = null, target = projectDetails("group"))

    assertThat(details.getSourceRemoteDescriptor(coordinates)).isNull()
  }

  private fun gitRemote(url: String, name: String = "origin"): GitRemote =
    GitRemote(name = name, urls = listOf(url), pushUrls = listOf(url), fetchRefSpecs = listOf(), pushRefSpecs = listOf())

  private fun remoteCoordinates(remote: GitRemote): GitRemoteUrlCoordinates {
    val repositoryInfo = mockk<GitRepoInfo> {
      every { remotes } returns listOf(remote)
    }
    val repository = mockk<GitRepository> {
      every { info } returns repositoryInfo
    }
    return GitRemoteUrlCoordinates(remote.firstUrl!!, remote, repository)
  }

  // The API returns the canonical URLs of gitlab.com, not the URLs with the SSH alias.
  private fun projectDetails(owner: String): GitLabMergeRequestFullDetails.ProjectDetails =
    GitLabMergeRequestFullDetails.ProjectDetails(
      path = GitLabProjectPath(owner, "repo"),
      httpUrlToRepo = "https://gitlab.com/$owner/repo.git",
      sshUrlToRepo = "git@gitlab.com:$owner/repo.git"
    )

  private fun mergeRequestDetails(
    source: GitLabMergeRequestFullDetails.ProjectDetails?,
    target: GitLabMergeRequestFullDetails.ProjectDetails,
  ): GitLabMergeRequestFullDetails = mockk {
    every { sourceProject } returns source
    every { targetProject } returns target
  }
}

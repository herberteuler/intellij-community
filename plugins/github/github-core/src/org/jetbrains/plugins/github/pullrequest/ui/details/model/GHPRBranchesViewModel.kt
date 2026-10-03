// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.github.pullrequest.ui.details.model

import com.intellij.collaboration.async.childScope
import com.intellij.collaboration.async.launchNow
import com.intellij.collaboration.async.mapState
import com.intellij.collaboration.async.withInitial
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewBranches
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewBranchesViewModel
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.util.io.URLUtil
import git4idea.GitStandardRemoteBranch
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitHostingUrlUtil
import git4idea.remote.hosting.GitHostingUrlUtil.getUriFromRemoteUrl
import git4idea.remote.hosting.GitRemoteBranchesUtil
import git4idea.remote.hosting.HostedGitRepositoryRemote
import git4idea.remote.hosting.changesSignalFlow
import git4idea.workingTrees.GitWorkingTreesService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.plugins.github.api.GHRepositoryCoordinates
import org.jetbrains.plugins.github.api.data.GHRepository
import org.jetbrains.plugins.github.api.data.pullrequest.GHPullRequest
import org.jetbrains.plugins.github.authentication.accounts.GithubAccount
import org.jetbrains.plugins.github.pullrequest.GHPRStatisticsCollector
import org.jetbrains.plugins.github.pullrequest.ui.GHPRProjectViewModel
import org.jetbrains.plugins.github.util.GHGitRepositoryMapping
import java.net.URI

private val LOG = logger<GHPRBranchesViewModel>()

@ApiStatus.Experimental
class GHPRBranchesViewModel internal constructor(
  parentCs: CoroutineScope,
  private val project: Project,
  private val mapping: GHGitRepositoryMapping,
  private val account: GithubAccount,
  private val detailsState: StateFlow<GHPullRequest>,
) : CodeReviewBranchesViewModel {
  private val cs = parentCs.childScope(this::class)

  private val gitRepository = mapping.remote.repository

  private val targetBranch: StateFlow<String> = detailsState.mapState(cs) {
    it.baseRefName
  }

  override val sourceBranch: StateFlow<String> = detailsState.mapState(cs) {
    val headRepository = it.headRepository
    if (headRepository != null && (headRepository.isFork || it.baseRefName == it.headRefName)) {
      headRepository.owner.login + ":" + it.headRefName
    }
    else {
      it.headRefName
    }
  }

  override val isCheckedOut: StateFlow<Boolean> = gitRepository.changesSignalFlow().withInitial(Unit)
    .combine(detailsState) { _, details ->
      val remote = details.getHeadRemoteDescriptor(mapping.remote) ?: return@combine false
      GitRemoteBranchesUtil.testRemoteBranchCheckedOut(gitRepository, remote, details.headRefName)
    }.stateIn(cs, SharingStarted.Eagerly, false)

  private val _showBranchesRequests = MutableSharedFlow<CodeReviewBranches>()
  override val showBranchesRequests: SharedFlow<CodeReviewBranches> = _showBranchesRequests

  override fun fetchAndCheckoutRemoteBranch() {
    val details = detailsState.value
    cs.launch {
      fetchAndCheckoutBranch(mapping.remote, details)
      GHPRStatisticsCollector.logDetailsBranchCheckedOut(project)
    }
  }

  override val canCheckoutInNewWorktree: Boolean
    // A new worktree can't be created for a branch that is already checked out in the current one.
    get() = GitWorkingTreesService.isWorktreeCreationSupported(gitRepository) && !isCheckedOut.value

  override fun checkoutInNewWorktree() {
    val details = detailsState.value
    cs.launch {
      fetchAndCheckoutBranchInNewWorktree(
        remoteUrlCoordinates = mapping.remote,
        details = details,
        preferredRepoAndAccount = mapping.repository to account,
      )
      GHPRStatisticsCollector.logDetailsBranchCheckedOut(project)
    }
  }

  override val canShowInLog: Boolean = true
  override fun fetchAndShowInLog() {
    cs.launch {
      val details = detailsState.first()

      val headRemote = details.getHeadRemoteDescriptor(mapping.remote)
                         ?.let { GitRemoteBranchesUtil.findRemote(gitRepository, it) } ?: return@launch
      val baseRemote = details.getBaseRemoteDescriptor(mapping.remote)
                         ?.let { GitRemoteBranchesUtil.findRemote(gitRepository, it) } ?: return@launch

      val headBranch = GitStandardRemoteBranch(headRemote, details.headRefName)
      val baseBranch = GitStandardRemoteBranch(baseRemote, details.baseRefName)

      GitRemoteBranchesUtil.fetchAndShowRemoteBranchInLog(gitRepository, headBranch, baseBranch)
    }
  }

  override fun showBranches() {
    cs.launchNow {
      val source = sourceBranch.value
      val target = targetBranch.value
      _showBranchesRequests.emit(CodeReviewBranches(source, target))
      GHPRStatisticsCollector.logDetailsBranchesOpened(project)
    }
  }

  companion object {

    private const val WORKTREE_FROM_REVIEW_PLACE = "review.details.branch.popup"

    /**
     * Gets the server URI with the host of [defaultCoordinates].
     * HTTP remotes keep the web path from [url]. SSH remotes use the root path.
     */
    private fun GHRepository.getServerUri(defaultCoordinates: GitRemoteUrlCoordinates): URI {
      val remoteUri = getUriFromRemoteUrl(defaultCoordinates.url)
                      ?: throw IllegalArgumentException("Invalid remote URL: ${defaultCoordinates.url}")
      if (GitHostingUrlUtil.isSshUrl(defaultCoordinates.url)) return remoteUri.resolve("/")

      val webPath = getUriFromRemoteUrl(url)?.path
        ?.takeIf { it.endsWith("/$nameWithOwner") }
        ?.removeSuffix(nameWithOwner) ?: "/"
      return remoteUri.resolve(webPath)
    }

    /**
     * Creates the descriptor with the host of [defaultCoordinates] and the project paths from the API.
     */
    private fun GHRepository.getRemoteDescriptor(defaultCoordinates: GitRemoteUrlCoordinates): HostedGitRepositoryRemote {
      val serverUri = getServerUri(defaultCoordinates)
      return HostedGitRepositoryRemote(owner.login, serverUri, nameWithOwner, url, getSshUrl(defaultCoordinates))
    }

    /**
     * Uses the SSH host and explicit port of [defaultCoordinates].
     * When the host changes and no port is specified, the SSH configuration supplies the port.
     */
    private fun GHRepository.getSshUrl(defaultCoordinates: GitRemoteUrlCoordinates): String {
      if (!GitHostingUrlUtil.isSshUrl(defaultCoordinates.url) || !GitHostingUrlUtil.isSshUrl(sshUrl)) return sshUrl
      val remoteUri = getUriFromRemoteUrl(defaultCoordinates.url) ?: return sshUrl
      val sshUri = getUriFromRemoteUrl(sshUrl) ?: return sshUrl
      if (remoteUri.host.equals(sshUri.host, true) && (remoteUri.port == -1 || remoteUri.port == sshUri.port)) return sshUrl

      if (sshUrl.contains(URLUtil.SCHEME_SEPARATOR)) {
        val uri = URI(sshUrl)
        if (remoteUri.port == -1) {
          val userInfo = uri.userInfo?.let { "$it@" }.orEmpty()
          return "$userInfo${remoteUri.host}:${uri.path.removePrefix("/")}"
        }
        return URI(uri.scheme, uri.userInfo, remoteUri.host, remoteUri.port, uri.path, uri.query, uri.fragment).toString()
      }
      val hostStart = sshUrl.indexOf('@') + 1
      val hostEnd = sshUrl.indexOf(':', hostStart)
      if (hostEnd < 0) return sshUrl
      if (remoteUri.port != -1) {
        val userInfo = sshUrl.substring(0, hostStart).removeSuffix("@").takeIf { it.isNotEmpty() }
        val path = "/" + sshUrl.substring(hostEnd + 1).removePrefix("/")
        return URI("ssh", userInfo, remoteUri.host, remoteUri.port, path, null, null).toString()
      }
      return sshUrl.substring(0, hostStart) + remoteUri.host + sshUrl.substring(hostEnd)
    }

    fun GHPullRequest.getHeadRemoteDescriptor(remoteUrlCoordinates: GitRemoteUrlCoordinates): HostedGitRepositoryRemote? =
      headRepository?.getRemoteDescriptor(remoteUrlCoordinates)

    fun GHPullRequest.getBaseRemoteDescriptor(remoteUrlCoordinates: GitRemoteUrlCoordinates): HostedGitRepositoryRemote? =
      baseRepository?.getRemoteDescriptor(remoteUrlCoordinates)

    internal suspend fun fetchAndCheckoutBranch(remoteUrlCoordinates: GitRemoteUrlCoordinates, details: GHPullRequest) {
      val baseRepository = details.baseRepository ?: run {
        LOG.warn("Can't checkout remote branch for PR ${details.number} because base repository is missing")
        return
      }
      val headRepository = details.headRepository ?: run {
        LOG.warn("Can't checkout remote branch for PR ${details.number} because head repository is missing")
        return
      }
      val isFork = headRepository != baseRepository && details.headRepository.isFork
      val localPrefix = if (isFork) "fork/${details.headRepository.owner.login}" else null
      val remoteDescriptor = headRepository.getRemoteDescriptor(remoteUrlCoordinates)
      GitRemoteBranchesUtil.fetchAndCheckoutRemoteBranch(remoteUrlCoordinates.repository,
                                                         remoteDescriptor,
                                                         details.headRefName,
                                                         localPrefix)
    }

    internal suspend fun fetchAndCheckoutBranchInNewWorktree(
      remoteUrlCoordinates: GitRemoteUrlCoordinates,
      details: GHPullRequest,
      preferredRepoAndAccount: Pair<GHRepositoryCoordinates, GithubAccount>,
    ) {
      val baseRepository = details.baseRepository ?: run {
        LOG.warn("Can't checkout remote branch in a new worktree for PR ${details.number} because base repository is missing")
        return
      }
      val headRepository = details.headRepository ?: run {
        LOG.warn("Can't checkout remote branch in a new worktree for PR ${details.number} because head repository is missing")
        return
      }
      val isFork = headRepository != baseRepository && details.headRepository.isFork
      val localPrefix = if (isFork) "fork/${details.headRepository.owner.login}" else null
      val remoteDescriptor = headRepository.getRemoteDescriptor(remoteUrlCoordinates)
      val prId = details.prId
      val worktreeName = "${remoteUrlCoordinates.repository.root.name}_PR_${details.number}"
      val parentDir = withContext(Dispatchers.IO) {
        GitRemoteBranchesUtil.getReviewWorktreesParentDir(remoteUrlCoordinates.repository.project)
      }
      GitRemoteBranchesUtil.fetchAndCheckoutInNewWorktree(remoteUrlCoordinates.repository,
                                                          remoteDescriptor,
                                                          details.headRefName,
                                                          parentDir,
                                                          worktreeName,
                                                          WORKTREE_FROM_REVIEW_PLACE,
                                                          localPrefix) { worktreeProject ->
        worktreeProject.service<GHPRProjectViewModel>().activateAndAwaitProject(preferredRepoAndAccount) {
          openPullRequestInfoAndDiff(prId)
        }
      }
    }
  }
}

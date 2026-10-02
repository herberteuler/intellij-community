// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gitlab.mergerequest.data

import com.intellij.collaboration.ui.codereview.details.data.ReviewRequestState
import com.intellij.openapi.util.NlsSafe
import com.intellij.util.io.URLUtil
import git4idea.GitRemoteBranch
import git4idea.push.GitSpecialRefRemoteBranch
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.hosting.GitHostingUrlUtil
import git4idea.remote.hosting.GitHostingUrlUtil.getUriFromRemoteUrl
import git4idea.remote.hosting.HostedGitRepositoryRemote
import git4idea.repo.GitRemote
import org.jetbrains.plugins.gitlab.api.dto.GitLabDiffRefs
import org.jetbrains.plugins.gitlab.api.dto.GitLabLabelGQLDTO
import org.jetbrains.plugins.gitlab.api.dto.GitLabMergeRequestPermissionsDTO
import org.jetbrains.plugins.gitlab.api.dto.GitLabPipelineDTO
import org.jetbrains.plugins.gitlab.api.dto.GitLabProjectDTO
import org.jetbrains.plugins.gitlab.api.dto.GitLabReviewerDTO
import org.jetbrains.plugins.gitlab.api.dto.GitLabUserDTO
import org.jetbrains.plugins.gitlab.mergerequest.api.dto.GitLabMergeRequestDTO
import org.jetbrains.plugins.gitlab.util.GitLabProjectPath
import java.net.URI
import java.util.Date

data class GitLabMergeRequestFullDetails(
  val iid: String,
  val title: @NlsSafe String,
  val createdAt: Date,
  val author: GitLabUserDTO,
  val mergeStatus: GitLabMergeStatus,
  val isMergeable: Boolean,
  val state: GitLabMergeRequestState,
  val draft: Boolean,
  val assignees: List<GitLabUserDTO>,
  val reviewers: List<GitLabReviewerDTO>,
  val webUrl: @NlsSafe String,
  val detailedLabels: List<GitLabLabelGQLDTO>,
  val targetProject: ProjectDetails,
  val sourceProject: ProjectDetails?,
  val description: String,
  val approvedBy: List<GitLabUserDTO>,
  val targetBranch: String,
  val sourceBranch: String,
  val approvalsRequired: Int,
  val conflicts: Boolean,
  val ffOnlyMerge: Boolean,
  val onlyAllowMergeIfAllDiscussionsAreResolved: Boolean,
  val onlyAllowMergeIfPipelineSucceeds: Boolean,
  val allowMergeOnSkippedPipeline: Boolean,
  val shouldSquash: Boolean,
  val shouldSquashWithProject: Boolean, // [shouldSquash] + project override
  val shouldSquashReadOnly: Boolean?,
  val defaultSquashCommitMessage: String?,
  val defaultMergeCommitMessage: String?,
  val removeSourceBranch: Boolean,
  val shouldBeRebased: Boolean,
  val rebaseInProgress: Boolean,
  val diffRefs: GitLabDiffRefs?,
  val headPipeline: GitLabPipelineDTO?,
  val userPermissions: GitLabMergeRequestPermissionsDTO,
) {

  data class ProjectDetails(
    val path: GitLabProjectPath,
    val httpUrlToRepo: String?,
    val sshUrlToRepo: String?,
  )

  companion object {
    fun fromGraphQL(dto: GitLabMergeRequestDTO): GitLabMergeRequestFullDetails = GitLabMergeRequestFullDetails(
      iid = dto.iid,
      title = dto.title,
      createdAt = dto.createdAt,
      author = dto.author,
      mergeStatus = dto.mergeStatusEnum ?: GitLabMergeStatus.UNCHECKED,
      isMergeable = dto.mergeable,
      state = dto.state,
      draft = dto.draft,
      assignees = dto.assignees,
      reviewers = dto.reviewers,
      webUrl = dto.webUrl,
      targetProject = dto.targetProject.toDetails(),
      sourceProject = dto.sourceProject?.toDetails(),
      description = dto.description.orEmpty(),
      approvedBy = dto.approvedBy,
      targetBranch = dto.targetBranch,
      sourceBranch = dto.sourceBranch,
      approvalsRequired = dto.approvalsRequired ?: 0,
      conflicts = dto.conflicts,
      diffRefs = dto.diffRefs,
      headPipeline = dto.headPipeline,
      userPermissions = dto.userPermissions,
      detailedLabels = dto.labels,
      ffOnlyMerge = dto.targetProject.mergeRequestsFfOnlyEnabled ?: false,
      shouldSquash = dto.squash,
      shouldSquashWithProject = dto.squashOnMerge,
      shouldSquashReadOnly = dto.squashReadOnly,
      defaultSquashCommitMessage = dto.defaultSquashCommitMessage,
      defaultMergeCommitMessage = dto.defaultMergeCommitMessage,
      removeSourceBranch = dto.forceRemoveSourceBranch ?: false,
      onlyAllowMergeIfAllDiscussionsAreResolved = dto.targetProject.onlyAllowMergeIfAllDiscussionsAreResolved ?: false,
      onlyAllowMergeIfPipelineSucceeds = dto.targetProject.onlyAllowMergeIfPipelineSucceeds ?: false,
      allowMergeOnSkippedPipeline = dto.targetProject.allowMergeOnSkippedPipeline ?: false,
      shouldBeRebased = dto.shouldBeRebased,
      rebaseInProgress = dto.rebaseInProgress
    )

    private fun GitLabProjectDTO.toDetails() = ProjectDetails(
      path = GitLabProjectPath(ownerPath, path),
      httpUrlToRepo = httpUrlToRepo,
      sshUrlToRepo = sshUrlToRepo
    )
  }
}

val GitLabMergeRequestFullDetails.reviewState: ReviewRequestState
  get() =
    if (draft) {
      ReviewRequestState.DRAFT
    }
    else when (state) {
      GitLabMergeRequestState.CLOSED -> ReviewRequestState.CLOSED
      GitLabMergeRequestState.MERGED -> ReviewRequestState.MERGED
      GitLabMergeRequestState.OPENED -> ReviewRequestState.OPENED
      else -> ReviewRequestState.OPENED // to avoid null state
    }

/**
 * Creates the descriptor of the remote for this project.
 * The descriptor uses the host of [defaultCoordinates], so it also matches a remote with an SSH alias.
 */
fun GitLabMergeRequestFullDetails.ProjectDetails.getRemoteDescriptor(defaultCoordinates: GitRemoteUrlCoordinates): HostedGitRepositoryRemote =
  HostedGitRepositoryRemote(
    path.owner,
    getServerUri(defaultCoordinates),
    path.fullPath(),
    httpUrlToRepo,
    sshUrlToRepo?.let { getSshUrl(it, defaultCoordinates) }
  )

/**
 * Gets the server URI with the host of [defaultCoordinates].
 * HTTP remotes keep the web path from [httpUrlToRepo]. SSH remotes use the root path.
 */
private fun GitLabMergeRequestFullDetails.ProjectDetails.getServerUri(defaultCoordinates: GitRemoteUrlCoordinates): URI {
  val remoteUri = getUriFromRemoteUrl(defaultCoordinates.url) ?: throw IllegalArgumentException("Invalid remote URL: ${defaultCoordinates.url}")
  if (GitHostingUrlUtil.isSshUrl(defaultCoordinates.url)) return remoteUri.resolve("/")

  val projectPath = path.fullPath()
  val webPath = httpUrlToRepo?.let { getUriFromRemoteUrl(it)?.path }
    ?.takeIf { it.endsWith("/$projectPath") }
    ?.removeSuffix(projectPath) ?: "/"
  return remoteUri.resolve(webPath)
}

/**
 * Gets the SSH URL of this project with the host of [defaultCoordinates].
 * Keeps an explicit port from [defaultCoordinates].
 * Without such a port, the URL keeps the project port only when the host does not change.
 * With another host, such as an SSH alias, the SSH config gives the port, so the URL has no port.
 */
private fun getSshUrl(sshUrl: String, defaultCoordinates: GitRemoteUrlCoordinates): String {
  if (!GitHostingUrlUtil.isSshUrl(defaultCoordinates.url) || !GitHostingUrlUtil.isSshUrl(sshUrl)) return sshUrl
  val remoteUri = getUriFromRemoteUrl(defaultCoordinates.url) ?: return sshUrl
  val sshUri = getUriFromRemoteUrl(sshUrl) ?: return sshUrl
  if (remoteUri.host.equals(sshUri.host, true) && (remoteUri.port == -1 || remoteUri.port == sshUri.port)) return sshUrl

  if (sshUrl.contains(URLUtil.SCHEME_SEPARATOR)) {
    val uri = runCatching { URI(sshUrl) }.getOrNull() ?: return sshUrl
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

fun GitLabMergeRequestFullDetails.getSourceRemoteDescriptor(defaultCoordinates: GitRemoteUrlCoordinates): HostedGitRepositoryRemote? =
  sourceProject?.getRemoteDescriptor(defaultCoordinates)

fun GitLabMergeRequestFullDetails.getTargetRemoteDescriptor(defaultCoordinates: GitRemoteUrlCoordinates): HostedGitRepositoryRemote =
  targetProject.getRemoteDescriptor(defaultCoordinates)

/**
 * Gets a special remote ref for the head of the merge request.
 * This special reference does not represent a remote branch,
 * only a reference to the last commit of the MR.
 *
 * https://gitlab.com/gitlab-org/gitlab-foss/-/issues/47110
 */
fun GitLabMergeRequestFullDetails.getSpecialRemoteBranchForHead(remote: GitRemote): GitRemoteBranch =
  GitSpecialRefRemoteBranch("refs/merge-requests/${iid}/head", remote)

fun GitLabMergeRequestFullDetails.isFork(): Boolean =
  sourceProject != targetProject

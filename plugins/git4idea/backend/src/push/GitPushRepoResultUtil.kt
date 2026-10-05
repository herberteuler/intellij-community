// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.push

import git4idea.GitRemoteBranch
import git4idea.branch.GitBranchUtil
import git4idea.repo.GitRepository

fun GitPushRepoResult.findRemoteBranch(repository: GitRepository): GitRemoteBranch? =
  repository.branches.findRemoteBranch(GitBranchUtil.stripRefsPrefix(targetBranch))

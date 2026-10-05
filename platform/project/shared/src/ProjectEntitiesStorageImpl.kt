// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.project

import com.intellij.ide.plugins.CurrentProductMode
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.kernel.util.flushLatestChange
import com.intellij.platform.kernel.withKernel
import fleet.kernel.change
import fleet.kernel.rebase.shared
import fleet.kernel.transactor

internal class ProjectEntitiesStorageImpl : ProjectEntitiesStorage() {
  override suspend fun createEntityImpl(project: Project) {
    val projectId = project.projectId()
    change {
      shared {
        ProjectEntity.upsert(ProjectEntity.ProjectIdValue, projectId) {
          it[ProjectEntity.ProjectIdValue] = projectId
        }
      }
    }
  }

  // withKernel should be kept here, since Kernel is not properly propagated in tests
  // and project entity may be removed from threads without attached Kernel
  @Suppress("DEPRECATION")
  override suspend fun removeProjectEntity(project: Project): Unit = withKernel {
    if (CurrentProductMode.value.isFrontendProcess &&!project.isRdLightFrontend) {
      // The frontend cannot remove the shared ProjectEntity as it doesn't own that entity.
      return@withKernel
    }

    change {
      shared {
        val entity = project.asEntityOrNull() ?: run {
          LOG.error("Project entity hasn't been found for $project")
          return@shared
        }
        entity.delete()
      }
    }

    // Removing ProjectEntity and LocalProjectEntity is the last operation in most of the tests
    // Without calling "flushLatestChange" kernel keeps the project, which causes "testProjectLeak" failures
    transactor().flushLatestChange()
  }

  companion object {
    private val LOG = logger<ProjectEntitiesStorageImpl>()
  }
}

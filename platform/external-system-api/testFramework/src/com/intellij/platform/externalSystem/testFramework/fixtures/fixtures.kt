// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.externalSystem.testFramework.fixtures

import com.intellij.openapi.project.Project
import com.intellij.platform.externalSystem.testFramework.fixtures.impl.WorkspaceFixtureImpl
import com.intellij.testFramework.closeProjectAsync
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.testFixture
import java.nio.file.Path

fun workspaceFixture(): TestFixture<WorkspaceFixture> = testFixture {
  initialized(WorkspaceFixtureImpl()) {}
}

fun TestFixture<WorkspaceFixture>.projectFixture(
  projectRootFixture: TestFixture<Path>,
): TestFixture<Project> = testFixture {
  val workspace = this@projectFixture.init()
  val projectRoot = projectRootFixture.init()
  val project = workspace.openProject(projectRoot)
  initialized(project) {
    project.closeProjectAsync()
  }
}
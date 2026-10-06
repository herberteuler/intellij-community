// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.impl

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.ide.trustedProjects.TrustedProjectsListener
import com.intellij.ide.trustedProjects.TrustedProjectsLocator.LocatedProject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.junit5.SystemProperty
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.replaceService
import com.intellij.util.ThreeState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

@TestApplication
@Timeout(30)
@SystemProperty(propertyKey = "idea.trust.headless.disabled", propertyValue = "false")
internal class TrustedProjectsResetTest {
  @Test
  fun `the reset forgets both decisions and trusted locations and publishes the new state`(
    @TestDisposable disposable: Disposable,
    @TempDir root: Path,
  ) {
    val application = ApplicationManager.getApplication()
    val paths = TrustedPaths()
    val locations = TrustedPathsSettings()
    application.replaceService(TrustedPaths::class.java, paths, disposable)
    application.replaceService(TrustedPathsSettings::class.java, locations, disposable)
    val trusted = root.resolve("trusted")
    val untrusted = root.resolve("untrusted")
    val location = root.resolve("location")
    TrustedProjects.setProjectTrusted(trusted, true)
    TrustedProjects.setProjectTrusted(untrusted, false)
    locations.setTrustedPaths(listOf(location.toString()))
    assertThat(TrustedProjects.isProjectTrusted(location.resolve("child"))).isTrue()
    val pathChanges = paths.stateModificationCount
    val locationChanges = locations.stateModificationCount
    val listener = RecordingTrustListener()
    application.messageBus.connect(disposable).subscribe(TrustedProjectsListener.TOPIC, listener)

    TrustedProjects.clearSavedTrust()

    assertThat(paths.state.trustedPaths).isEmpty()
    assertThat(locations.getTrustedPaths()).isEmpty()
    assertThat(paths.stateModificationCount).isGreaterThan(pathChanges)
    assertThat(locations.stateModificationCount).isGreaterThan(locationChanges)
    for (path in listOf(trusted, untrusted, location.resolve("child"))) {
      assertThat(TrustedProjects.getProjectTrustedState(path)).isEqualTo(ThreeState.UNSURE)
    }
    assertThat(listener.changedPaths).contains(trusted, location)
    assertThat(listener.statesDuringNotification).containsOnly(ThreeState.UNSURE)
    listener.changedPaths.clear()
    TrustedProjects.clearSavedTrust()
    assertThat(listener.changedPaths).isEmpty()
  }

  private class RecordingTrustListener : TrustedProjectsListener {
    val changedPaths = ArrayList<Path>()
    val statesDuringNotification = ArrayList<ThreeState>()

    override fun onProjectUntrusted(locatedProject: LocatedProject) {
      changedPaths.addAll(locatedProject.projectRoots)
      statesDuringNotification += TrustedProjects.getProjectTrustedState(locatedProject)
    }
  }
}

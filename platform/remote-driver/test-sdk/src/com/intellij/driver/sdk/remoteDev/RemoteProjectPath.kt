package com.intellij.driver.sdk.remoteDev

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.jdk.RemotePath

/**
 * Returns the project's base path in the backend's file system.
 *
 * @receiver Driver connected to JetBrains Client
 */
fun Driver.getRemoteProjectBasePath(project: Project): String? = withContext {
  val path = utility(RemoteProjectPathProvider::class).getRemoteProjectBaseNioPath(project)
             ?: return@withContext null
  utility(EelPathConversions::class).asEelPath(path).toString()
}

@Remote("com.intellij.platform.eel.provider.RemoteProjectPathProviderKt")
private interface RemoteProjectPathProvider {
  fun getRemoteProjectBaseNioPath(project: Project): RemotePath?
}

@Remote("com.intellij.platform.eel.provider.EelPathConversionsKt")
private interface EelPathConversions {
  fun asEelPath(path: RemotePath): EelPath
}

@Remote("com.intellij.platform.eel.path.EelPath")
private interface EelPath {
  override fun toString(): String
}

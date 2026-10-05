// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote.hosting

import com.intellij.collaboration.api.ServerPath
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.NlsSafe
import com.intellij.util.io.URLUtil
import git4idea.remote.GitRemoteUrlCoordinates
import git4idea.remote.GitRemoteUrlUtil
import org.jetbrains.annotations.ApiStatus
import java.net.URI

object GitHostingUrlUtil {
  @JvmStatic
  fun isSshUrl(url: String): Boolean = GitRemoteUrlUtil.isSshUrl(url)

  @JvmStatic
  @NlsSafe
  fun removeProtocolPrefix(url: String): String = GitRemoteUrlUtil.removeProtocolPrefix(url)

  @JvmStatic
  fun getUriFromRemoteUrl(remoteUrl: String): URI? = GitRemoteUrlUtil.getUriFromRemoteUrl(remoteUrl)

  @JvmStatic
  fun match(serverUri: URI, gitRemoteUrl: String): Boolean = GitRemoteUrlUtil.match(serverUri, gitRemoteUrl)

  @JvmStatic
  fun matchHost(serverUri: URI, gitRemoteUrl: String): Boolean = GitRemoteUrlUtil.matchHost(serverUri, gitRemoteUrl)

  @ApiStatus.Internal
  suspend fun <S : ServerPath> findServerAt(log: Logger, coordinates: GitRemoteUrlCoordinates, serverCheck: suspend (URI) -> S?): S? {
    val uri = getUriFromRemoteUrl(coordinates.url)
    log.debug("Extracted URI $uri from remote ${coordinates.url}")
    if (uri == null) return null

    val host = uri.host ?: return null
    val path = uri.path ?: return null
    val pathParts = path.removePrefix("/").split('/').takeIf { it.size >= 2 } ?: return null
    val serverSuffix = if (pathParts.size == 2) null else pathParts.subList(0, pathParts.size - 2).joinToString("/")

    for (serverUri in listOf(
      URI(URLUtil.HTTPS_PROTOCOL, host, serverSuffix, null),
      URI(URLUtil.HTTP_PROTOCOL, host, serverSuffix, null),
      URI(URLUtil.HTTP_PROTOCOL, null, host, 8080, serverSuffix, null, null)
    )) {
      log.debug("Looking for server at $serverUri")
      try {
        val server = serverCheck(serverUri)
        if (server != null) {
          log.debug("Found server at $serverUri")
          return server
        }
      }
      catch (ignored: Throwable) {
      }
    }
    return null
  }
}
// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package git4idea.remote

import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.io.OSAgnosticPathUtil
import com.intellij.util.UriUtil
import com.intellij.util.io.URLUtil
import java.net.URI
import java.net.URISyntaxException

/**
 * Parses and matches Git remote URLs.
 * The functions need no hosting service.
 */
object GitRemoteUrlUtil {
  @JvmStatic
  fun isSshUrl(url: String): Boolean {
    val schemeSeparator = url.indexOf(URLUtil.SCHEME_SEPARATOR)
    if (schemeSeparator >= 0) {
      return when (url.substring(0, schemeSeparator)) {
        "ssh", "git+ssh", "ssh+git" -> true
        else -> false
      }
    }

    val colon = url.indexOf(':')
    val slash = url.indexOf('/')
    return colon >= 0 && (slash !in 0..<colon) && !OSAgnosticPathUtil.startsWithWindowsDrive(url)
  }

  @JvmStatic
  @NlsSafe
  fun removeProtocolPrefix(url: String): String {
    var index = url.indexOf('@')
    if (index != -1) {
      return url.substring(index + 1)
    }
    index = url.indexOf("://")
    return if (index != -1) {
      url.substring(index + 3)
    }
    else {
      url
    }
  }

  @JvmStatic
  fun getUriFromRemoteUrl(remoteUrl: String): URI? {
    val fixed = UriUtil.trimTrailingSlashes(remoteUrl).removeSuffix("/").removeSuffix(".git")
    return try {
      if (!fixed.contains(URLUtil.SCHEME_SEPARATOR)) {
        // scp-style
        URI(URLUtil.HTTPS_PROTOCOL + URLUtil.SCHEME_SEPARATOR + removeProtocolPrefix(fixed).replace(":/", "/").replace(':', '/'))
      }
      else {
        URI(fixed)
      }
    }
    catch (_: URISyntaxException) {
      null
    }
  }

  @JvmStatic
  fun match(serverUri: URI, gitRemoteUrl: String): Boolean {
    val remoteUri = getUriFromRemoteUrl(gitRemoteUrl) ?: return false

    if (!serverUri.host.equals(remoteUri.host, true)) return false

    if (serverUri.path != null && serverUri.path != "/") {
      val remoteUriPath = remoteUri.path ?: return false
      if (!remoteUriPath.startsWith(serverUri.path, true)) return false
    }
    return true
  }

  @JvmStatic
  fun matchHost(serverUri: URI, gitRemoteUrl: String): Boolean {
    val remoteUri = getUriFromRemoteUrl(gitRemoteUrl) ?: return false
    return serverUri.host.equals(remoteUri.host, true)
  }
}

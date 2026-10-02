// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.terminal.emulator.impl.ghostty.bindings

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.plugins.PluginModuleId
import com.intellij.idea.AppMode
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.PluginPathManager
import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.util.system.CpuArch
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import org.jetbrains.annotations.VisibleForTesting
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the libghostty-vt shared library file.
 *
 * When running a distribution or a dev build, the library is already available:
 * the build step downloads the current platform's library and bundles it with the
 * Terminal plugin (`org.jetbrains.intellij.build.CommunityRepositoryModules`).
 *
 * When running from sources (including tests), there is no such build step, so the
 * library is downloaded on the fly and cached afterward ([getOrDownloadLibRoot]).
 * The downloader is a content module of the core plugin, not a dependency of this module.
 *
 * [GHOSTTY_VT_LIB_ROOT_PROPERTY] overrides both lookups with ready-to-use library files.
 */
internal object LibGhosttyVtLocator {

  /**
   * Returns the library file to load for the current platform.
   *
   * When running from sources, downloads the library first (see [getOrDownloadLibRoot]).
   *
   * @throws IOException if there is no libghostty-vt build for the current platform,
   *   the library file is missing, or the download fails
   */
  @Throws(IOException::class)
  fun findLibraryFile(): Path {
    val relativeLibPath = currentPlatformLibraryPath()
    val overriddenLibRoot = System.getProperty(GHOSTTY_VT_LIB_ROOT_PROPERTY)
    val libRoot = when {
      overriddenLibRoot != null -> Path.of(overriddenLibRoot)
      shouldDownload() -> getOrDownloadLibRoot()
      else -> {
        PluginPathManager.getPluginResource(LibGhosttyVtLocator::class.java, "libghostty-vt")?.toPath()
        ?: throw IOException(missingLibraryMessage(relativeLibPath, libFile = null))
      }
    }
    val libFile = libRoot.resolve(relativeLibPath)
    if (!Files.isRegularFile(libFile)) {
      throw IOException(missingLibraryMessage(relativeLibPath, libFile))
    }
    return libFile
  }

  /**
   * True when the library must be downloaded: running from sources (including tests),
   * but not from a dev build.
   */
  internal fun shouldDownload(): Boolean {
    return PluginManagerCore.isRunningFromSources() && !AppMode.isRunningFromDevBuild()
  }

  /**
   * Downloads the library archive and returns its extracted root, which holds the
   * `<os>-<arch>` subdirectories for all platforms.
   *
   * Serves running from sources, where no build step prepares the library.
   *
   * The method calls the downloader by reflection, because this module does not depend on it.
   * In the IDE, the plugin set gives the class loader of the downloader module.
   * Without a plugin set, or when that module has no class loader, the method uses its own class loader.
   * Its own class loader serves a unit test and a run whose class path holds the downloader.
   *
   * @throws IOException if the download fails, or if no class loader sees the downloader
   */
  @Throws(IOException::class)
  internal fun getOrDownloadLibRoot(): Path {
    try {
      val classLoader = PluginManagerCore.getPluginSetOrNull()
                          ?.findEnabledModule(PluginModuleId.getId(DOWNLOADER_MODULE, PluginModuleId.JETBRAINS_NAMESPACE))
                          ?.pluginClassLoader
                        ?: LibGhosttyVtLocator::class.java.classLoader
      val method = classLoader.loadClass(DOWNLOADER_CLASS).getMethod("getOrDownloadLibRoot", Path::class.java)
      try {
        return method.invoke(null, Path.of(PathManager.getCommunityHomePath())) as Path
      }
      catch (e: InvocationTargetException) {
        throw e.cause ?: e
      }
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      throw IOException(
        "Cannot download the libghostty-vt library archive. " +
        "Set -D$GHOSTTY_VT_LIB_ROOT_PROPERTY=<dir> to a directory holding the <os>-<arch> library subdirectories " +
        "to use a local copy. See plugins/terminal/emulator/README.md.", e
      )
    }
  }

  private const val DOWNLOADER_MODULE = "intellij.platform.buildScripts.downloader"
  private const val DOWNLOADER_CLASS = "org.jetbrains.intellij.build.dependencies.TerminalLibGhosttyVtDownloader"

  private fun missingLibraryMessage(relativeLibPath: String, libFile: Path?): String {
    val message = if (libFile == null) "Cannot find $relativeLibPath" else "Cannot find $relativeLibPath: $libFile is not a file"
    if (System.getProperty(GHOSTTY_VT_LIB_ROOT_PROPERTY) != null) {
      return "$message (the library root is overridden with -D$GHOSTTY_VT_LIB_ROOT_PROPERTY)"
    }
    return message
  }

  /**
   * The [libraryPath] of the current platform.
   *
   * @throws IOException if there is no libghostty-vt build for this OS or CPU architecture
   */
  @OptIn(LowLevelLocalMachineAccess::class)
  @Throws(IOException::class)
  @VisibleForTesting
  internal fun currentPlatformLibraryPath(): String {
    val os = when (OS.CURRENT) {
      OS.Windows -> GhosttyVtOs.WINDOWS
      OS.macOS -> GhosttyVtOs.MACOS
      OS.Linux -> GhosttyVtOs.LINUX
      else -> throw IOException("libghostty-vt is not available for OS ${SystemInfoRt.OS_NAME}")
    }
    val arch = when (CpuArch.CURRENT) {
      CpuArch.X86_64 -> GhosttyVtArch.X86_64
      CpuArch.ARM64 -> GhosttyVtArch.AARCH64
      else -> throw IOException("libghostty-vt is not available for CPU architecture ${System.getProperty("os.arch")}")
    }
    return libraryPath(os, arch)
  }

  /**
   * The `<os>-<arch>/<library file>` path of the [os]/[arch] build, relative to the
   * native-library root.
   */
  internal fun libraryPath(os: GhosttyVtOs, arch: GhosttyVtArch): String {
    return "${os.directoryName}-${arch.directoryName}/${os.libraryFileName}"
  }
}

/** Base name from which [System.mapLibraryName]-style file names are derived. */
internal const val GHOSTTY_VT_LIB_BASE_NAME: String = "ghostty-vt"

/**
 * Overrides the directory holding the `<os>-<arch>/` subdirectories.
 * Takes precedence over every other lookup in [LibGhosttyVtLocator.findLibraryFile].
 */
internal const val GHOSTTY_VT_LIB_ROOT_PROPERTY: String = "ij.terminal.libghostty-vt.lib.root"

internal enum class GhosttyVtOs(val directoryName: String, val libraryFileName: String) {
  // The file names follow the conventions of `System.mapLibraryName`, hence no `lib` prefix on Windows.
  WINDOWS("windows", "$GHOSTTY_VT_LIB_BASE_NAME.dll"),
  MACOS("macos", "lib$GHOSTTY_VT_LIB_BASE_NAME.dylib"),
  LINUX("linux", "lib$GHOSTTY_VT_LIB_BASE_NAME.so"),
}

internal enum class GhosttyVtArch(val directoryName: String) {
  X86_64("x86_64"),
  AARCH64("aarch64"),
}

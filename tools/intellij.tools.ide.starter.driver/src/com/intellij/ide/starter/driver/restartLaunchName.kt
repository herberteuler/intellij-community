package com.intellij.ide.starter.driver

import org.jetbrains.annotations.ApiStatus

/**
 * Appends `-restarted` to [currentLaunchName]. A name that already ends with it gets a number: `-restarted` becomes
 * `-restarted2`, and `-restarted2` becomes `-restarted3`.
 */
@ApiStatus.Internal
fun nextRestartLaunchNameOf(currentLaunchName: String): String {
  val launchName = currentLaunchName.takeIf { it.isNotEmpty() }
  val match = launchName?.let(RESTARTED_LAUNCH_NAME::matchEntire)
  if (match != null) {
    val baseName = match.groupValues[1].takeIf { it.isNotEmpty() }
    val restartNumber = match.groupValues[2].toIntOrNull() ?: 1
    return listOfNotNull(baseName, "restarted${restartNumber + 1}").joinToString("-")
  }

  return launchName?.let { "$it-restarted" } ?: "restarted"
}

private val RESTARTED_LAUNCH_NAME = Regex("""(?:(.+)-)?restarted(\d*)""")

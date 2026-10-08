package com.intellij.python.hatch.impl.sdk

import com.intellij.python.hatch.cli.HatchEnvironment
import com.jetbrains.python.sdk.add.v2.PathHolder
import com.intellij.python.hatch.HatchPyTool
import com.intellij.python.pytools.common.FusId
import com.intellij.execution.target.TargetedCommandLineBuilder
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.python.hatch.common.icons.PythonHatchCommonIcons
import com.jetbrains.python.PyInternalExecApi
import com.jetbrains.python.sdk.flavors.CPythonSdkFlavor
import com.jetbrains.python.sdk.flavors.PyFlavorData
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path
import javax.swing.Icon

@ApiStatus.Internal
@PyInternalExecApi
data class HatchSdkFlavorData(val hatchEnvironmentName: String?) : PyFlavorData {
  override fun prepareTargetCommandLine(sdk: Sdk, targetCommandLineBuilder: TargetedCommandLineBuilder) {
    PyFlavorData.Empty.prepareTargetCommandLine(sdk, targetCommandLineBuilder)
  }
}

@ApiStatus.Internal
@PyInternalExecApi
object HatchSdkFlavor : CPythonSdkFlavor<HatchSdkFlavorData>() {
  override fun getIcon(): Icon = PythonHatchCommonIcons.Logo
  override fun getFlavorDataClass(): Class<HatchSdkFlavorData> = HatchSdkFlavorData::class.java
  override fun getManager(): FusId = HatchPyTool.getInstance().fusId

  /** Hatch names an environment of a project by its name. Data without a name stands for the default environment. */
  override fun toolEnvRefOf(pythonBinary: PathHolder, data: HatchSdkFlavorData): String =
    data.hatchEnvironmentName ?: HatchEnvironment.DEFAULT.name
  override fun isValidSdkPath(pythonBinaryPath: Path): Boolean = false
  override fun isPlatformIndependent(): Boolean = true
}
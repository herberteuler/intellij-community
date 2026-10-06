// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.roots.ui.configuration

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.projectRoots.AdditionalDataConfigurable
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.SdkAdditionalData
import com.intellij.openapi.projectRoots.SdkModel
import com.intellij.openapi.projectRoots.SdkModificator
import com.intellij.openapi.projectRoots.SdkType
import com.intellij.openapi.util.JDOMUtil
import com.intellij.platform.eel.provider.LocalEelMachine
import com.intellij.platform.workspace.jps.entities.SdkEntity
import com.intellij.platform.workspace.jps.serialization.impl.ELEMENT_ADDITIONAL
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.assertErrorLogged
import com.intellij.workspaceModel.ide.impl.GlobalWorkspaceModel
import org.jdom.Element
import java.nio.file.Path

class SdkModificatorCommitConsistencyTest : HeavyPlatformTestCase() {
  override fun setUp() {
    super.setUp()
    SdkType.EP_NAME.point.registerExtension(ConsistencyTestSdkType(), testRootDisposable)
  }

  fun `test change listeners see the committed bridge`() {
    val sdk = createRegisteredSdk()
    var bridgeNameInListener: String? = null
    var bridgeDataInListener: String? = null
    var entityDataInListener: String? = null
    ApplicationManager.getApplication().messageBus.connect(testRootDisposable)
      .subscribe(ProjectJdkTable.JDK_TABLE_TOPIC, object : ProjectJdkTable.Listener {
        override fun jdkNameChanged(jdk: Sdk, previousName: String) {
          bridgeNameInListener = jdk.name
          bridgeDataInListener = serializeAdditionalData(jdk)
          entityDataInListener = findSdkEntity("bar").additionalData
        }
      })

    val modificator = sdk.sdkModificator
    modificator.name = "bar"
    modificator.sdkAdditionalData = ConsistencyTestSdkAdditionalData("b")
    runWriteAction { modificator.commitChanges() }

    assertEquals("bar", bridgeNameInListener)
    assertEquals(entityDataInListener, bridgeDataInListener)
    assertEquals("b", (sdk.sdkAdditionalData as ConsistencyTestSdkAdditionalData).data)
  }

  fun `test commit of null additional data clears the bridge`() {
    val sdk = createRegisteredSdk()

    val modificator = sdk.sdkModificator
    modificator.sdkAdditionalData = null
    runWriteAction { modificator.commitChanges() }

    assertEquals("", findSdkEntity("foo").additionalData)
    assertNull(sdk.sdkAdditionalData)
  }

  fun `test applyChangesWithoutWriteAction on a registered SDK reports an error`() {
    val sdk = createRegisteredSdk()

    val modificator = sdk.sdkModificator
    modificator.sdkAdditionalData = ConsistencyTestSdkAdditionalData("b")
    assertErrorLogged<Throwable> {
      modificator.applyChangesWithoutWriteAction()
    }
  }

  private fun createRegisteredSdk(): Sdk {
    val sdkTable = ProjectJdkTable.getInstance()
    val sdk = sdkTable.createSdk("foo", ConsistencyTestSdkType.getInstance())
    val modificator = sdk.sdkModificator
    modificator.sdkAdditionalData = ConsistencyTestSdkAdditionalData("a")
    runWriteAction {
      modificator.commitChanges()
      sdkTable.addJdk(sdk, testRootDisposable)
    }
    return sdk
  }

  private fun findSdkEntity(name: String): SdkEntity {
    return GlobalWorkspaceModel.getInstance(LocalEelMachine).currentSnapshot
      .entities(SdkEntity::class.java)
      .single { it.name == name }
  }

  private fun serializeAdditionalData(sdk: Sdk): String {
    val additionalDataElement = Element(ELEMENT_ADDITIONAL)
    sdk.sdkType.saveAdditionalData(sdk.sdkAdditionalData!!, additionalDataElement)
    return JDOMUtil.write(additionalDataElement)
  }
}

private class ConsistencyTestSdkType : SdkType("ConsistencyTest") {
  companion object {
    fun getInstance(): ConsistencyTestSdkType = findInstance(ConsistencyTestSdkType::class.java)
  }

  override fun suggestHomePath(path: Path): String? = null

  override fun isValidSdkHome(path: String): Boolean = false

  override fun suggestSdkName(currentSdkName: String?, sdkHome: String): String = ""

  override fun createAdditionalDataConfigurable(sdkModel: SdkModel, sdkModificator: SdkModificator): AdditionalDataConfigurable? = null

  override fun getPresentableName(): String = "ConsistencyTest"

  override fun saveAdditionalData(additionalData: SdkAdditionalData, additional: Element) {
    additional.setAttribute("data", (additionalData as ConsistencyTestSdkAdditionalData).data)
  }

  override fun loadAdditionalData(additional: Element): SdkAdditionalData {
    return ConsistencyTestSdkAdditionalData(additional.getAttributeValue("data") ?: "")
  }
}

private class ConsistencyTestSdkAdditionalData(val data: String) : SdkAdditionalData

// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("DaemonServicesFactory")
@file:Suppress("IO_FILE_USAGE")

package org.jetbrains.plugins.gradle.internal.daemon

import com.intellij.gradle.toolingExtension.util.GradleVersionUtil
import org.gradle.initialization.BuildLayoutParameters
import org.gradle.internal.jvm.JavaInfo
import org.gradle.internal.logging.events.OutputEventListener
import org.gradle.internal.service.ServiceRegistry
import org.gradle.launcher.daemon.client.DaemonClientFactory
import org.gradle.launcher.daemon.configuration.DaemonParameters
import org.gradle.launcher.daemon.startup.DaemonPriority
import org.gradle.tooling.internal.protocol.InternalBuildProgressListener
import org.gradle.util.GradleVersion
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Optional

object GradleDaemonServicesFactory {

  fun getServices(daemonClientFactory: DaemonClientFactory, myServiceDirectoryPath: String?): ServiceRegistry {
    val layoutParameters = getBuildLayoutParameters(myServiceDirectoryPath)
    val daemonParameters = getDaemonParameters(layoutParameters)
    val factory = when {
      GradleVersionUtil.isCurrentGradleAtLeast("9.8") -> DaemonServicesFacade98
      GradleVersionUtil.isCurrentGradleAtLeast("9.5") -> DaemonServicesFacade95
      GradleVersionUtil.isCurrentGradleAtLeast("8.13") -> DaemonServicesFacade813
      GradleVersionUtil.isCurrentGradleAtLeast("8.8") -> DaemonServicesFacade88
      else -> LegacyDaemonServicesDaemonServicesFacade
    }
    return factory.getServices(daemonClientFactory, daemonParameters, layoutParameters)
  }


  private fun getBuildLayoutParameters(myServiceDirectoryPath: String?): BuildLayoutParameters {
    val layout = BuildLayoutParameters()
    if (!myServiceDirectoryPath.isNullOrEmpty()) {
      layout.setGradleUserHomeDir(File(myServiceDirectoryPath))
    }
    return layout
  }
}

private abstract class DaemonServicesFacade {
  abstract fun getServices(
    daemonClientFactory: DaemonClientFactory,
    parameters: DaemonParameters,
    layoutParameters: BuildLayoutParameters,
  ): ServiceRegistry

  fun invoke(
    daemonClientFactory: DaemonClientFactory,
    parameters: DaemonParameters,
    layoutParameters: BuildLayoutParameters,
  ): ServiceRegistry {
    return try {
      getServices(daemonClientFactory, parameters, layoutParameters)
    }
    catch (e: ReflectiveOperationException) {
      throw RuntimeException("Cannot resolve ServiceRegistry by reflection. Gradle: ${GradleVersion.current()}", e)
    }
    catch (e: ClassCastException) {
      throw RuntimeException("Unable to cast the result of the invocation to ServiceRegistry. Gradle: ${GradleVersion.current()} ", e)
    }
  }
}

private object DaemonServicesFacade98 : DaemonServicesFacade() {
  override fun getServices(
    daemonClientFactory: DaemonClientFactory,
    parameters: DaemonParameters,
    layoutParameters: BuildLayoutParameters,
  ): ServiceRegistry = createBuildClientServicesAfter9Dot5(daemonClientFactory, parameters, layoutParameters) {
    createDaemonRequestContextAfter8Dot10(DaemonPriority::class.java, DaemonPriority.NORMAL)
  }
}

private object DaemonServicesFacade95 : DaemonServicesFacade() {
  override fun getServices(
    daemonClientFactory: DaemonClientFactory,
    parameters: DaemonParameters,
    layoutParameters: BuildLayoutParameters,
  ): ServiceRegistry = createBuildClientServicesAfter9Dot5(daemonClientFactory, parameters, layoutParameters) {
    getDaemonRequestContextAfter8Dot8()
  }
}

private object DaemonServicesFacade813 : DaemonServicesFacade() {
  override fun getServices(
    daemonClientFactory: DaemonClientFactory,
    parameters: DaemonParameters,
    layoutParameters: BuildLayoutParameters,
  ): ServiceRegistry {
    val daemonRequestContextClass = Class.forName("org.gradle.launcher.daemon.context.DaemonRequestContext")
    val serviceLookupClass = Class.forName("org.gradle.internal.service.ServiceLookup")
    val createBuildClientServicesMethod: Method = DaemonClientFactory::class.java.getDeclaredMethod(
      "createBuildClientServices",
      serviceLookupClass,
      DaemonParameters::class.java,
      daemonRequestContextClass,
      InputStream::class.java,
      Optional::class.java
    )
    val serviceLookupDelegate = getGradleServiceLookup()
    val serviceLookup: Any = GradleServiceLookupProxy.newProxyInstance(serviceLookupDelegate)
    val requestContext = getDaemonRequestContextAfter8Dot8()
    return createBuildClientServicesMethod.invoke(
      daemonClientFactory,
      serviceLookup,
      parameters,
      requestContext,
      ByteArrayInputStream(ByteArray(0)),
      Optional.empty<InternalBuildProgressListener>()
    ) as ServiceRegistry
  }
}

private object DaemonServicesFacade88 : DaemonServicesFacade() {
  override fun getServices(
    daemonClientFactory: DaemonClientFactory,
    parameters: DaemonParameters,
    layoutParameters: BuildLayoutParameters,
  ): ServiceRegistry {
    val daemonRequestContextClass = Class.forName("org.gradle.launcher.daemon.context.DaemonRequestContext")
    val serviceLookupClass = Class.forName("org.gradle.internal.service.ServiceLookup")
    val createBuildClientServicesMethod: Method = DaemonClientFactory::class.java.getDeclaredMethod(
      "createBuildClientServices",
      serviceLookupClass,
      DaemonParameters::class.java,
      daemonRequestContextClass,
      InputStream::class.java
    )
    val serviceLookupDelegate = getGradleServiceLookup()
    val serviceLookup: Any = GradleServiceLookupProxy.newProxyInstance(serviceLookupDelegate)
    val requestContext = getDaemonRequestContextAfter8Dot8()
    return createBuildClientServicesMethod.invoke(
      daemonClientFactory,
      serviceLookup,
      parameters,
      requestContext,
      ByteArrayInputStream(ByteArray(0))
    ) as ServiceRegistry
  }
}

private object LegacyDaemonServicesDaemonServicesFacade : DaemonServicesFacade() {
  override fun getServices(
    daemonClientFactory: DaemonClientFactory,
    parameters: DaemonParameters,
    layoutParameters: BuildLayoutParameters,
  ): ServiceRegistry {
    val method: Method = DaemonClientFactory::class.java.getDeclaredMethod("createBuildClientServices",
                                                                           OutputEventListener::class.java,
                                                                           DaemonParameters::class.java,
                                                                           InputStream::class.java
    )
    val invocationResult: Any = method.invoke(daemonClientFactory,
                                              OutputEventListener {},
                                              parameters,
                                              ByteArrayInputStream(ByteArray(0))
    )
    return invocationResult as ServiceRegistry
  }
}

private fun createBuildClientServicesAfter9Dot5(
  daemonClientFactory: DaemonClientFactory,
  parameters: DaemonParameters,
  layoutParameters: BuildLayoutParameters,
  requestContextProvider: () -> Any,
): ServiceRegistry {
  val daemonRequestContextClass = Class.forName("org.gradle.launcher.daemon.context.DaemonRequestContext")
  val serviceLookupClass = Class.forName("org.gradle.internal.service.ServiceLookup")
  val buildLayoutConfigurationClass = Class.forName("org.gradle.initialization.layout.BuildLayoutConfiguration")
  val createBuildClientServicesMethod: Method = DaemonClientFactory::class.java.getDeclaredMethod(
    "createBuildClientServices",
    serviceLookupClass,
    DaemonParameters::class.java,
    daemonRequestContextClass,
    buildLayoutConfigurationClass,
    InputStream::class.java,
    Optional::class.java
  )
  val serviceLookupDelegate = getGradleServiceLookup()
  val serviceLookup: Any = GradleServiceLookupProxy.newProxyInstance(serviceLookupDelegate)
  val requestContext = requestContextProvider()
  val buildLayoutConfiguration = buildLayoutConfigurationClass
    .getConstructor(BuildLayoutParameters::class.java)
    .newInstance(layoutParameters)
  return createBuildClientServicesMethod.invoke(
    daemonClientFactory,
    serviceLookup,
    parameters,
    requestContext,
    buildLayoutConfiguration,
    ByteArrayInputStream(ByteArray(0)),
    Optional.empty<InternalBuildProgressListener>()
  ) as ServiceRegistry
}

private fun getGradleServiceLookup(): GradleServiceLookup {
  val userInputReceiverClass = Class.forName("org.gradle.internal.logging.console.GlobalUserInputReceiver")
  val userInputReceiver = Proxy.newProxyInstance(
    userInputReceiverClass.classLoader,
    arrayOf(userInputReceiverClass),
    InvocationHandler { _, _, _ -> }
  )
  return GradleServiceLookup().apply {
    register(OutputEventListener::class.java, OutputEventListener.NO_OP)
    register(userInputReceiverClass, userInputReceiver)
  }
}

private fun getDaemonRequestContextAfter8Dot8(): Any {
  if (GradleVersionUtil.isCurrentGradleAtLeast("8.10")) {
    // 8.10 to 9.7 DaemonPriority located in the org.gradle.launcher.daemon.configuration package
    val daemonPriorityClass = Class.forName("org.gradle.launcher.daemon.configuration.DaemonPriority")
    val normalDaemonPriority = daemonPriorityClass.enumConstants.first { (it as Enum<*>).name == "NORMAL" }
    return createDaemonRequestContextAfter8Dot10(daemonPriorityClass, normalDaemonPriority)
  }
  val requestContextClass = Class.forName("org.gradle.launcher.daemon.context.DaemonRequestContext")
  val nativeServicesModeClass = getNativeServicesModeClass()
  val daemonJvmCriteriaClass = Class.forName("org.gradle.launcher.daemon.toolchain.DaemonJvmCriteria")
  val nativeServiceModeValue = nativeServicesModeClass.enumConstants[2]
  val legacyDaemonPriorityClass = Class.forName("org.gradle.launcher.daemon.configuration.DaemonParameters\$Priority")
  if (!legacyDaemonPriorityClass.isEnum) {
    throw IllegalStateException("DaemonParameters.Priority is expected to be a Enum. Gradle version: ${GradleVersion.current()}")
  }
  val normalDaemonPriority = legacyDaemonPriorityClass.enumConstants[1]
  if (GradleVersionUtil.isCurrentGradleAtLeast("8.9")) {
    val requestContextConstructor = requestContextClass.getDeclaredConstructor(
      daemonJvmCriteriaClass,
      Collection::class.java,
      Boolean::class.java,
      nativeServicesModeClass,
      legacyDaemonPriorityClass
    )
    return requestContextConstructor.newInstance(
      null,
      emptyList<String>(),
      false,
      nativeServiceModeValue,
      normalDaemonPriority
    )
  }
  else {
    val requestContextConstructor = requestContextClass.getDeclaredConstructor(
      JavaInfo::class.java,
      daemonJvmCriteriaClass,
      Collection::class.java,
      Boolean::class.java,
      nativeServicesModeClass,
      legacyDaemonPriorityClass
    )
    return requestContextConstructor.newInstance(
      null,
      null,
      emptyList<String>(),
      false,
      nativeServiceModeValue,
      normalDaemonPriority
    )
  }
}

private fun createDaemonRequestContextAfter8Dot10(daemonPriorityClass: Class<*>, daemonPriority: Any): Any {
  val requestContextClass = Class.forName("org.gradle.launcher.daemon.context.DaemonRequestContext")
  val nativeServicesModeClass = getNativeServicesModeClass()
  val daemonJvmCriteriaClass = Class.forName("org.gradle.launcher.daemon.toolchain.DaemonJvmCriteria")
  val requestContextConstructor = requestContextClass.getDeclaredConstructor(
    daemonJvmCriteriaClass,
    Collection::class.java,
    Boolean::class.java,
    nativeServicesModeClass,
    daemonPriorityClass
  )
  return requestContextConstructor.newInstance(
    null,
    emptyList<String>(),
    false,
    nativeServicesModeClass.enumConstants[2],
    daemonPriority
  )
}

private fun getNativeServicesModeClass(): Class<*> {
  val nativeServicesClass = Class.forName("org.gradle.internal.nativeintegration.services.NativeServices")
  val nativeServicesModeClass = nativeServicesClass.declaredClasses.find { it.name.contains("NativeServicesMode") }
                                ?: throw IllegalStateException("The NativeServicesMode class is not found inside the NativeServices class. " +
                                                               "Gradle version: ${GradleVersion.current()}")
  if (!nativeServicesModeClass.isEnum) {
    throw IllegalStateException("NativeServicesMode is expected to be a Enum. Gradle version: ${GradleVersion.current()}")
  }
  return nativeServicesModeClass
}

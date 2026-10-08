// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins.newui

import com.intellij.ide.IdeBundle
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.InlineBanner
import com.intellij.ui.InlineBannerBase
import com.intellij.util.PlatformUtils
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls

@ApiStatus.Internal
object UnavailableWithoutSubscriptionComponent {
  @JvmStatic
  fun getHelpTooltip(): @Nls String? {
    return IdeBundle.message("plugin.available.in.commercial.ide.text", getCommercialIdeData() ?: return null)
  }

  fun getBanner(): InlineBanner? {
    val commercialIdeData = getCommercialIdeData() ?: return null
    return createPluginBanner(
      message = IdeBundle.message("plugin.available.in.commercial.ide.text", commercialIdeData),
      addStartTrial = PluginSubscriptionPromo.getInstance()?.isTrialAvailable() == true,
    )
  }
}


@ApiStatus.Internal
object PartiallyAvailableComponent {
  fun getBanner(): InlineBannerBase? {
    return createPluginBanner(IdeBundle.message("plugin.has.ultimate.features.text", getCommercialIdeData() ?: return null))
  }
}

private fun getCommercialIdeData(): @Nls String? {
  return when {
    PlatformUtils.isPyCharmPro() -> IdeBundle.message("subscription.dialog.pro")
    PlatformUtils.isIntelliJ() -> IdeBundle.message("subscription.dialog.ultimate")
    else -> null
  }
}

private fun createPluginBanner(message: @Nls String, addStartTrial: Boolean = false): InlineBanner {
  val banner = InlineBanner(message, EditorNotificationPanel.Status.Info).showCloseButton(false)
  if (addStartTrial) {
    banner.addAction(IdeBundle.message("link.start.trial")) {
      PluginSubscriptionPromo.getInstance()?.startTrial()
    }
  }
  banner.addAction(IdeBundle.message("link.activate.subscription")) {
    val action = ActionManager.getInstance().getAction("Register")
    val dataContext = DataContext { dataId: String? ->
      when (dataId) {
        "register.request.direct.call" -> true
        else -> null
      }
    }

    action?.actionPerformed(AnActionEvent.createEvent(dataContext, Presentation(), "", ActionUiKind.NONE, null))
  }
  return banner
}
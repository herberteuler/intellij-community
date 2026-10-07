// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.diagnostic;

import com.intellij.diagnostic.PluginException;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.PluginAware;
import com.intellij.openapi.extensions.PluginDescriptor;
import com.intellij.openapi.util.NlsActions;
import com.intellij.openapi.util.NlsContexts.DetailedDescription;
import com.intellij.util.Consumer;
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;

/// Override this class and register the implementation in the plugin.xml file to provide custom reporting for errors related to the plugin:
/// ```
///   <extensions xmlns="com.intellij">
///     <errorHandler implementation="my.plugin.package.MyErrorHandler"/>
///   </extensions>
/// ```
public abstract class ErrorReportSubmitter implements PluginAware {
  @ApiStatus.Internal
  public static final ExtensionPointName<ErrorReportSubmitter> EP_NAME = ExtensionPointName.create("com.intellij.errorHandler");

  private PluginDescriptor myPlugin;

  /// Called by the framework. Allows identifying the plugin that provided this extension.
  @Override
  public void setPluginDescriptor(@NotNull PluginDescriptor plugin) {
    myPlugin = plugin;
  }

  /// @return plugin that provided this particular extension
  public PluginDescriptor getPluginDescriptor() {
    return myPlugin;
  }

  /// @return an action text to be used in Error Reporter user interface, e.g. "Report to JetBrains".
  @ApiStatus.OverrideOnly
  public abstract @NlsActions.ActionText @NotNull String getReportActionText();

  /// @return a text of a privacy notice to be shown in the dialog (in HTML; links are allowed).
  @ApiStatus.OverrideOnly
  @RequiresBackgroundThread
  public @DetailedDescription @Nullable String getPrivacyNoticeText() {
    return null;
  }

  /// If this reporter allows a user to identify themselves, the method should return either the name of an account that will be used
  /// for submitting reports or an empty string. Otherwise, it should return `null`.
  @ApiStatus.OverrideOnly
  @RequiresBackgroundThread
  public @Nullable String getReporterAccount() {
    return null;
  }

  /// If [#getReporterAccount()] returns a non-null value, this method may be called when a user wants to change a reporter account.
  /// It is expected to be synchronous - i.e., do not return until a user finished entering their data.
  @ApiStatus.OverrideOnly
  public void changeReporterAccount(@NotNull Component parentComponent) {
    throw new UnsupportedOperationException();
  }

  /// This method is called whenever an exception in a plugin code had happened, and a user decided to report a problem to the plugin vendor.
  ///
  /// **Note**: the method is not abstract because compatibility, but all implementations must override it.
  ///
  /// @param events          a non-empty sequence of error descriptors.
  /// @param additionalInfo  additional information provided by a user.
  /// @param parentComponent UI component to use as a parent in any UI activity from a submitter.
  /// @param consumer        a callback to be called after sending is finished (or failed).
  /// @return `true` if reporting was started (must invoke `consumer` callback with a result), `false` if a report can't be sent at the moment.
  @ApiStatus.OverrideOnly
  public boolean submit(
    @SuppressWarnings("SSBasedInspection") @NotNull IdeaLoggingEvent @NotNull [] events,
    @Nullable String additionalInfo,
    @NotNull Component parentComponent,
    @SuppressWarnings("UsagesOfObsoleteApi") @NotNull Consumer<? super SubmittedReportInfo> consumer
  ) {
    PluginException.reportDeprecatedDefault(getClass(), "submit(IdeaLoggingEvent[],String,Component,Supplier)", "The method must be overridden");
    consumer.consume(new SubmittedReportInfo(null, "Invalid reporter: " + getClass().getName(), SubmittedReportInfo.SubmissionStatus.FAILED));
    return false;
  }
}

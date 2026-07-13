// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.compose.ide.plugin.resources.actions.svgPaste

import com.intellij.compose.ide.plugin.resources.vectorDrawable.preview.BaseVectorDrawablePreviewRenderer
import com.intellij.compose.ide.plugin.shared.ComposeIdeBundle
import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.VFileCopyEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiManager
import com.intellij.refactoring.SkipOverwriteChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

internal class ComposeResourcesSvgPasteListener : AsyncFileListener {
  override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
    val pastedSvgs = events.filter { event ->
      event.path.endsWith(".svg", ignoreCase = true) && when (event) {
        is VFileCopyEvent, is VFileMoveEvent -> true
        is VFileCreateEvent -> event.isFromRefresh
        else -> false
      }
    }
    if (pastedSvgs.isEmpty()) return null
    return object : AsyncFileListener.ChangeApplier {
      override fun afterVfsChange() = queueSvgConversions(pastedSvgs)
    }
  }

  private fun queueSvgConversions(pastedSvgs: List<VFileEvent>) {
    pastedSvgs
      .mapNotNull { event ->
        when (event) {
          is VFileCopyEvent -> event.findCreatedFile()
          is VFileMoveEvent, is VFileCreateEvent -> event.file
          else -> null
        }
      }
      .groupBy { file -> ProjectLocator.getInstance().guessProjectForFile(file) }
      .forEach { (project, svgs) ->
        if (project == null || project.isDisposed) return@forEach
        project.service<ComposeResourcesSvgConversionQueue>().queueFiles(svgs)
      }
  }
}

internal suspend fun showBatchConversionDialog(project: Project, svgs: List<VirtualFile>) {
  val validSvgs = svgs.filter { it.isValid }
  if (validSvgs.isEmpty()) return

  val shouldConvert = when (ComposeResourcesSvgPasteDialog.getPasteBehavior(project)) {
    SvgPasteBehavior.NEVER_CONVERT -> false
    SvgPasteBehavior.ALWAYS_CONVERT -> true
    SvgPasteBehavior.ASK -> withContext(Dispatchers.EDT) {
      !project.isDisposed && ComposeResourcesSvgPasteDialog(project, validSvgs.size).showAndGet()
    }
  }
  if (shouldConvert) convertSvgFiles(project, validSvgs)
}

private suspend fun convertSvgFiles(project: Project, svgs: List<VirtualFile>) {
  val renderer = BaseVectorDrawablePreviewRenderer.getInstance() ?: return

  val (converted, conversionFailures) = withContext(Dispatchers.Default) { convertToVectorDrawables(renderer, svgs) }
  val approved = resolveOverwriteConflicts(project, converted)
  val (written, writeFailures) = writeVectorDrawables(project, approved)

  val singleWrittenFile = written.singleOrNull()
  if (singleWrittenFile != null) {
    withContext(Dispatchers.EDT) {
      if (!project.isDisposed && singleWrittenFile.isValid) {
        FileEditorManager.getInstance(project).openFile(singleWrittenFile, true)
      }
    }
  }
  notifyFailures(project, conversionFailures + writeFailures)
}

private fun convertToVectorDrawables(
  renderer: BaseVectorDrawablePreviewRenderer,
  svgs: List<VirtualFile>,
): Pair<List<Conversion>, List<String>> {
  val converted = mutableListOf<Conversion>()
  val failedFileNames = mutableListOf<String>()

  for (svg in svgs) {
    if (!svg.isValid) continue
    val svgPath = svg.toNioPathOrNull() ?: continue

    val errors = StringBuilder()
    val xml = try {
      renderer.convertSvgToVectorDrawable(svgPath, errors)
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      errors.append(e.message ?: e.javaClass.name)
      null
    }

    if (xml != null) {
      converted += Conversion(svg, xml)
    }
    else {
      log.warn("Failed to convert SVG '${svg.name}' to vector drawable: $errors")
      failedFileNames += svg.name
    }
  }
  return converted to failedFileNames
}

private suspend fun resolveOverwriteConflicts(project: Project, converted: List<Conversion>): List<Conversion> {
  val (clashingSvgs, prompts) = readAction {
    val clashing = mutableSetOf<VirtualFile>()
    val toAsk = mutableListOf<Pair<Conversion, PsiDirectory>>()
    if (!project.isDisposed) {
      val psiManager = PsiManager.getInstance(project)
      for (conversion in converted) {
        val vfsDir = conversion.svg.parent ?: continue
        if (vfsDir.findChild(conversion.svg.targetXmlName()) == null) continue
        clashing += conversion.svg
        psiManager.findDirectory(vfsDir)?.let { toAsk += conversion to it }
      }
    }
    clashing to toAsk
  }
  if (clashingSvgs.isEmpty()) return converted

  val title = ComposeIdeBundle.message("compose.svg.paste.dialog.title")
  val toOverwrite = mutableSetOf<VirtualFile>()
  var choiceForAll: SkipOverwriteChoice? = null

  for ((index, prompt) in prompts.withIndex()) {
    val (conversion, psiDir) = prompt
    val choice = choiceForAll ?: withContext(Dispatchers.EDT) {
      if (project.isDisposed) null
      else SkipOverwriteChoice.askUser(psiDir, conversion.svg.targetXmlName(), title, index < prompts.lastIndex)
    } ?: return emptyList()

    if (choice == SkipOverwriteChoice.OVERWRITE_ALL || choice == SkipOverwriteChoice.SKIP_ALL) choiceForAll = choice
    if (choice == SkipOverwriteChoice.OVERWRITE || choice == SkipOverwriteChoice.OVERWRITE_ALL) toOverwrite += conversion.svg
  }
  return converted.mapNotNull { conversion ->
    when (conversion.svg) {
      !in clashingSvgs -> conversion
      in toOverwrite -> conversion.copy(mayOverwriteTarget = true)
      else -> null
    }
  }
}

private suspend fun writeVectorDrawables(project: Project, approved: List<Conversion>): Pair<List<VirtualFile>, List<String>> {
  val written = mutableListOf<VirtualFile>()
  val failedFileNames = mutableListOf<String>()

  if (approved.isEmpty() || project.isDisposed) return written to failedFileNames

  writeCommandAction(project, ComposeIdeBundle.message("compose.svg.paste.command.name")) {
    for ((svg, xml, mayOverwriteTarget) in approved) {
      if (!svg.isValid) continue
      val vfsDir = svg.parent ?: continue

      if (!mayOverwriteTarget && vfsDir.findChild(svg.targetXmlName()) != null) {
        log.info("Skipped converting '${svg.name}': '${svg.targetXmlName()}' appeared in the meantime")
        failedFileNames += svg.name
        continue
      }

      try {
        val vfsFile = vfsDir.findOrCreateChildData(project, svg.targetXmlName())
        VfsUtil.saveText(vfsFile, xml)
        svg.delete(project)
        written += vfsFile
      }
      catch (e: IOException) {
        log.warn("Failed to write the vector drawable for '${svg.name}'", e)
        failedFileNames += svg.name
      }
    }
  }
  return written to failedFileNames
}

private fun notifyFailures(project: Project, failedFileNames: List<String>) {
  if (failedFileNames.isEmpty()) return

  val message = when (failedFileNames.size) {
    1 -> ComposeIdeBundle.message("compose.svg.paste.single.fail.warning", failedFileNames.first())
    else -> ComposeIdeBundle.message("compose.svg.paste.multiple.fail.warning", failedFileNames.size)
  }
  NotificationGroupManager.getInstance()
    .getNotificationGroup("Compose resources SVG conversion")
    .createNotification(message, NotificationType.WARNING)
    .notify(project)
}

private fun VirtualFile.targetXmlName(): String = "$nameWithoutExtension.xml"

private data class Conversion(val svg: VirtualFile, val convertedXmlFileContent: String, val mayOverwriteTarget: Boolean = false)

private val log = logger<ComposeResourcesSvgPasteListener>()
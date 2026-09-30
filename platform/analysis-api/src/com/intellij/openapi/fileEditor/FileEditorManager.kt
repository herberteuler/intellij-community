// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileEditor

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Unmodifiable
import java.awt.Color
import javax.swing.JComponent
import javax.swing.border.Border

/**
 * @see FileEditorManagerListener
 */
abstract class FileEditorManager {
  companion object {
    @JvmField
    val USE_CURRENT_WINDOW: Key<Boolean> = Key.create("OpenFile.searchForOpen")

    @JvmStatic
    fun getInstance(project: Project): FileEditorManager = project.service()

    @JvmField
    val SEPARATOR_DISABLED: Key<Boolean> = Key.create("FileEditorSeparatorDisabled")

    @JvmField
    val SEPARATOR_BORDER: Key<Border> = Key.create("FileEditorSeparatorBorder")

    @JvmField
    val SEPARATOR_COLOR: Key<Color> = Key.create("FileEditorSeparatorColor")
  }

  abstract fun getComposite(file: VirtualFile): FileEditorComposite?

  @ApiStatus.Experimental
  abstract fun canOpenFile(file: VirtualFile): Boolean

  /**
   * @param file file to open. The file should be valid.
   * @return array of opened editors
   */
  abstract fun openFile(file: VirtualFile, focusEditor: Boolean): Array<FileEditor>

  @ApiStatus.Experimental
  open fun requestOpenFile(file: VirtualFile) {
    openFile(file, true)
  }

  abstract fun openFile(file: VirtualFile): @Unmodifiable List<FileEditor>

  /**
   * Opens a file.
   * Must be called from [EDT](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).
   *
   * @param file        file to open
   * @param focusEditor `true` if need to focus
   * @return array of opened editors
   */
  open fun openFile(file: VirtualFile, focusEditor: Boolean, searchForOpen: Boolean): Array<FileEditor> {
    throw UnsupportedOperationException("Not implemented")
  }

  /**
   * Closes all the editors opened for a given file.
   * Must be called from [EDT](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).
   *
   * @param file file to be closed.
   */
  abstract fun closeFile(file: VirtualFile)

  /**
   * Works as [openFile] but forces opening of text editor (see [TextEditor]).
   * If several text editors are opened, including the default one, the default text editor is focused (if requested) and returned.
   * Must be called from [EDT](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).
   *
   * @return opened text editor. The method returns `null` in case if text editor wasn't opened.
   */
  abstract fun openTextEditor(descriptor: OpenFileDescriptor, focusEditor: Boolean): Editor?

  /**
   * @return currently selected text editor. The method returns `null` in case
   * there is no selected editor at all or selected editor is not a text one.
   */
  @get:RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  abstract val selectedTextEditor: Editor?

  @ApiStatus.Experimental
  open fun getSelectedTextEditor(isLockFree: Boolean): Editor? {
    return selectedTextEditor
  }

  /**
   * @return currently selected TEXT editors including ones which were opened by guests during a collaborative development session
   * The method returns an empty array in case there are no selected editors or none of them is a text one.
   * Must be called from [EDT](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).
   */
  @get:ApiStatus.Experimental
  @get:RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  open val selectedTextEditorWithRemotes: Array<Editor>
    get() {
      val editor = selectedTextEditor
      return if (editor != null) arrayOf(editor) else Editor.EMPTY_ARRAY
    }

  /**
   * @return `true` if `file` is opened, `false` otherwise
   */
  abstract fun isFileOpen(file: VirtualFile): Boolean

  /**
   * @return `true` if `file` is opened, `false` otherwise.
   * Unlike [isFileOpen] includes files which were opened by all guests during a collaborative development session.
   */
  @ApiStatus.Experimental
  open fun isFileOpenWithRemotes(file: VirtualFile): Boolean {
    return isFileOpen(file)
  }

  /**
   * @return all opened files. The order of files in the array corresponds to the order of editor tabs.
   */
  abstract val openFiles: Array<VirtualFile>

  /**
   * @return all opened files, including ones which were opened by guests during a collaborative development session.
   * The order of files in the array corresponds to the order of host's editor tabs, order for guests isn't determined.
   * There are cases when only editors of a particular user are needed (e.g. a search scope 'open files'),
   * but at the same time editor notifications should be shown to all users.
   */
  @get:ApiStatus.Experimental
  abstract val openFilesWithRemotes: @Unmodifiable List<VirtualFile>

  open fun hasOpenFiles(): Boolean {
    return openFiles.isNotEmpty()
  }

  /**
   * Get a file currently being edited
   * Can depend on the current focus location and selection
   */
  @get:ApiStatus.Experimental
  abstract val currentFile: VirtualFile?

  /**
   * @return files currently selected. The method returns an empty array if there are no selected files.
   * If more than one file is selected (split), the file with the most recent focused editor is returned first.
   */
  abstract val selectedFiles: Array<VirtualFile>

  /**
   * @return editors currently selected. The method returns an empty array if no editors are open.
   */
  abstract val selectedEditors: Array<FileEditor>

  /**
   * @return editors currently selected including ones which were opened by guests during a collaborative development session.
   * The method returns an empty array if no editors are open.
   */
  @get:ApiStatus.Experimental
  open val selectedEditorWithRemotes: @Unmodifiable Collection<FileEditor>
    get() = listOf(*selectedEditors)

  /**
   * @return currently selected file editor or `null` if there is no selected editor at all.
   */
  open val selectedEditor: FileEditor?
    get() {
      val files = selectedFiles
      return if (files.isEmpty()) null else getSelectedEditor(files[0])
    }

  @get:ApiStatus.Experimental
  abstract val selectedEditorFlow: StateFlow<FileEditor?>

  /**
   * @return editor which is currently selected for a given file.
   * The method returns `null` if `file` is not opened.
   */
  abstract fun getSelectedEditor(file: VirtualFile): FileEditor?

  /**
   * @return current editors for the specified `file`
   */
  abstract fun getEditors(file: VirtualFile): Array<FileEditor>

  open fun getEditorList(file: VirtualFile): @Unmodifiable List<FileEditor> {
    return getEditors(file).asList()
  }

  /**
   * @return all editors for the specified `file`
   */
  abstract fun getAllEditors(file: VirtualFile): Array<FileEditor>

  abstract fun getAllEditorList(file: VirtualFile): @Unmodifiable List<FileEditor>

  /**
   * @return all open editors
   */
  abstract val allEditors: Array<FileEditor>

  /**
   * Adds the specified component above the editor and paints a separator line below it.
   * If a separator line is not needed, set the client property to `true`:
   *
   * `    component.putClientProperty(SEPARATOR_DISABLED, true);    `
   *
   * Otherwise, a separator line will be painted by a
   * [TEARLINE_COLOR][com.intellij.openapi.editor.colors.EditorColors.TEARLINE_COLOR] if it is set.
   *
   * This method allows adding several components above the editor.
   * To change the order of components, the specified component may implement the
   * [Weighted][com.intellij.openapi.util.Weighted] interface.
   */
  @RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  abstract fun addTopComponent(editor: FileEditor, component: JComponent)

  @RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  abstract fun removeTopComponent(editor: FileEditor, component: JComponent)

  /**
   * Adds the specified component below the editor and paints a separator line above it.
   * If a separator line is not needed, set the client property to `true`:
   *
   * `    component.putClientProperty(SEPARATOR_DISABLED, true);    `
   *
   * Otherwise, a separator line will be painted by a
   * [TEARLINE_COLOR][com.intellij.openapi.editor.colors.EditorColors.TEARLINE_COLOR] if it is set.
   *
   * This method allows adding several components below the editor.
   * To change the order of components, the specified component may implement the
   * [Weighted][com.intellij.openapi.util.Weighted] interface.
   */
  @RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  abstract fun addBottomComponent(editor: FileEditor, component: JComponent)

  @RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  abstract fun removeBottomComponent(editor: FileEditor, component: JComponent)

  /**
   * Adds specified `listener`.
   *
   * @param listener listener to be added
   * @deprecated Use [com.intellij.util.messages.MessageBus] instead: see [FileEditorManagerListener.FILE_EDITOR_MANAGER]
   */
  @Deprecated("Use [com.intellij.util.messages.MessageBus] instead: see [FileEditorManagerListener.FILE_EDITOR_MANAGER]")
  open fun addFileEditorManagerListener(listener: FileEditorManagerListener) {
  }

  /**
   * Removes specified `listener`.
   *
   * @param listener listener to be removed
   * @deprecated Use [FileEditorManagerListener.FILE_EDITOR_MANAGER] instead
   */
  @Deprecated("Use [FileEditorManagerListener.FILE_EDITOR_MANAGER] instead")
  open fun removeFileEditorManagerListener(listener: FileEditorManagerListener) {
  }

  @RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  fun openEditor(descriptor: OpenFileDescriptor, focusEditor: Boolean): @Unmodifiable List<FileEditor> {
    return openFileEditor(descriptor, focusEditor)
  }

  @RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  abstract fun openFileEditor(descriptor: FileEditorNavigatable, focusEditor: Boolean): @Unmodifiable List<FileEditor>

  /**
   * @return the project which the file editor manager is associated with.
   */
  abstract val project: Project

  /**
   * Selects a specified file editor tab for the specified editor.
   *
   * @param file                 a file to switch the file editor tab for. The function does nothing if the file is not currently open in the editor.
   * @param fileEditorProviderId the ID of the file editor to open; matches the return value of [FileEditorProvider.getEditorTypeId]
   */
  abstract fun setSelectedEditor(file: VirtualFile, fileEditorProviderId: String)

  /**
   * [FileEditorManager] supports asynchronous opening of text editors, i.e. when one of `openFile*` methods returns, returned
   * editor might not be fully initialized yet. This method allows delaying (if needed) execution of a given runnable until the editor is
   * fully loaded.
   */
  abstract fun runWhenLoaded(editor: Editor, runnable: Runnable)

  /**
   * Refreshes the text, colors, and icon of the editor tabs representing the specified file.
   *
   * @param file refreshed file
   */
  open fun updateFilePresentation(file: VirtualFile) { }

  /**
   * Updates the file color of the editor tabs representing the specified file.
   *
   * @param file refreshed file
   */
  open fun updateFileColor(file: VirtualFile) { }

  /**
   * Returns whether any editor tab representing the specified file is pinned.
   * Must be called from [EDT](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).
   *
   * @param file file to check
   */
  @ApiStatus.Internal
  @RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  open fun hasPinnedEditorTab(file: VirtualFile): Boolean {
    return false
  }

  /**
   * Pins or unpins editor tabs representing the specified file.
   * Must be called from [EDT](https://docs.oracle.com/javase/tutorial/uiswing/concurrency/dispatch.html).
   *
   * @param file file to update
   * @param pinned whether the editor tab should be pinned
   */
  @ApiStatus.Internal
  @RequiresEdt(generateAssertion = false /* IJPL-115548 */)
  open fun setPinnedEditorTab(file: VirtualFile, pinned: Boolean) { }

  /**
   * Returns currently focused editor (if any). It is either `null` or the value returned by [selectedEditor].
   */
  open val focusedEditor: FileEditor?
    get() {
      val editor = selectedEditor
      return if (editor != null && UIUtil.isFocusAncestor(editor.component)) editor else null
    }
}

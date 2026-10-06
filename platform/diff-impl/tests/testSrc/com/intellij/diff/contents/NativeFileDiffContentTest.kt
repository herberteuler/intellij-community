// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.diff.contents

import com.intellij.diff.DiffContentFactory
import com.intellij.idea.TestFor
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.NativeFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.limits.FileSizeLimit
import com.intellij.testFramework.BinaryLightVirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.util.LineSeparator
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.io.path.writeBytes

@TestApplication
@TestFor(issues = ["IJPL-106095"])
internal class NativeFileDiffContentTest {
  private val tempDirectory: TestFixture<Path> = tempPathFixture()
  private val factory: DiffContentFactory get() = DiffContentFactory.getInstance()

  @Test
  fun emptyNativeFilesHaveAReadOnlyFileDocument() {
    checkNativeText("")
  }

  @Test
  fun asciiNativeFilesHaveAReadOnlyFileDocument() {
    checkNativeText("<definitions/>\n")
  }

  @Test
  fun utf8NativeFilesHaveAReadOnlyFileDocument() {
    checkNativeText("[Setup]\nAppName=Пример\n")
  }

  private fun checkNativeText(text: String) {
    val bytes = text.toByteArray()
    val file = nativeFile(bytes)
    val content = assertInstanceOf(DocumentContent::class.java, factory.create(null, file))

    assertEquals(text, content.document.text)
    assertFalse(content.document.isWritable)
    assertSame(file, assertInstanceOf(FileContent::class.java, content).file)
    assertSame(file, content.highlightFile)
    assertSame(NativeFileType.INSTANCE, content.contentType)
    assertNull(FileDocumentManager.getInstance().getCachedDocument(file))
    assertArrayEquals(bytes, file.contentsToByteArray())

    assertSame(file, factory.createFile(null, file)!!.file)
    assertEquals(text, factory.createDocument(null, file)!!.document.text)
  }

  @Test
  fun utf8NativeFilesPreserveTheBomAndLineSeparators() {
    checkNativeEncoding("UTF-8")
  }

  @Test
  fun utf16leNativeFilesPreserveTheBomAndLineSeparators() {
    checkNativeEncoding("UTF-16LE")
  }

  @Test
  fun utf16beNativeFilesPreserveTheBomAndLineSeparators() {
    checkNativeEncoding("UTF-16BE")
  }

  private fun checkNativeEncoding(encoding: String) {
    val charset = Charset.forName(encoding)
    val bom = when (encoding) {
      "UTF-8" -> byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
      "UTF-16LE" -> byteArrayOf(0xff.toByte(), 0xfe.toByte())
      "UTF-16BE" -> byteArrayOf(0xfe.toByte(), 0xff.toByte())
      else -> error(encoding)
    }
    val text = "<definitions name=\"Пример\"/>\r\n"
    val file = nativeFile(bom + text.toByteArray(charset))
    val content = assertInstanceOf(DocumentContent::class.java, factory.create(null, file))

    assertEquals(text.replace("\r\n", "\n"), content.document.text)
    assertEquals(charset, content.charset)
    assertEquals(true, content.hasBom())
    assertEquals(LineSeparator.CRLF, content.lineSeparator)
  }

  @Test
  fun binaryNativeFilesHaveNoDocument() {
    val bytes = byteArrayOf(0, 1, 2, 3)
    val file = nativeFile(bytes)
    val content = factory.create(null, file)

    assertFalse(content is DocumentContent)
    assertSame(file, assertInstanceOf(FileContent::class.java, content).file)
    assertNull(factory.createDocument(null, file))
    assertFalse(factory.createFromBytes(null, bytes, NativeFileType.INSTANCE, file.name) is DocumentContent)
  }

  @Test
  fun emptyNativeRevisionsHaveADocument() {
    val content = factory.createFromBytes(null, byteArrayOf(), NativeFileType.INSTANCE, "workflow.bpmn")

    assertEquals("", assertInstanceOf(DocumentContent::class.java, content).document.text)
  }

  @Test
  fun explicitBinaryContentStaysBinary() {
    val content = factory.createBinary(null, "<definitions/>".toByteArray(), NativeFileType.INSTANCE, "workflow.bpmn")

    assertFalse(content is DocumentContent)
  }

  @Test
  fun nativeFilesPreserveTheHighlightFile() {
    val file = nativeFile("<definitions/>".toByteArray())
    val highlightFile = LightVirtualFile("highlight.txt", "")
    val content = assertInstanceOf(DocumentContent::class.java, factory.create(null, file, highlightFile))

    assertSame(file, assertInstanceOf(FileContent::class.java, content).file)
    assertSame(highlightFile, content.highlightFile)
  }

  @Test
  fun oversizedNativeFilesDoNotLoadTheirBytes() {
    val file = object : BinaryLightVirtualFile("workflow.bpmn", NativeFileType.INSTANCE, byteArrayOf()) {
      override fun getLength(): Long = FileSizeLimit.getContentLoadLimit(extension).toLong() + 1

      override fun contentsToByteArray(): ByteArray = error("The file exceeds the content loading limit")
    }

    assertFalse(factory.create(null, file) is DocumentContent)
  }

  @Test
  fun localFilesWithAnExternalAssociationHaveADocument(): Unit = timeoutRunBlocking {
    val extension = "ijpl106095"
    val fileTypeManager = FileTypeManager.getInstance()
    val originalType = fileTypeManager.getFileTypeByExtension(extension)
    edtWriteAction {
      fileTypeManager.associateExtension(NativeFileType.INSTANCE, extension)
    }
    try {
      val path = tempDirectory.get().resolve("workflow.$extension")
      val bytes = "<definitions/>\n".toByteArray()
      path.writeBytes(bytes)
      val file = checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path))
      assertSame(NativeFileType.INSTANCE, file.fileType)
      assertNull(readAction { FileDocumentManager.getInstance().getDocument(file) })

      val content = assertInstanceOf(DocumentContent::class.java, factory.create(null, file))

      assertEquals("<definitions/>\n", content.document.text)
      assertSame(file, assertInstanceOf(FileContent::class.java, content).file)
      assertFalse(content.document.isWritable)
      assertArrayEquals(bytes, file.contentsToByteArray())
    }
    finally {
      edtWriteAction {
        fileTypeManager.removeAssociatedExtension(NativeFileType.INSTANCE, extension)
        if (originalType != UnknownFileType.INSTANCE) {
          fileTypeManager.associateExtension(originalType, extension)
        }
      }
    }
  }

  private fun nativeFile(bytes: ByteArray): BinaryLightVirtualFile =
    BinaryLightVirtualFile("workflow.bpmn", NativeFileType.INSTANCE, bytes)
}

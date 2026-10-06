// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.execution.testframework.sm

import com.intellij.execution.testframework.sm.runner.cutLineIfTooLong
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage
import jetbrains.buildServer.messages.serviceMessages.ServiceMessageTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LongLineCutterTest {
  private fun createMessage(attrs: Map<String, String>) = createMessage("myMessage", attrs)

  private fun createMessage(messageName: String, attrs: Map<String, String>) = ServiceMessage.asString(messageName, attrs)

  private fun parseMessage(text: String): ServiceMessage? = ServiceMessageUtil.parse(text, false)

  @Test
  fun shortMessageUntouched() {
    val message = createMessage(mapOf(
      "A" to "B",
      "Z" to "Q"
    ))
    assertEquals(message, cutLineIfTooLong(message, Int.MAX_VALUE, 100))
  }

  @Test
  fun longLineShortened() {
    val maxLength = 10000
    val text = cutLineIfTooLong("abcde".repeat(maxLength), maxLength, 100)
    assertEquals(text.length, maxLength)
  }

  @Test
  fun actualExpectedShort() {
    val maxLength = 1000
    val message = createMessage(mapOf(
      "expected" to "A".repeat(maxLength * 2),
      "actual" to "B"
    ))
    val result = parseMessage(cutLineIfTooLong(message, maxLength, 10))!!

    val actual = result.attributes["actual"]!!
    val expected = result.attributes["expected"]!!

    assertEquals(actual, "B")
    assertTrue(expected.startsWith("A"))
    assertTrue(expected.endsWith("A"))
    assertTrue("..." in expected)
  }

  @Test
  fun actualExpectedLong() {
    val maxLength = 1000
    val message = createMessage(mapOf(
      "expected" to "A".repeat(maxLength * 2),
      "actual" to "B".repeat(maxLength * 2)
    ))
    val result = parseMessage(cutLineIfTooLong(message, maxLength, 10))!!

    val actual = result.attributes["actual"]!!
    val expected = result.attributes["expected"]!!

    assertTrue(actual.startsWith("B"))
    assertTrue(actual.endsWith("B"))
    assertTrue(expected.startsWith("A"))
    assertTrue(expected.endsWith("A"))
    assertTrue(expected.length == actual.length)
    assertTrue("..." in actual)
    assertTrue("..." in expected)
  }

  @Test
  fun longMessageShortened() {
    val maxLength = 10000
    val s = "abc\r\n"
    val longString = s.repeat(maxLength * 2)
    val message = createMessage(mapOf(
      "A" to "B",
      "C" to "D",
      "Z" to longString
    ))
    val result = cutLineIfTooLong(message, maxLength, 100)
    assertTrue(result.length <= maxLength, "Failed to cut message")
    val shortenedMessage = parseMessage(result)!!
    assertEquals("B", shortenedMessage.attributes["A"])
    assertEquals("D", shortenedMessage.attributes["C"])
    val longestValue = shortenedMessage.attributes["Z"]!!
    assertTrue(longestValue.startsWith(s) && longestValue.endsWith(s), "D")
  }

  @Test
  fun attributesAreNotValidated() {
    val maxLength = 199
    val message = createMessage(ServiceMessageTypes.TEST_FAILED, mapOf(
      "details" to "Q".repeat(maxLength * 3),
    ))
    val margin = 49
    val result = cutLineIfTooLong(message, maxLength, margin)
    assertTrue(result.length <= maxLength)
    val shortenedMessage = parseMessage(result)!!
    val details = shortenedMessage.attributes["details"]!!
    assertEquals("Q".repeat(margin) + "<...>" + "Q".repeat(margin), details)
  }
}
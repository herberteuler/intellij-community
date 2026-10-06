// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.discoverability

import com.intellij.testFramework.junit5.TestApplication
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.net.InetAddress

@TestApplication
class DiscoveryServiceSchemaTest {
  @Test
  fun `test discovery info JSON matches schema`() {
    val schemaStream = javaClass.classLoader.getResourceAsStream("com/intellij/platform/discoverability/ide-instance-schema.json")
                       ?: error("Schema resource not found on classpath")
    val mapper = ObjectMapper()
    val schemaNode = schemaStream.use { mapper.readTree(it) }

    // Remove "format" keywords to avoid runtime dependency on com.ethlo.time:itu.
    removeFormatKeywords(schemaNode)

    val schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
      .getSchema(mapper.writeValueAsString(schemaNode))

    val out = ByteArrayOutputStream()
    runBlocking {
      writeDiscoveryInfoJson(out, InetAddress.getLoopbackAddress(), 63342)
    }

    val errors = schema.validate(out.toString(Charsets.UTF_8.name()), InputFormat.JSON)
    assertTrue(errors.isEmpty(), "JSON schema validation errors:\n${errors.joinToString("\n") { it.message }}")
  }

  private fun removeFormatKeywords(node: JsonNode) {
    if (node is ObjectNode) {
      node.remove("format")
      node.properties().forEach { (_, value) -> removeFormatKeywords(value) }
    }
    else if (node.isArray) {
      node.forEach { removeFormatKeywords(it) }
    }
  }
}

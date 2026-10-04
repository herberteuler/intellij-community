package com.jetbrains.lsp.test

import com.jetbrains.lsp.protocol.NotificationType
import com.jetbrains.lsp.protocol.RequestType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.nonNullOriginal
import kotlin.test.fail

/**
 * The build-time rule behind `LSP.decodeJson`: every nullable member of a class is optional (a `null` default), so a
 * missing member decodes as `null` with `explicitNulls = true` too. A member that breaks it costs a second decode pass
 * (`decodeOrPlain` retries with `LSP.json` after the `MissingFieldException`).
 *
 * For Fleet plugin modules with their own protocol types: call [assertNullableMembersDefaultToNull] with their request
 * and notification types, plus the variants of their content-polymorphic unions as [extraRoots] (a union descriptor has
 * no elements, so the walk stops there).
 */
object NullableDefaults {
  /** One member that breaks the rule, as `serialName.member`, and the path from a root to it. */
  data class Violation(val member: String, val path: String)

  /** The roots of a set of protocol types: params, result and error serializers. */
  fun rootsOf(requests: Iterable<RequestType<*, *, *>>, notifications: Iterable<NotificationType<*>>): List<Pair<String, SerialDescriptor>> =
    requests.flatMap { type ->
      listOf("${type.method} params" to type.paramsSerializer.descriptor,
             "${type.method} result" to type.resultSerializer.descriptor,
             "${type.method} error" to type.errorSerializer.descriptor)
    } + notifications.map { "${it.method} params" to it.paramsSerializer.descriptor }

  /**
   * The nullable members with no default under [roots], walked recursively over class members, list and map elements,
   * inline values and the elements of sealed descriptors, through `.nullable`. One violation per member, sorted.
   */
  @OptIn(ExperimentalSerializationApi::class)
  fun violations(roots: Iterable<Pair<String, SerialDescriptor>>): List<Violation> {
    val found = LinkedHashMap<String, Violation>()
    walk(roots) { descriptor, index, path ->
      if (descriptor.kind == StructureKind.CLASS && descriptor.getElementDescriptor(index).isNullable && !descriptor.isElementOptional(index)) {
        val member = "${descriptor.serialName}.${descriptor.getElementName(index)}"
        found.getOrPut(member) { Violation(member, path) }
      }
    }
    return found.values.sortedBy { it.member }
  }

  /** The serial names of the classes the walk of [violations] reaches under [roots], to check its coverage. */
  fun classesUnder(roots: Iterable<Pair<String, SerialDescriptor>>): Set<String> {
    val classes = HashSet<String>()
    walk(roots) { descriptor, _, _ -> if (descriptor.kind == StructureKind.CLASS) classes.add(descriptor.serialName) }
    return classes
  }

  @OptIn(ExperimentalSerializationApi::class)
  private fun walk(roots: Iterable<Pair<String, SerialDescriptor>>, onElement: (SerialDescriptor, Int, String) -> Unit) {
    val visited = HashSet<SerialDescriptor>()

    fun walk(descriptor: SerialDescriptor, path: String) {
      val original = descriptor.nonNullOriginal
      if (!visited.add(original)) return
      for (index in 0 until original.elementsCount) {
        val elementPath = "$path > ${original.getElementName(index)}"
        onElement(original, index, elementPath)
        walk(original.getElementDescriptor(index), elementPath)
      }
    }

    for ((name, root) in roots) walk(root, name)
  }

  /** Fails with every violation under the given types, except the members in [allowed] (`serialName.member`). */
  fun assertNullableMembersDefaultToNull(
    requests: Iterable<RequestType<*, *, *>>,
    notifications: Iterable<NotificationType<*>>,
    extraRoots: Iterable<SerialDescriptor> = emptyList(),
    allowed: Set<String> = emptySet(),
  ) {
    val roots = rootsOf(requests, notifications) + extraRoots.map { it.serialName to it }
    val violations = violations(roots).filter { it.member !in allowed }
    if (violations.isNotEmpty()) {
      fail("Nullable members with no `= null` default (LSP.decodeJson needs one):\n" +
           violations.joinToString("\n") { "  ${it.member}  (${it.path})" })
    }
  }
}

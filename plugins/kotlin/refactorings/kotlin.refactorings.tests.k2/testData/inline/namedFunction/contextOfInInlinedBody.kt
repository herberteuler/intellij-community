// COMPILER_ARGUMENTS: -Xcontext-parameters
// LANGUAGE_VERSION: 2.2

package pack

context(_: String)
fun contextValu<caret>e(): String = contextOf<String>()

fun usage() {
  context("foo") {
    contextValue()
    val a = 1
  }
}

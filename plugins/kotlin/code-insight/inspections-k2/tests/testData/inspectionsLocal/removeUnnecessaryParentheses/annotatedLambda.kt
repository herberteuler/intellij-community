// PROBLEM: none

fun main() {
    foo()
    <caret>(@Suppress("UNUSED_EXPRESSION") { foo() })
}

fun foo() {}

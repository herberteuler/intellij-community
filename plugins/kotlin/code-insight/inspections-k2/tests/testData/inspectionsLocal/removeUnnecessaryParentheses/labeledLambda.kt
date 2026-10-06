// PROBLEM: none

fun main() {
    foo()
    <caret>(bar@{ foo() })
}

fun foo() {}

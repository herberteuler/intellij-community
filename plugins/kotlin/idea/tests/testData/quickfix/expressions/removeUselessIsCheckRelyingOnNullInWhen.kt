// "Remove redundant 'is' check" "false"
// ERROR: IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL_ERROR
// K2_AFTER_ERROR: IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL_ERROR
class A
class B

fun test(a: A?) = when (a) {
    <caret>is B? -> "only null"
    else -> "other"
}

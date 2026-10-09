// "Replace 'is' check with null check" "false"
// ENABLE_WARNINGS
// WARNING: IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL_WARNING
// AFTER_WARNING: IMPOSSIBLE_IS_CHECK_RELYING_ON_NULL_WARNING
class P
class Q {
    fun qMember() = 1
}

fun test(obj: Any?) {
    if (obj is P?) {
        if (<caret>obj is Q?) {
            obj?.qMember()
        }
    }
}

fun contextOf(block: () -> Unit): Int {
    block()
    return 42
}

fun doSideEff<caret>ects(): Int {
    return contextOf { println("side effect") }
}

fun test() {
    doSideEffects()
}

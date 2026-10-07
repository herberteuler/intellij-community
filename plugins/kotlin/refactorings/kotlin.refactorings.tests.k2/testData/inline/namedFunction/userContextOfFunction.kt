fun contextOf(): Int {
    println("side effect")
    return 42
}

fun doSideEff<caret>ects(): Int {
    return contextOf()
}

fun test() {
    doSideEffects()
}

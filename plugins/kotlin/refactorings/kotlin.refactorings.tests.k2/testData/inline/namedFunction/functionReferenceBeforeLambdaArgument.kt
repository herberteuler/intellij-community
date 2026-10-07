fun main() {
    higherOrder(::other, { println("block") })
}

fun higherOrder(otherLambda: () -> Unit, block: () -> Unit) {
    otherLambda()
    block()
}

fun <caret>other() {
    println("other")
}

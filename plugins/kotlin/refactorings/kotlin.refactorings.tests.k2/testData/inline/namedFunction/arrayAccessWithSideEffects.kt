class A {
    operator fun get(i: Int): A {
        println(i)
        return this
    }

    fun doSideEff<caret>ects(): A {
        return this[42]
    }
}

fun test() {
    val a = A()
    a.doSideEffects()
}

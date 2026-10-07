class A {
    val items = intArrayOf(1, 2, 3)
    val list = listOf(1, 2, 3)

    fun firstIt<caret>ems(): Int {
        return items[0] + list[1]
    }
}

fun test() {
    val a = A()
    a.firstItems()
}

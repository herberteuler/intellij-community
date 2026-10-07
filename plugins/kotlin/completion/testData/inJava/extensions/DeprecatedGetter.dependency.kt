package a

class Target

@get:Deprecated("use another getter")
var Target.value: Int
    get() = 0
    set(value) {}

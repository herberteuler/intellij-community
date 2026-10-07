package a

class PropertyTarget

@Deprecated("use size")
val PropertyTarget.oldSize: Int get() = 0

val PropertyTarget.size: Int get() = 0

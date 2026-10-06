package foo

open class A

@Deprecated(
    "Use A instead",
    ReplaceWith(
        expression = "A",
        "foo.A"
    )
)
open class B : A()

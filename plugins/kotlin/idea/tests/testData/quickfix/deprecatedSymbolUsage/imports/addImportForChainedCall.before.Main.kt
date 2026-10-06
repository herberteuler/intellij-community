// "Replace with 'this.testFunB().testFun(test3.TestObject)'" "true"
package test3

import test2.B

fun main() {
    B().<caret>b("hi")
}

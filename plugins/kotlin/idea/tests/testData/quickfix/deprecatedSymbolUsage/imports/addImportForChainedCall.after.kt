// "Replace with 'this.testFunB().testFun(test3.TestObject)'" "true"
package test3

import test1.testFun
import test2.B
import test3.TestObject

fun main() {
    B().testFunB().<selection><caret></selection>testFun(TestObject)
}

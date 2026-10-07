import com.intellij.openapi.Disposable

fun foo() {
  class MyLocal : BaseListenerInterface, Disposable

  class MyLocalOuter {
    <error>class MyNestedInLocal</error> : BaseListenerInterface, Disposable
  }
}
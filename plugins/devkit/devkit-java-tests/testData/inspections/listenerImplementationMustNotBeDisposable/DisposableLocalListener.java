import com.intellij.openapi.Disposable;

class MyClass {
  void foo() {
    class MyLocal implements BaseListenerInterface, Disposable { }

    class MyLocalOuter {
      static class MyNestedInLocal implements BaseListenerInterface, Disposable { }
    }

    new Object() {
      static class MyNestedInAnonymous implements BaseListenerInterface, Disposable { }
    };
  }
}
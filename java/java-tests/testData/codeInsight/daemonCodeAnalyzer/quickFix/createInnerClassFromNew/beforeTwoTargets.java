// "Create inner class 'Foo'" "true-preview"
public class Test {
  class Inner {
    void f() {
      new <caret>Foo(1);
    }
  }
}

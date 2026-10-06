// "Create inner class 'Foo'" "true-preview"
public class Test {
  class Inner {
    void f() {
      new Foo(1);
    }

      private class Foo {
          public Foo(int i) {<caret>
          }
      }
  }
}

// "Create inner class 'Foo'" "true-preview"
public class Test {
  class Inner {
    Foo f;

      private class Foo {
      }
  }
}

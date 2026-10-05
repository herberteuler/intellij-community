class Postmonition {
  private static int <warning descr="Private field 'value' is assigned but never accessed">value</warning> = 1;
  public static void main(String[] args) {
    value++;
    value += 1;
  }
}
class Scratch {
  private int value;

  public static void main() {
    final Scratch scratch = new Scratch();
    System.out.println(switch (123) {
      case 123 -> ++scratch.value;
      default -> throw new IllegalStateException();
    });
  }
}
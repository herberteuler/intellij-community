package a;

public class Testing {
    public static void test() {
        Target target = new Target();
        target.<caret>
    }
}

// WITH_ORDER
// EXIST: {"lookupString":"getValue","attributes":""}
// EXIST: {"lookupString":"setValue","attributes":"strikeout"}

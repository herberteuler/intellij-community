package a;

public class Testing {
    public static void test() {
        PropertyTarget target = new PropertyTarget();
        target.get<caret>
    }
}

// WITH_ORDER
// EXIST: {"lookupString":"getSize","attributes":""}
// EXIST: {"lookupString":"getOldSize","attributes":"strikeout"}

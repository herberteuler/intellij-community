class Test {
    String test(Object[] args) {
        if (args != null) {
            return "";
        } else {
            <selection>throw fail("No headers defined", null);</selection>
        }
    }

    RuntimeException fail(String message, Object[] args) {
        return new RuntimeException(message);
    }
}

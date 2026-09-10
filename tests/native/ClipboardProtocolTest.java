public final class ClipboardProtocolTest {
    private static native int runTests();

    public static void main(String[] args) {
        System.load(args[0] + "/libwinpr3.so");
        System.load(args[0] + "/libclipboard_test.so");
        if (runTests() != 0) throw new AssertionError("Native clipboard tests failed");
    }
}

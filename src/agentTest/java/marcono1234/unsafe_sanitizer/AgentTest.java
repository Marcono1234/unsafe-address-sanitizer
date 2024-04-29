package marcono1234.unsafe_sanitizer;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentTest {
    public static final Unsafe unsafe;
    static {
        try {
            var field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed getting Unsafe", e);
        }
    }

    @Test
    void success() {
        long a = unsafe.allocateMemory(10);
        unsafe.putInt(a + 6, 1);
        assertEquals(1, unsafe.getInt(a + 6));
        unsafe.freeMemory(a);
    }

    @Test
    void error() {
        var error = assertThrows(Error.class, () -> unsafe.freeMemory(-1));
        assertEquals("Cannot free at address -1", error.getMessage());

        long a = unsafe.allocateMemory(3);
        error = assertThrows(Error.class, () -> unsafe.putInt(a, 1));
        assertEquals("Size 4 exceeds actual size 3 at " + a, error.getMessage());
        unsafe.freeMemory(a);
    }

    /**
     * Verifies that the JAR with dependencies was properly packaged and includes the right module descriptors.
     */
    @Disabled("agent JAR is not loaded as module; https://bugs.openjdk.org/browse/JDK-6932391?")
    @Test
    void moduleTest() throws Exception {
        Module agentImplModule = Class.forName("marcono1234.unsafe_sanitizer.agent_impl.UnsafeSanitizerImpl").getModule();
        assertEquals("marcono1234.unsafe_sanitizer.agent_impl", agentImplModule.getName());

        Module agentModule = Class.forName("marcono1234.unsafe_sanitizer.UnsafeSanitizer").getModule();
        assertEquals("marcono1234.unsafe_sanitizer", agentModule.getName());
    }
}
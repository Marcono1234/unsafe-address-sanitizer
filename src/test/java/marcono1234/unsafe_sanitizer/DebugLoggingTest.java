package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import marcono1234.unsafe_sanitizer.agent_impl.DirectByteBufferHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static marcono1234.unsafe_sanitizer.MemoryHelper.allocateMemory;
import static marcono1234.unsafe_sanitizer.MemoryHelper.freeMemory;
import static marcono1234.unsafe_sanitizer.TestSupport.assertBadMemoryAccess;
import static marcono1234.unsafe_sanitizer.UnsafeAccess.unsafe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugLoggingTest {
    @BeforeAll
    static void installAgent() {
        UnsafeSanitizer.installAgent(AgentSettings.defaultSettings());
        UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.THROW);
        // This mainly prevents spurious errors in case any other test failed to free memory
        TestSupport.checkAllNativeMemoryFreedAndForget();

        UnsafeSanitizer.modifySettings().setCallDebugLogging(true);
    }

    @AfterAll
    static void resetDebugLogging() {
        UnsafeSanitizer.modifySettings().setCallDebugLogging(false);
    }

    @AfterEach
    void checkNoError() {
        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected error", lastError);
        }
        TestSupport.checkAllNativeMemoryFreedAndForget();
    }

    @AfterEach
    void resetErrorAction() {
        UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.THROW);
    }

    private static void assertDebugLog(Runnable runnable, String expectedOutput) {
        assertDebugLog(runnable, () -> expectedOutput);
    }

    private static void assertDebugLog(Runnable runnable, Supplier<String> expectedOutput) {
        assertDebugLogImpl(runnable, output -> assertEquals(expectedOutput.get(), output));
    }

    private static void assertDebugLog(Runnable runnable, Pattern expectedOutput) {
        //noinspection CodeBlock2Expr
        assertDebugLogImpl(runnable, output -> {
            assertTrue(expectedOutput.matcher(output).matches(), "Unexpected output: " + output);
        });
    }

    private static void assertDebugLogImpl(Runnable runnable, Consumer<String> assertion) {
        Objects.requireNonNull(runnable);

        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected previous error", lastError);
        }

        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        PrintStream tempSystemOut = new PrintStream(capturedOut, true, StandardCharsets.UTF_8);
        PrintStream oldSystemOut = System.out;

        try {
            System.setOut(tempSystemOut);
            runnable.run();
        } finally {
            System.setOut(oldSystemOut);
        }

        tempSystemOut.flush();
        String systemOutOutput = capturedOut.toString(StandardCharsets.UTF_8);

        lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected error", lastError);
        }

        assertion.accept(systemOutOutput.trim());
    }

    @Test
    void allocate_success() {
        AtomicLong a = new AtomicLong();
        assertDebugLog(
            () -> a.set(allocateMemory(10)),
            () -> "[DEBUG] Unsafe.allocateMemory(10) = " + a.get()
        );
        freeMemory(a.get());
    }

    @Test
    void allocate_thrown_error() {
        UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.THROW);
        assertDebugLog(
            () -> assertBadMemoryAccess(() -> unsafe.allocateMemory(-1)),
            "[DEBUG] Unsafe.allocateMemory(-1) = <error: BadMemoryAccessError: Invalid bytes count: -1>"
        );
    }

    @Test
    void allocate_unsafe_exception() {
        UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.NONE);
        assertDebugLog(
            () -> {
                try {
                    unsafe.allocateMemory(-1);
                } catch (RuntimeException t) {
                    var lastError = UnsafeSanitizer.getAndClearLastError();
                    if (lastError == null) {
                        throw new AssertionError("No memory error occurred, but other exception was thrown", t);
                    }
                    // Else exception is expected
                }
            },
            // Don't check exact error message because it is an `Unsafe` implementation detail
            Pattern.compile("\\[DEBUG\\] Unsafe\\.allocateMemory\\(-1\\) = <error: .+>")
        );
    }

    @Test
    void allocate_skipped() {
        UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.PRINT_SKIP);

        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        PrintStream tempSystemErr = new PrintStream(capturedErr, true, StandardCharsets.UTF_8);
        PrintStream oldSystemErr = System.err;

        AtomicReference<Error> error = new AtomicReference<>();

        try {
            System.setErr(tempSystemErr);
            assertDebugLog(
                () -> {
                    unsafe.allocateMemory(-1);
                    var lastError = UnsafeSanitizer.getAndClearLastError();
                    if (lastError == null) {
                        throw new AssertionError("No memory error occurred");
                    }
                    assertEquals("Invalid bytes count: -1", lastError.getMessage());
                    error.set(lastError);
                },
                "[DEBUG] Unsafe.allocateMemory(-1) = 0"
            );
        } finally {
            System.setErr(oldSystemErr);
        }

        tempSystemErr.flush();
        String systemErrOutput = capturedErr.toString(StandardCharsets.UTF_8);
        assertTrue(systemErrOutput.contains(error.get().toString()), "Unexpected System.err output: " + systemErrOutput);
    }

    @Test
    void directByteBuffer_allocate() {
        AtomicReference<ByteBuffer> buffer = new AtomicReference<>();
        assertDebugLog(
            () -> buffer.set(ByteBuffer.allocateDirect(10)),
            () -> "[DEBUG] ByteBuffer.allocateDirect(10) = DirectByteBuffer[address=" + DirectByteBufferHelper.getAddress(buffer.get()) + "]"
        );
        // Clean up buffer to not affect other tests, in case garbage collection does not directly collect it
        unsafe.invokeCleaner(buffer.get());
    }

    @Test
    void directByteBuffer_free() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(10);
        long address = DirectByteBufferHelper.getAddress(buffer);
        assertDebugLog(
            () -> unsafe.invokeCleaner(buffer),
            "[DEBUG] DirectByteBuffer$Deallocator[address=" + address + "].run()"
        );
        // Make sure buffer is not freed automatically before or within `assertDebugLog`
        Reference.reachabilityFence(buffer.get());
    }

    @Test
    void putObject_Null() {
        String[] a = new String[1];
        long offset = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        assertDebugLog(
            () -> unsafe.putObject(a, offset, null),
            "[DEBUG] Unsafe.putObject(java.lang.String[length=1], " + offset + ", null)"
        );
    }

    @Test
    void putObject_NestedArray() {
        int[][][] a = new int[1][][];
        long offset = Unsafe.ARRAY_OBJECT_BASE_OFFSET;
        assertDebugLog(
            () -> unsafe.putObject(a, offset, new int[0][]),
            "[DEBUG] Unsafe.putObject(int[length=1][][], " + offset + ", int[length=0][])"
        );
    }

    @Test
    void getObject_String() throws Exception {
        class Dummy {
            final String s = "abc\u001Fd'\"\\e\u007F";

            @Override
            public String toString() {
                return "Dummy";
            }
        }

        long offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("s"));
        assertDebugLog(
            () -> unsafe.getObject(new Dummy(), offset),
            "[DEBUG] Unsafe.getObject(Dummy, " + offset + ") = \"abc\\u001fd\\'\\\"\\\\e\\u007f\""
        );
    }

    @Test
    void putObject_String() throws Exception {
        class Dummy {
            String s;

            @Override
            public String toString() {
                return "Dummy";
            }
        }

        long offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("s"));
        assertDebugLog(
            () -> unsafe.putObject(new Dummy(), offset, "abc\u001Fd'\"\\e\u007F"),
            "[DEBUG] Unsafe.putObject(Dummy, " + offset + ", \"abc\\u001fd\\'\\\"\\\\e\\u007f\")"
        );
    }

    @Test
    void getInt_Array() {
        int[] a = new int[10];
        long offset = Unsafe.ARRAY_INT_BASE_OFFSET;
        assertDebugLog(
            () -> unsafe.getInt(a, offset),
            "[DEBUG] Unsafe.getInt(int[length=10], " + offset + ") = 0"
        );
    }
}

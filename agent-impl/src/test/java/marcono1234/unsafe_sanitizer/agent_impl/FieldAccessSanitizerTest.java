package marcono1234.unsafe_sanitizer.agent_impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.lang.reflect.Field;

import static marcono1234.unsafe_sanitizer.agent_impl.UnsafeAccess.unsafe;
import static org.junit.jupiter.api.Assertions.fail;

class FieldAccessSanitizerTest {
    private FieldAccessSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        UnsafeSanitizerImpl.setErrorAction(AgentErrorAction.THROW);
        sanitizer = new FieldAccessSanitizer();
    }

    @AfterEach
    void clearLastError() {
        var lastError = UnsafeSanitizerImpl.getLastErrorRefImpl().getAndSet(null);
        if (lastError != null) {
            fail("Unexpected error", lastError);
        }
    }

    // TODO: Check exception messages?
    private static void assertThrows(Executable executable) {
        Assertions.assertThrows(BadMemoryAccessError.class, executable);
        UnsafeSanitizerImpl.getLastErrorRefImpl().set(null);
    }

    @Test
    void instanceField() throws Exception {
        class Dummy {
            byte b;
            int i;
            Object o;
        }
        Dummy dummy = new Dummy();

        long offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("b"));
        sanitizer.checkAccess(dummy, offset, MemorySize.BYTE_1);

        offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("i"));
        sanitizer.checkAccess(dummy, offset, MemorySize.BYTE_4);

        offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("o"));
        sanitizer.checkAccess(dummy, offset, MemorySize.OBJECT);
    }

    @Test
    void instanceFieldInheritance() throws Exception {
        class Super {
            int i;
        }
        class Sub extends Super {
        }

        long offset = unsafe.objectFieldOffset(Super.class.getDeclaredField("i"));
        sanitizer.checkAccess(new Sub(), offset, MemorySize.BYTE_4);
    }

    @Test
    void instanceFieldError() throws Exception {
        class Dummy {
            int i;
            long l;
            Object o;
        }
        Dummy dummy = new Dummy();

        {
            long offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("i"));
            assertThrows(() -> sanitizer.checkAccess(dummy, offset + 1, MemorySize.BYTE_4));
            // Trying to access with misaligned offset; `Unsafe` doc says that offset should not be used for
            // arithmetic operations
            assertThrows(() -> sanitizer.checkAccess(dummy, offset + 1, MemorySize.BYTE_1));

            // Trying to access 4 byte `int` as 8 byte
            assertThrows(() -> sanitizer.checkAccess(dummy, offset, MemorySize.BYTE_8));
            // Access of smaller size
            assertThrows(() -> sanitizer.checkAccess(dummy, offset, MemorySize.BYTE_1));
        }

        {
            long offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("l"));
            // Trying to access `long` as Object
            assertThrows(() -> sanitizer.checkAccess(dummy, offset, MemorySize.OBJECT));

            // Trying to access `long` as 'address'; at least on 32-bit platforms this could be an issue
            assertThrows(() -> sanitizer.checkAccess(dummy, offset, MemorySize.ADDRESS));
        }

        {
            long offset = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("o"));
            // Trying to access Object as bytes; might be technically possible but not sure if this is really
            // officially supported
            assertThrows(() -> sanitizer.checkAccess(dummy, offset, MemorySize.BYTE_4));
        }
    }

    @Test
    void staticField() throws Exception {
        class Dummy {
            static byte b;
            static int i;
        }

        Field field = Dummy.class.getDeclaredField("b");
        Object base = unsafe.staticFieldBase(field);
        long offset = unsafe.staticFieldOffset(field);
        sanitizer.checkAccess(base, offset, MemorySize.BYTE_1);

        field = Dummy.class.getDeclaredField("i");
        base = unsafe.staticFieldBase(field);
        offset = unsafe.staticFieldOffset(field);
        sanitizer.checkAccess(base, offset, MemorySize.BYTE_4);
    }

    @Test
    void staticFieldInterface() throws Exception {
        interface Dummy {
            int i = 1;
        }

        Field field = Dummy.class.getDeclaredField("i");
        Object base = unsafe.staticFieldBase(field);
        long offset = unsafe.staticFieldOffset(field);
        sanitizer.checkAccess(base, offset, MemorySize.BYTE_4);
    }

    @Test
    void mixedFields() throws Exception {
        class Dummy {
            int i;
            static int s;
        }
        Dummy dummy = new Dummy();

        long offsetI = unsafe.objectFieldOffset(Dummy.class.getDeclaredField("i"));

        Field fieldS = Dummy.class.getDeclaredField("s");
        Object baseS = unsafe.staticFieldBase(fieldS);
        long offsetS = unsafe.staticFieldOffset(fieldS);

        sanitizer.checkAccess(dummy, offsetI, MemorySize.BYTE_4);
        sanitizer.checkAccess(baseS, offsetS, MemorySize.BYTE_4);

        // Mixing offsets is not allowed
        assertThrows(() -> sanitizer.checkAccess(dummy, offsetS, MemorySize.BYTE_4));
        assertThrows(() -> sanitizer.checkAccess(baseS, offsetI, MemorySize.BYTE_4));
    }
}
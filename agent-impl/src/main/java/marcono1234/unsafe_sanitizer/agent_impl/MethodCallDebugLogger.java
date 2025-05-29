package marcono1234.unsafe_sanitizer.agent_impl;

import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.Objects;

import static marcono1234.unsafe_sanitizer.agent_impl.DirectByteBufferHelper.DEALLOCATOR_CLASS_NAME;

/**
 * Injected into transformed classes by the agent to log method calls.
 */
public class MethodCallDebugLogger {
    private MethodCallDebugLogger() {}

    public static volatile boolean isEnabled = false;

    private static String formatChar(char c) {
        if (c == '"' || c == '\'' || c == '\\') {
            return "\\" + c;
        }
        if (c < 0x20 || c >= 127) {
            return "\\u" + HexFormat.of().toHexDigits(c);
        } else {
            return Character.toString(c);
        }
    }

    private static String formatValue(Object obj) {
        if (obj != null && obj.getClass().isArray()) {
            int length = Array.getLength(obj);
            var innerComponentType = obj.getClass().getComponentType();
            int nestedArrayDimensions = 0;
            while (innerComponentType.isArray()) {
                nestedArrayDimensions++;
                innerComponentType = innerComponentType.getComponentType();
            }

            // To match Java array declaration syntax, print the length of the array for the first `[...]` occurrence
            return innerComponentType.getTypeName() + "[length=" + length + "]" + "[]".repeat(nestedArrayDimensions);
        } else if (obj instanceof Character c) {
            return "'" + formatChar(c) + "'";
        } else if (obj instanceof CharSequence charSequence) {
            String s = charSequence.toString();
            StringBuilder stringBuilder = new StringBuilder(s.length());
            stringBuilder.append('"');
            for (char c : s.toCharArray()) {
                stringBuilder.append(formatChar(c));
            }
            return stringBuilder.append('"').toString();
        } else {
            return Objects.toString(obj);
        }
    }

    // Note: This is split into `onMethodEnter` and `onMethodExit` (instead of only logging on exit) to make
    // troubleshooting JVM crashes either (depending on ErrorAction) by already printing which method was called
    // before the JVM crashes
    public static void onMethodEnter(
        String declaringClassName,
        String methodName,
        Object this_,
        Object[] arguments
    ) {
        StringBuilder message = new StringBuilder("[DEBUG] ");
        // Append class name without package name
        message.append(declaringClassName, declaringClassName.lastIndexOf('.') + 1, declaringClassName.length());

        if (this_ != null && this_.getClass().getName().equals(DEALLOCATOR_CLASS_NAME)) {
            long deallocatorAddress = DirectByteBufferHelper.getDeallocatorAddress((Runnable) this_);
            message.append("[address=").append(deallocatorAddress).append(']');
        }

        message.append('.').append(methodName).append('(');

        boolean isFirst = true;
        for (Object arg : arguments) {
            if (isFirst) {
                isFirst = false;
            } else {
                message.append(", ");
            }
            message.append(formatValue(arg));
        }
        message.append(')');

        // Only use `print` here instead of `println`; the remainder is appended by `onMethodExit`
        // This avoids duplicating the message in `onMethodExit`, but could cause corrupted output if a different
        // thread concurrently prints to System.out, or if System.err and System.out are merged on the console
        System.out.print(message.toString());
        // Flush output to make sure users can see it in the console, even if the JVM crashes soon afterwards
        System.out.flush();
    }

    public static void onMethodExit(
        MethodType methodType,
        Object result,
        Throwable thrown
    ) {
        // Prints the remainder for the message started in `onMethodEnter`
        String remainder;
        if (thrown != null) {
            remainder = " = <error: " + thrown.getClass().getSimpleName() + ": " + thrown.getMessage() + ">";
        } else if (methodType.returnType() != void.class) {
            remainder = " = ";

            // Special case for DirectByteBuffer to include its address in the output
            if (result instanceof ByteBuffer byteBuffer && byteBuffer.isDirect()) {
                long address = DirectByteBufferHelper.getAddress(byteBuffer);
                remainder += "DirectByteBuffer[address=" + address + "]";
            } else {
                remainder += formatValue(result);
            }
        } else {
            remainder = "";
        }
        System.out.println(remainder);
        // Flush output to make sure users can see it in the console, even if the JVM crashes soon afterwards
        System.out.flush();
    }
}

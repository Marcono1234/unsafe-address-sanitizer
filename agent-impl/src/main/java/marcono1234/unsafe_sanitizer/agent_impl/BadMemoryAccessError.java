package marcono1234.unsafe_sanitizer.agent_impl;

import java.io.PrintStream;
import java.util.Arrays;

// This is an `Error` instead of `RuntimeException` subclass to make it less likely that user code accidentally
// discards the error
public class BadMemoryAccessError extends Error {
    private BadMemoryAccessError(String message) {
        super(message);
    }

    private BadMemoryAccessError(String message, Throwable cause) {
        super(message, cause);
    }

    private static void onError(BadMemoryAccessError error) {
        if (!UnsafeSanitizerImpl.isIncludeSanitizerStackFrames()) {
            String packageName = "marcono1234.unsafe_sanitizer.agent_impl.";
            // Don't directly use `BadMemoryAccessError.class.getPackageName()` as package name because that
            // could cause issues in case that class is moved to a sub-package in the future
            assert (BadMemoryAccessError.class.getPackageName() + ".").startsWith(packageName);

            var stackTrace = error.getStackTrace();
            // Skip all frames until the last one from the sanitizer
            int endIndex = -1;
            for (int i = 0; i < stackTrace.length; i++) {
                String c = stackTrace[i].getClassName();
                if (c.startsWith(packageName)) {
                    endIndex = i;
                }
            }

            stackTrace = Arrays.copyOfRange(stackTrace, endIndex + 1, stackTrace.length);
            error.setStackTrace(stackTrace);
        }

        UnsafeSanitizerImpl.getLastErrorRefImpl().set(error);

        switch (UnsafeSanitizerImpl.getErrorAction()) {
            case THROW -> throw error;
            case PRINT, PRINT_SKIP -> {
                PrintStream stream = System.err;
                error.printStackTrace(stream);
                // Flush output to make sure users can see it in the console, even if the JVM crashes soon afterwards
                stream.flush();
            }
        }
    }

    /**
     * @return In case due to the {@link AgentErrorAction} no error is thrown, returns whether the {@code Unsafe}
     *      method should be executed or not and its execution should be skipped (e.g. to prevent a JVM crash).
     */
    static boolean reportError(String message) {
        var error = new BadMemoryAccessError(message);
        onError(error);
        return UnsafeSanitizerImpl.executeOnError();
    }

    /**
     * @return In case due to the {@link AgentErrorAction} no error is thrown, returns whether the {@code Unsafe}
     *      method should be executed or not and its execution should be skipped (e.g. to prevent a JVM crash).
     */
    static boolean reportError(IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (UnsafeSanitizerImpl.isIncludeSanitizerStackFrames()) {
            var error = new BadMemoryAccessError(message, exception);
            onError(error);
            return UnsafeSanitizerImpl.executeOnError();
        } else {
            return reportError(message);
        }
    }
}

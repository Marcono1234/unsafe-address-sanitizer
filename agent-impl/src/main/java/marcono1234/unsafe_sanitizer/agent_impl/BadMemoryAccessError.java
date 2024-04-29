package marcono1234.unsafe_sanitizer.agent_impl;

import java.io.PrintStream;

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
    static boolean reportError(Throwable exception) {
        var error = new BadMemoryAccessError(exception.getMessage(), exception);
        onError(error);
        return UnsafeSanitizerImpl.executeOnError();
    }
}

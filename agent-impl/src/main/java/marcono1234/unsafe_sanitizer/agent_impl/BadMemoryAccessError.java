package marcono1234.unsafe_sanitizer.agent_impl;

import java.io.Console;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;

// This is an `Error` instead of `RuntimeException` subclass to make it less likely that user code accidentally
// discards the error
public class BadMemoryAccessError extends Error {
    private BadMemoryAccessError(String message) {
        super(message);
    }

    private BadMemoryAccessError(String message, Throwable cause) {
        super(message, cause);
    }

    private interface OutputPrinter {
        void println(String text);
        void flush();

        void printThrowable(Throwable t);
    }
    private static class PrintStreamPrinter implements OutputPrinter {
        private final PrintStream printStream;

        public PrintStreamPrinter(PrintStream printStream) {
            this.printStream = Objects.requireNonNull(printStream);
        }

        @Override
        public void println(String text) {
            printStream.println(text);
        }

        @Override
        public void flush() {
            printStream.flush();
        }

        @Override
        public void printThrowable(Throwable t) {
            t.printStackTrace(printStream);
        }
    }
    private static class PrintWriterPrinter implements OutputPrinter {
        private final PrintWriter printWriter;

        public PrintWriterPrinter(PrintWriter printWriter) {
            this.printWriter = Objects.requireNonNull(printWriter);
        }

        @Override
        public void println(String text) {
            printWriter.println(text);
        }

        @Override
        public void flush() {
            printWriter.flush();
        }

        @Override
        public void printThrowable(Throwable t) {
            t.printStackTrace(printWriter);
        }
    }

    private static OutputPrinter getOutputPrinter() {
        /*
         * Note: Could alternatively also offer 3 options:
         *   - SYSTEM_ERR (default)
         *     advantage: always available
         *     disadvantage: can be replaced / discarded by user (but can also be an advantage, e.g. when a logging
         *       framework records the output as well)
         *   - SYSTEM_CONSOLE
         *     advantage: can detect when not available (== null) and fall back to something else
         *     disadvantage: writes to 'out' stream instead of 'err' stream
         *   - FD_ERR (FileDescriptor.err)
         *     advantage: cannot be replaced by user (but maybe also a disadvantage, depending on the use case)
         *     disadvantage: cannot reliably detect if this attached to something or whether a fallback is needed
         *       (or could check if `System.console() == null`?)
         *     Note: Have to implement `flush()` using  `FileDescriptor#sync()` (and not by `FileOutputStream#flush()`)?
         *
         * For now offering only System.err and System#console() might be enough
         */

        Console console = System.console();
        return UnsafeSanitizerImpl.isPrintErrorsToConsole() && console != null
            ? new PrintWriterPrinter(console.writer())
            // Always create new printer instead of using singleton, to account for replaced System.err
            : new PrintStreamPrinter(System.err);
    }

    /**
     * Handles the error. The behavior depends on the current {@link UnsafeSanitizerImpl#getErrorAction()}.
     *
     * @return Whether the caller should perform the invalid memory access anyway; if {@code false} the caller
     *      should skip the invalid access
     */
    private static boolean onError(BadMemoryAccessError error) {
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

        var errorAction = UnsafeSanitizerImpl.getErrorAction();
        OutputPrinter outputPrinter = getOutputPrinter();
        if (errorAction.printStackTrace) {
            outputPrinter.printThrowable(error);
            // Flush output to make sure users can see it in the console, even if the JVM crashes soon afterwards
            outputPrinter.flush();
        }

        switch (UnsafeSanitizerImpl.getErrorAction()) {
            case THROW, THROW_PRINT -> throw error;
            case TERMINATE_JVM -> {
                outputPrinter.println("### unsafe-address-sanitizer is terminating JVM ###");
                System.exit(1);
            }
        }

        return errorAction.executeOnError;
    }

    /**
     * @return In case due to the {@link AgentErrorAction} no error is thrown, returns whether the {@code Unsafe}
     *      method should be executed or not and its execution should be skipped (e.g. to prevent a JVM crash).
     */
    static boolean reportError(String message) {
        var error = new BadMemoryAccessError(message);
        return onError(error);
    }

    /**
     * @return In case due to the {@link AgentErrorAction} no error is thrown, returns whether the {@code Unsafe}
     *      method should be executed or not and its execution should be skipped (e.g. to prevent a JVM crash).
     */
    static boolean reportError(IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (UnsafeSanitizerImpl.isIncludeSanitizerStackFrames()) {
            var error = new BadMemoryAccessError(message, exception);
            return onError(error);
        } else {
            return reportError(message);
        }
    }
}

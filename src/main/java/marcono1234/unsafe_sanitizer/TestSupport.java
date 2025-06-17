package marcono1234.unsafe_sanitizer;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import marcono1234.unsafe_sanitizer.agent_impl.AgentErrorAction;
import marcono1234.unsafe_sanitizer.agent_impl.BadMemoryAccessError;
import marcono1234.unsafe_sanitizer.agent_impl.UnsafeSanitizerImpl;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Provides helper methods for tests which want to assert that a certain action performed
 * good or bad memory access.
 *
 * <p>Which types of bad memory access are detected depends on the {@link UnsafeSanitizer} configuration.
 */
public class TestSupport {
    private TestSupport() {}

    /**
     * Runnable which can throw a {@link Throwable}.
     *
     * @see #assertBadMemoryAccess(ThrowingRunnable)
     * @see #assertNoBadMemoryAccess(ThrowingRunnable)
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        /** Performs the action. */
        void run() throws Throwable;
    }

    /**
     * Supplier which can throw a {@link Throwable}.
     * @param <T> type of the result
     *
     * @see #assertNoBadMemoryAccessGet(ThrowingSupplier)
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        /** Performs the action and returns the result. */
        T get() throws Throwable;
    }

    private static void checkAgentThrowsErrors() {
        UnsafeSanitizer.checkInstalled();
        if (UnsafeSanitizerImpl.getErrorAction() != AgentErrorAction.THROW) {
            throw new IllegalStateException("Error action must be " + ErrorAction.THROW);
        }
    }

    /**
     * Helper method for unit tests which temporarily sets {@link ErrorAction#THROW}
     * before restoring the original error action.
     */
    // Only for internal usage for now
    static <T> T withThrowErrorAction(Supplier<T> s) {
        UnsafeSanitizer.checkInstalled();
        var oldErrorAction = UnsafeSanitizerImpl.getErrorAction();
        try {
            UnsafeSanitizer.modifySettings().setErrorAction(ErrorAction.THROW);
            return s.get();
        } finally {
            UnsafeSanitizerImpl.setErrorAction(oldErrorAction);
        }
    }

    /**
     * Asserts that the runnable performs a bad memory access, and returns the error.
     *
     * <p>The {@linkplain UnsafeSanitizer#getLastError() last error} will be cleared automatically.
     * The exact type and message of the returned error are implementation details.
     *
     * @throws IllegalStateException
     *      if the agent has not been installed yet
     * @throws IllegalStateException
     *      if the current {@linkplain UnsafeSanitizer.ModifySettings#setErrorAction(ErrorAction) error action} is
     *      not {@link ErrorAction#THROW}
     */
    // @CanIgnoreReturnValue is needed to avoid spurious IntelliJ warnings for callers, see https://youtrack.jetbrains.com/issue/IDEA-188863
    @CanIgnoreReturnValue
    public static Error assertBadMemoryAccess(ThrowingRunnable runnable) {
        Objects.requireNonNull(runnable);
        checkAgentThrowsErrors();

        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected previous error" , lastError);
        }

        try {
            runnable.run();
        } catch (Throwable t) {
            // Cannot directly refer to `BadMemoryAccessError` in `catch` because it seems the class is then
            // loaded eagerly, which fails because the agent has not been installed yet
            if (t instanceof BadMemoryAccessError badMemoryAccessError) {
                lastError = UnsafeSanitizer.getAndClearLastError();
                if (lastError != badMemoryAccessError) {
                    var e = new AssertionError("Last error does not match thrown error", lastError);
                    e.addSuppressed(badMemoryAccessError);
                    throw e;
                }

                return badMemoryAccessError;
            }

            lastError = UnsafeSanitizer.getAndClearLastError();
            if (lastError == null) {
                throw new AssertionError("Unexpected exception", t);
            } else {
                // TODO: Should this be allowed? For example when user code wraps the BadMemoryAccessError, or even
                //  just rethrows different exception (without wrapping)?
                var e = new AssertionError("Unexpected exception, but expected bad memory access error occurred as well", t);
                e.addSuppressed(lastError);
                throw e;
            }
        }

        lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError == null) {
            throw new AssertionError("No exception was thrown");
        } else {
            throw new AssertionError("No exception was thrown, but expected bad memory access error occurred", lastError);
        }
    }

    /**
     * Asserts that the supplier performs no bad memory access, and returns the result
     * of the supplier.
     *
     * <p>If an unexpected bad memory access occurs, an {@link AssertionError} will be thrown and the
     * {@linkplain UnsafeSanitizer#getLastError() last error} will be cleared automatically.
     *
     * @param <T>
     *      result type of the supplier
     * @throws IllegalStateException
     *      if the agent has not been installed yet
     * @throws IllegalStateException
     *      if the current {@linkplain UnsafeSanitizer.ModifySettings#setErrorAction(ErrorAction) error action} is
     *      not {@link ErrorAction#THROW}
     */
    public static <T> T assertNoBadMemoryAccessGet(ThrowingSupplier<T> supplier) {
        Objects.requireNonNull(supplier);
        checkAgentThrowsErrors();

        var lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("Unexpected previous error" , lastError);
        }

        T result;
        try {
            result = supplier.get();
        } catch (Throwable t) {
            lastError = UnsafeSanitizer.getAndClearLastError();
            if (lastError == null || lastError == t) {
                throw new AssertionError("Unexpected exception", t);
            } else {
                var e = new AssertionError("Unexpected exception, and unexpected bad memory access error occurred as well", t);
                e.addSuppressed(lastError);
                throw e;
            }
        }

        lastError = UnsafeSanitizer.getAndClearLastError();
        if (lastError != null) {
            throw new AssertionError("No exception was thrown, but unexpected bad memory access error occurred", lastError);
        }
        return result;
    }

    /**
     * Asserts that the runnable performs no bad memory access.
     *
     * <p>If an unexpected bad memory access occurs, an {@link AssertionError} will be thrown and the
     * {@linkplain UnsafeSanitizer#getLastError() last error} will be cleared automatically.
     *
     * @throws IllegalStateException
     *      if the agent has not been installed yet
     * @throws IllegalStateException
     *      if the current {@linkplain UnsafeSanitizer.ModifySettings#setErrorAction(ErrorAction) error action} is
     *      not {@link ErrorAction#THROW}
     */
    public static void assertNoBadMemoryAccess(ThrowingRunnable runnable) {
        Objects.requireNonNull(runnable);
        assertNoBadMemoryAccessGet(() -> {
            runnable.run();
            return null;
        });
    }

    // Only for internal usage for now
    static void checkAllNativeMemoryFreedAndForget() {
        UnsafeSanitizer.checkInstalled();
        UnsafeSanitizerImpl.checkAllNativeMemoryFreed(true);
    }
}

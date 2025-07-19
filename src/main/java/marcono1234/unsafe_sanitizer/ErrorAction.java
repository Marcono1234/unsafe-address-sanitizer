package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.agent_impl.AgentErrorAction;

/**
 * Action to perform in case of bad memory access. Regardless of the error action the error will always be
 * additionally stored as {@link UnsafeSanitizer#getLastError()}.
 */
public enum ErrorAction {
    /**
     * Do nothing, permit the bad memory access.
     */
    NONE,
    /**
     * Print the error stack trace to the console and terminate the JVM via {@link System#exit(int)}.
     *
     * <p>Intended for cases where the application should really terminate after invalid access has occurred,
     * unlike {@link #THROW} where the application could discard the error and continue.
     */
    TERMINATE_JVM,
    /**
     * Throw an {@link Error} on bad memory access.
     */
    THROW,
    /**
     * Like {@link #THROW}, but additionally directly print the error stack trace to the console.
     *
     * <p>Intended for cases where the application might discard the error, so additionally printing the error
     * to console at least allows the user to notice the error. Can lead to the error stack trace being printed
     * twice, the second time when the error is caught (respectively if it is not caught and the JVM terminates).
     */
    THROW_PRINT,
    /**
     * Print the error stack trace to the console, but permit the bad memory access anyway.
     *
     * <p>Intended for cases where the sanitizer is for the first time applied to an existing application to
     * detect all places where invalid access happens, without affecting execution. Because the invalid access
     * is performed anyway, this can crash the JVM or corrupt its memory.
     */
    PRINT,
    /**
     * Print the error stack trace to the console and skip the actual memory access.
     * When the {@code Unsafe} method expects a return value, a (possibly invalid) default value is returned instead.
     *
     * <p>Intended for similar use cases as {@link #PRINT}, but makes sure no invalid access is performed
     * therefore avoiding a JVM crash or memory corruption. However, skipping the invalid access and returning
     * a default value (if necessary) can affect the application behavior.
     */
    PRINT_SKIP,
    ;

    /**
     * Gets the corresponding internal error action.
     */
    AgentErrorAction getAgentErrorAction() {
        // Do this lookup dynamically (instead of for example storing values as field of this enum) to make sure
        // agent-impl class is only accessed after it has been loaded
        return switch (this) {
            case NONE -> AgentErrorAction.NONE;
            case TERMINATE_JVM -> AgentErrorAction.TERMINATE_JVM;
            case THROW -> AgentErrorAction.THROW;
            case THROW_PRINT -> AgentErrorAction.THROW_PRINT;
            case PRINT -> AgentErrorAction.PRINT;
            case PRINT_SKIP -> AgentErrorAction.PRINT_SKIP;
        };
    }
}

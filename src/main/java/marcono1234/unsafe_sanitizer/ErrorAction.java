package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.agent_impl.AgentErrorAction;

/**
 * Action to perform in case of bad memory access. Regardless of the error action the error will always be
 * additionally stored as {@link UnsafeSanitizer#getLastError()}.
 */
public enum ErrorAction {
    /**
     * Do nothing, permit bad memory access.
     */
    NONE,
    /**
     * Throw an {@link Error} on bad memory access.
     */
    THROW,
    /**
     * Print the error stack trace to the console, but permit the bad memory access anyway.
     */
    PRINT,
    /**
     * Print the error stack trace to the console and skip actual memory access.
     * When the {@code Unsafe} method expects a return value, a (possibly invalid) default value is returned instead.
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
            case THROW -> AgentErrorAction.THROW;
            case PRINT -> AgentErrorAction.PRINT;
            case PRINT_SKIP -> AgentErrorAction.PRINT_SKIP;
        };
    }
}

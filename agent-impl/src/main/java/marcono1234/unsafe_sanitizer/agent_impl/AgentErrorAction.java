package marcono1234.unsafe_sanitizer.agent_impl;

/**
 * Action to perform in case of bad memory access.
 */
public enum AgentErrorAction {
    NONE(true),
    THROW(true), // `executeOnError` does not matter since exception is thrown anyway
    PRINT(true),
    PRINT_SKIP(false),
    ;

    /** Whether to perform the bad memory access; {@code false} if it should be skipped */
    final boolean executeOnError;

    AgentErrorAction(boolean executeOnError) {
        this.executeOnError = executeOnError;
    }
}

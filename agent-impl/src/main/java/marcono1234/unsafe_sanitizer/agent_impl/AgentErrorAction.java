package marcono1234.unsafe_sanitizer.agent_impl;

/**
 * Action to perform in case of bad memory access.
 */
public enum AgentErrorAction {
    NONE(true, false),
    TERMINATE_JVM(/* irrelevant: execution stops anyway */ false, true),
    THROW(/* irrelevant: execution stops anyway */ false, false),
    THROW_PRINT(/* irrelevant: execution stops anyway */ false, true),
    PRINT(true, true),
    PRINT_SKIP(false, true),
    ;

    /** Whether to perform the bad memory access; {@code false} if it should be skipped */
    final boolean executeOnError;

    /** Whether to directly print the stack trace when the error occurs */
    final boolean printStackTrace;

    AgentErrorAction(boolean executeOnError, boolean printStackTrace) {
        this.executeOnError = executeOnError;
        this.printStackTrace = printStackTrace;
    }
}

package marcono1234.unsafe_sanitizer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.utility.JavaModule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Listener which collects errors and provides access to them through {@link #getErrors()}.
 *
 * <p>This is intended as a way to manually check for errors after agent installation because exceptions
 * are not propagated in current JDK versions, see also https://github.com/raphw/byte-buddy/issues/1815.
 */
class ErrorCollectingAgentListener extends AgentBuilder.Listener.Adapter {
    private final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onError(String typeName, ClassLoader classLoader, JavaModule module, boolean loaded, Throwable throwable) {
        errors.add(throwable);
    }

    public List<Throwable> getErrors() {
        return errors;
    }
}

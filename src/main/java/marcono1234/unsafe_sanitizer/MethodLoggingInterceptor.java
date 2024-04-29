package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.agent_impl.MethodCallDebugLogger;
import net.bytebuddy.asm.Advice.*;

import java.lang.invoke.MethodType;

import static net.bytebuddy.implementation.bytecode.assign.Assigner.Typing.DYNAMIC;

// Note: To avoid IntelliJ warnings, can mark in IntelliJ methods annotated with `@OnMethodEnter` and `@OnMethodExit`
//   as entrypoint

/**
 * Interceptor which performs debug logging of called methods.
 */
class MethodLoggingInterceptor {

    private MethodLoggingInterceptor() {
    }

    /*
     * Implementation notes:
     * - The actual logging is implemented in agent-impl because here in Advice methods cannot have
     *   separate helper methods, and cannot debug through code
     * - Uses `@Origin MethodType` and `@Origin(...)` because they can be stored as constants,
     *   whereas `@Origin Method` would always create new instance, even if debug logging is not enabled
     */

    @OnMethodEnter
    static void enter(
        @Origin("#t") String declaringTypeName,
        @Origin("#m") String methodName,
        @This(optional = true, typing = DYNAMIC) Object this_,
        @AllArguments(typing = DYNAMIC) Object[] arguments
    ) {
        MethodCallDebugLogger.onMethodEnter(declaringTypeName, methodName, this_, arguments);
    }

    @OnMethodExit(onThrowable = Throwable.class)
    static void exit(
        @Origin MethodType method,
        @Return(typing = DYNAMIC) Object result,
        @Thrown Throwable thrown
    )  {
        MethodCallDebugLogger.onMethodExit(method, result, thrown);
    }
}

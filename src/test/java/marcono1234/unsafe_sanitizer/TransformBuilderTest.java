package marcono1234.unsafe_sanitizer;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.Test;

import net.bytebuddy.asm.Advice;
import static org.junit.jupiter.api.Assertions.*;

class TransformBuilderTest {
    static class DummyAdvice {
        @Advice.OnMethodEnter
        public static void onEnter() {
            throw new AssertionError("should not be called");
        }
    }

    /**
     * Transforming should fail when the target method has JVM intrinsics.
     */
    @Test
    void jvmIntrinsic() {
        var errorListener = new ErrorCollectingAgentListener();
        AgentBuilder agentBuilder = new AgentBuilder.Default()
            .with(errorListener)
            // Necessary to transform JDK class, see comments in UnsafeSanitizer#installAgent
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .disableClassFormatChanges()
            .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."));

        // Try to transform Double#doubleToLongBits, which is annotated with @IntrinsicCandidate in current JDK versions
        agentBuilder = new TransformBuilder(Double.class)
            .addMethods("doubleToLongBits", DummyAdvice.class)
            .configure(agentBuilder);

        agentBuilder.installOn(ByteBuddyAgent.install());

        var errors = errorListener.getErrors();
        assertEquals(1, errors.size());

        var e = errors.get(0);
        assertInstanceOf(AssertionError.class, e);
        assertEquals("class java.lang.Double defines method with @IntrinsicCandidate", e.getMessage());
    }
}

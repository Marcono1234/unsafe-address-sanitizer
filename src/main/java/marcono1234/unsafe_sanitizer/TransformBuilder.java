package marcono1234.unsafe_sanitizer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static net.bytebuddy.matcher.ElementMatchers.*;

/**
 * Helper class for defining the class transformations and configuring the Byte Buddy agent.
 */
class TransformBuilder {
    /** The class that will be transformed */
    private final Class<?> classToTransform;
    private final List<ElementMatcher.Junction<? super MethodDescription>> methodMatchers;
    private final List<AsmVisitorWrapper.ForDeclaredMethods> visitors;

    public TransformBuilder(Class<?> classToTransform) {
        this.classToTransform = Objects.requireNonNull(classToTransform);
        this.methodMatchers = new ArrayList<>();
        this.visitors = new ArrayList<>();
    }

    public TransformBuilder(String typeName) {
        this(lookUpClass(typeName));
    }

    private static Class<?> lookUpClass(String name) {
        // Assume that the class can be found; throwing an exception (and failing agent installation) if it can't
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to find class: " + name, e);
        }
    }

    public Class<?> getClassToTransform() {
        return classToTransform;
    }

    /**
     * Adds the {@code interceptor} (implemented using {@link Advice}) for all matching methods.
     */
    public TransformBuilder addMethods(ElementMatcher.Junction<? super MethodDescription> matcher, Class<?> interceptor) {
        Objects.requireNonNull(matcher);
        Objects.requireNonNull(interceptor);

        methodMatchers.add(matcher);
        visitors.add(Advice.to(interceptor).on(matcher));
        return this;
    }

    /**
     * Adds the {@code interceptor} (implemented using {@link Advice}) for all methods with the given {@code name}.
     */
    public TransformBuilder addMethods(String name, Class<?> interceptor) {
        Objects.requireNonNull(name);
        return addMethods(named(name).and(isPublic()), interceptor);
    }

    /**
     * Adds the {@code interceptor} (implemented using {@link Advice}) for all methods whose name starts with {@code namePrefix}.
     */
    public TransformBuilder addMethodsWithPrefix(String namePrefix, Class<?> interceptor) {
        Objects.requireNonNull(namePrefix);
        return addMethods(nameStartsWith(namePrefix).and(isPublic()), interceptor);
    }

    /**
     * Configures the given {@code agentBuilder} with all registered interceptors, and returns the modified agent builder.
     */
    public AgentBuilder configure(AgentBuilder agentBuilder) {
        if (methodMatchers.isEmpty()) {
            throw new IllegalStateException("No matchers have been added");
        }

        var loggingMatcher = methodMatchers.get(0);
        for (int i = 1; i < methodMatchers.size(); i++) {
            loggingMatcher = loggingMatcher.or(methodMatchers.get(i));
        }
        var loggingMatcherF = loggingMatcher;

        return agentBuilder.type(is(classToTransform))
            .transform((builder, type, classLoader, module, protectionDomain) -> {
                // Logging interceptor apparently has to be visited first; otherwise if other advice
                // methods throw exception (in enter or exit), the logging interceptor is not run
                builder = builder.visit(Advice.to(MethodLoggingInterceptor.class).on(loggingMatcherF));

                for (var visitor : visitors) {
                    builder = builder.visit(visitor);
                }
                return builder;
            });
    }
}

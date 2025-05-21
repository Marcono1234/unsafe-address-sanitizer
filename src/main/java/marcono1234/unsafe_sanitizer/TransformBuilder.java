package marcono1234.unsafe_sanitizer;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.dynamic.scaffold.InstrumentedType;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.ByteCodeAppender;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.annotation.Annotation;
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
    private final List<ElementMatcher<? super MethodDescription>> methodMatchers;
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
    public TransformBuilder addMethods(ElementMatcher<? super MethodDescription> matcher, Class<?> interceptor) {
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

        var loggingMatcher = new ElementMatcher.Junction.Disjunction<>(methodMatchers);

        var jvmIntrinsicAnnotation = lookUpClass("jdk.internal.vm.annotation.IntrinsicCandidate").asSubclass(Annotation.class);
        var jvmIntrinsicMatcher = new ElementMatcher.Junction.Disjunction<>(methodMatchers)
            .and(isAnnotatedWith(jvmIntrinsicAnnotation));

        return agentBuilder.type(is(classToTransform))
            .transform((builder, type, classLoader, module, protectionDomain) -> {
                // If any of the methods use @IntrinsicCandidate, fail
                // Otherwise JVM might replace method with intrinsic at runtime, despite the method having been transformed
                // Could maybe also solve this by removing annotation (see https://github.com/raphw/byte-buddy/issues/917),
                // but might not be worth it if none of the transformed methods is currently affected by this, and would
                // have to check how JVM behaves in that case
                builder = builder.method(jvmIntrinsicMatcher).intercept(new Implementation() {
                    @Override
                    public InstrumentedType prepare(InstrumentedType instrumentedType) {
                        // Keep the type as-is
                        return instrumentedType;
                    }

                    @Override
                    public ByteCodeAppender appender(Target target) {
                        throw new AssertionError(target.getInstrumentedType() + " defines method with @" + jvmIntrinsicAnnotation.getSimpleName());
                    }
                });

                // Logging interceptor apparently has to be visited first; otherwise if other advice
                // methods throw exception (in enter or exit), the logging interceptor is not run
                builder = builder.visit(Advice.to(MethodLoggingInterceptor.class).on(loggingMatcher));

                for (var visitor : visitors) {
                    builder = builder.visit(visitor);
                }
                return builder;
            });
    }
}

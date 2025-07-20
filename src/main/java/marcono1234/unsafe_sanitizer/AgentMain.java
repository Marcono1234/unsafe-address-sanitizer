package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import org.jetbrains.annotations.VisibleForTesting;

import java.lang.instrument.Instrumentation;
import java.util.*;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Internal class used for standalone agent usage with {@code -javaagent} on the command line.
 */
@SuppressWarnings("unused") // executed for standalone agent usage
class AgentMain {
    private AgentMain() {}

    /**
     * Entry point for agent specified with {@code -javaagent} on the command line.
     *
     * <p>See package documentation for {@link java.lang.instrument}.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        AgentSettings agentSettings;
        if (agentArgs == null || agentArgs.isEmpty()) {
            agentSettings = AgentSettings.defaultSettings();
        } else {
            agentSettings = parseAgentSettings(agentArgs);
        }

        System.out.println("Installing Unsafe Sanitizer agent with settings " + agentSettings);
        UnsafeSanitizer.installAgent(instrumentation, agentSettings);
    }

    private static String createExampleAgentArg(Map.Entry<String, AgentOption<?>> optionEntry) {
        var option = optionEntry.getValue();
        return optionEntry.getKey() + AGENT_ARG_VALUE_SEPARATOR + option.getDefaultValueArgString();
    }

    // `main` method is only used to show usage help
    public static void main(String[] args) {
        System.out.println("=== Unsafe Address Sanitizer ===");
        System.out.println("https://github.com/Marcono1234/unsafe-address-sanitizer");
        System.out.println();
        System.out.println("Standalone agent usage:");
        String jvmAgentArg = "-javaagent:unsafe-address-sanitizer-standalone-agent.jar";
        System.out.println("  java " + jvmAgentArg + "[=<agent-arg>[,<agent-arg>...]] -jar ...");
        System.out.println();
        System.out.println("Agent arguments:");
        for (var optionEntry : agentOptions.entrySet()) {
            var option = optionEntry.getValue();
            System.out.println("  - " + optionEntry.getKey() + ": " + option.parser.getValuesHelp());
            System.out.println("    default: " + option.getDefaultValueArgString());
        }

        System.out.println("(see Javadoc for more details)");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  java " + jvmAgentArg + " -jar my-application.jar");
        var optionsIter = agentOptions.entrySet().iterator();
        System.out.println("  java " + jvmAgentArg + "=" + createExampleAgentArg(optionsIter.next())
            + AGENT_ARGS_SEPARATOR + createExampleAgentArg(optionsIter.next()) + " -jar my-application.jar");
    }

    @VisibleForTesting
    static AgentSettings createDefaultSettings() {
        AgentSettings agentSettings = AgentSettings.defaultSettings();
        // Options have their own default values; this makes it easier to print them in the command line help,
        // and it allows having different default settings for standalone usage
        for (var option : agentOptions.values()) {
            agentSettings = option.applyDefaultTo(agentSettings);
        }
        return agentSettings;
    }

    /** Separates multiple agent args */
    private static final String AGENT_ARGS_SEPARATOR = ",";
    /** Separates agent arg name and value */
    private static final String AGENT_ARG_VALUE_SEPARATOR = "=";

    @VisibleForTesting
    static AgentSettings parseAgentSettings(String agentArgs) {
        AgentSettings agentSettings = createDefaultSettings();

        String[] argStrings = agentArgs.split(Pattern.quote(AGENT_ARGS_SEPARATOR), -1);
        Set<String> seenOptions = new HashSet<>();

        for (String argString : argStrings) {
            if (argString.isBlank()) {
                throw new IllegalArgumentException("Invalid blank argument in agent args: " + agentArgs);
            }

            String[] optionValuePair = argString.split(Pattern.quote(AGENT_ARG_VALUE_SEPARATOR), 2);
            if (optionValuePair.length != 2) {
                throw new IllegalArgumentException("Missing value for '" + argString + "'");
            }

            String optionName = optionValuePair[0];
            AgentOption<?> option = agentOptions.get(optionName);
            if (option == null) {
                throw new IllegalArgumentException("Unknown option: " + optionName);
            }

            try {
                agentSettings = option.applyParsedTo(agentSettings, optionValuePair[1]);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid value for '" + optionName + "': " + e.getMessage(), e);
            }
            if (!seenOptions.add(optionName)) {
                throw new IllegalArgumentException("Duplicate '" + optionName + "' value");
            }
        }

        return agentSettings;
    }

    private static final ArgumentParser<Boolean> BOOLEAN_PARSER = new ArgumentParser<>() {
        @Override
        public Boolean parse(String arg) throws IllegalArgumentException {
            return switch (arg) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("Invalid boolean '" + arg + "'");
            };
        }

        @Override
        public String getValuesHelp() {
            return "true | false";
        }

        @Override
        public String valueToArgString(Boolean value) {
            Objects.requireNonNull(value);
            return value.toString();
        }

        @Override
        public String toString() {
            return "BOOLEAN_PARSER";
        }
    };

    private static final ArgumentParser<ErrorAction> ERROR_ACTION_PARSER = new ArgumentParser<>() {
        private static String errorActionToArgString(ErrorAction errorAction) {
            return errorAction.name().toLowerCase(Locale.ROOT).replace('_', '-');
        }

        private static final Map<String, ErrorAction> actions;
        static {
            Map<String, ErrorAction> tempActions = new LinkedHashMap<>();
            for (ErrorAction errorAction : ErrorAction.values()) {
                String transformedName = errorActionToArgString(errorAction);
                var oldValue = tempActions.put(transformedName, errorAction);
                if (oldValue != null) {
                    throw new AssertionError(errorAction + " and " + oldValue + " have conflicting names");
                }
            }
            // Don't use `Map.copyOf` because the iteration order is unspecified
            actions = Collections.unmodifiableMap(tempActions);
        }

        @Override
        public ErrorAction parse(String arg) throws IllegalArgumentException {
            ErrorAction action = actions.get(arg);
            if (action == null) {
                throw new IllegalArgumentException("Invalid error action '" + arg + "'");
            }
            return action;
        }

        @Override
        public String getValuesHelp() {
            return String.join(" | ", actions.keySet());
        }

        @Override
        public String valueToArgString(ErrorAction value) {
            Objects.requireNonNull(value);
            return errorActionToArgString(value);
        }

        @Override
        public String toString() {
            return "ERROR_ACTION_PARSER";
        }
    };

    @SafeVarargs
    private static <K, V> Map<K, V> mapOf(Map.Entry<K, V>... entries) {
        // Uses LinkedHashMap to preserve order (unlike `Map#ofEntries`)
        Map<K, V> map = new LinkedHashMap<>();
        for (var entry : entries) {
            K key = entry.getKey();
            var oldValue = map.put(key, entry.getValue());
            if (oldValue != null) {
                throw new IllegalArgumentException("Duplicate key: " + key);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    // Don't use `Map.of` because the iteration order is unspecified
    private static final Map<String, AgentOption<?>> agentOptions = mapOf(
        entry("instrumentation-logging", new AgentOption<>(BOOLEAN_PARSER, true, AgentSettings::withInstrumentationLogging)),
        entry("address-alignment-checking", new AgentOption<>(BOOLEAN_PARSER, true, AgentSettings::withAddressAlignmentChecking)),
        entry("global-native-memory-sanitizer", new AgentOption<>(BOOLEAN_PARSER, true, AgentSettings::withGlobalNativeMemorySanitizer)),
        entry("uninitialized-memory-tracking", new AgentOption<>(BOOLEAN_PARSER, true, AgentSettings::withUninitializedMemoryTracking)),
        entry("error-action", new AgentOption<>(ERROR_ACTION_PARSER, ErrorAction.THROW, AgentSettings::withErrorAction)),
        entry("print-errors-to-system-console", new AgentOption<>(BOOLEAN_PARSER, false, AgentSettings::withPrintErrorsToSystemConsole)),
        entry("include-sanitizer-stack-frames", new AgentOption<>(BOOLEAN_PARSER, true, AgentSettings::withIncludeSanitizerErrorStackFrames)),
        entry("call-debug-logging", new AgentOption<>(BOOLEAN_PARSER, false, AgentSettings::withCallDebugLogging))
    );
    static {
        assert agentOptions.size() == AgentSettings.class.getRecordComponents().length;
    }

    private record AgentOption<T>(ArgumentParser<T> parser, T defaultValue, BiFunction<AgentSettings, T, AgentSettings> setter) {
        AgentOption {
            // Assert that at least for default value round trip to and from string works
            assert Objects.equals(defaultValue, parser.parse(parser.valueToArgString(defaultValue)));
        }

        public AgentSettings applyDefaultTo(AgentSettings agentSettings) {
            return setter.apply(agentSettings, defaultValue);
        }

        public AgentSettings applyParsedTo(AgentSettings agentSettings, String value) throws IllegalArgumentException {
            T parsedValue = parser.parse(value);
            return setter.apply(agentSettings, parsedValue);
        }

        public String getDefaultValueArgString() {
            return parser.valueToArgString(defaultValue);
        }
    }

    private interface ArgumentParser<T> {
        T parse(String arg) throws IllegalArgumentException;

        /**
         * Gets a help string representing all valid values, to be shown to the user.
         */
        String getValuesHelp();

        /**
         * Converts a value to the string representation expected by {@link #parse(String)}.
         */
        String valueToArgString(T value);
    }
}

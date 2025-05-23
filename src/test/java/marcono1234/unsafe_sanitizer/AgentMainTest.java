package marcono1234.unsafe_sanitizer;

import marcono1234.unsafe_sanitizer.UnsafeSanitizer.AgentSettings;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;

import static org.junit.jupiter.api.Assertions.*;

class AgentMainTest {
    @Test
    void defaultSettings() {
        AgentSettings agentMainDefaultSettings = AgentMain.createDefaultSettings();
        // For now assume both default settings should be the same; can be changed in the future if necessary
        assertEquals(AgentSettings.defaultSettings(), agentMainDefaultSettings);
    }

    @Test
    void parseAgentSettings() throws Exception {
        // Should keep default values for all non-specified settings
        assertEquals(AgentSettings.defaultSettings(), AgentMain.parseAgentSettings("instrumentation-logging=true"));
        assertEquals(AgentSettings.defaultSettings(), AgentMain.parseAgentSettings("call-debug-logging=false"));

        String agentArgs = "instrumentation-logging=false"
            + ",address-alignment-checking=false"
            + ",global-native-memory-sanitizer=false"
            + ",uninitialized-memory-tracking=false"
            + ",error-action=print-skip"
            + ",call-debug-logging=true";
        AgentSettings parsedSettings = AgentMain.parseAgentSettings(agentArgs);
        AgentSettings defaultSettings = AgentSettings.defaultSettings();
        assertNotEquals(defaultSettings, parsedSettings);

        // Assumes that agentArgs above overwrite default values for all components
        for (RecordComponent recordComponent : AgentSettings.class.getRecordComponents()) {
            Method accessor = recordComponent.getAccessor();
            Object defaultValue = accessor.invoke(defaultSettings);
            Object parsedValue = accessor.invoke(parsedSettings);
            assertNotEquals(defaultValue, parsedValue, "Expected different values for " + recordComponent.getName());
        }
    }

    @Test
    void parseAgentSettings_Error() {
        var e = assertThrows(IllegalArgumentException.class, () -> AgentMain.parseAgentSettings("instrumentation-logging=false,"));
        assertEquals("Invalid blank argument in agent args: instrumentation-logging=false,", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> AgentMain.parseAgentSettings("instrumentation-logging=false,,call-debug-logging=true"));
        assertEquals("Invalid blank argument in agent args: instrumentation-logging=false,,call-debug-logging=true", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> AgentMain.parseAgentSettings("instrumentation-logging"));
        assertEquals("Missing value for 'instrumentation-logging'", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> AgentMain.parseAgentSettings("something-unknown=value"));
        assertEquals("Unknown option: something-unknown", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> AgentMain.parseAgentSettings("instrumentation-logging=not-boolean"));
        assertEquals("Invalid value for 'instrumentation-logging': Invalid boolean 'not-boolean'", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> AgentMain.parseAgentSettings("error-action=something-invalid"));
        assertEquals("Invalid value for 'error-action': Invalid error action 'something-invalid'", e.getMessage());

        e = assertThrows(IllegalArgumentException.class, () -> AgentMain.parseAgentSettings("error-action=throw,error-action=print"));
        assertEquals("Duplicate 'error-action' value", e.getMessage());
    }
}

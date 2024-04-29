/**
 * Module for installing and interacting with the {@code Unsafe} sanitizer, see
 * {@link marcono1234.unsafe_sanitizer.UnsafeSanitizer}.
 */
@SuppressWarnings({"module", "JavaModuleNaming"}) // suppress warnings about module name
module marcono1234.unsafe_sanitizer {
    requires marcono1234.unsafe_sanitizer.agent_impl;

    requires java.instrument;
    // For `sun.misc.Unsafe`
    requires jdk.unsupported;

    requires net.bytebuddy;
    requires net.bytebuddy.agent;
    requires com.google.errorprone.annotations;
    requires org.jetbrains.annotations;

    exports marcono1234.unsafe_sanitizer;
}

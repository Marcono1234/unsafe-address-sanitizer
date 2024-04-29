// TODO: This seems to be ignored at the moment and classes are in unnamed module at runtime, maybe https://bugs.openjdk.org/browse/JDK-6932391?

// This module exists so that instrumentation can open the `java.nio` package to it,
// to allow `DirectByteBufferHelper` to work
@SuppressWarnings({"module", "JavaModuleNaming"}) // suppress warnings about module name
module marcono1234.unsafe_sanitizer.agent_impl {
    // For `sun.misc.Unsafe`
    requires jdk.unsupported;

    requires org.jetbrains.annotations;

    exports marcono1234.unsafe_sanitizer.agent_impl to marcono1234.unsafe_sanitizer;
}

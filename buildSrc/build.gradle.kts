plugins {
    `kotlin-dsl`
}

// Needed for external plugins, see
// https://docs.gradle.org/8.5/samples/sample_convention_plugins.html#applying_an_external_plugin_in_precompiled_script_plugin
repositories {
    gradlePluginPortal()
}

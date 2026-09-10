// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Configuration consumed by FreeRDP's official Android library module.
extra["compileApi"] = 37
extra["targetApi"] = 37
extra["minApi"] = 24
extra["toolsVersion"] = "36.0.0"
extra["ndkVersion"] = "29.0.13113456"
extra["cmakeVersion"] = "4.1.2"
extra["abiFilters"] = arrayOf("arm64-v8a")
extra["splitArchs"] = arrayOf("arm64-v8a")
extra["splitEnabled"] = false
extra["universal"] = false
extra["cmakeArguments"] = arrayOf(
    "-DWITH_FFMPEG=OFF",
    "-DWITH_OPENH264=OFF",
    "-DWITH_OPUS=OFF",
    "-DWITH_WEBP=OFF",
    "-DWITH_JPEG=OFF",
    "-DWITH_PNG=OFF",
    "-DWITH_CJSON=OFF",
    "-DWITH_OPENSSL=ON",
)

// Keep app-specific input changes tracked without modifying the pinned FreeRDP checkout.
project(":freeRDPCore") {
    val upstreamJava = layout.projectDirectory.dir("src/main/java")
    val inputOverride = rootProject.layout.projectDirectory.dir("freerdp-overrides")
    val prepareJava by tasks.registering(Sync::class) {
        from(upstreamJava) {
            exclude("com/freerdp/freerdpcore/presentation/SessionInputManager.java")
            exclude("com/freerdp/freerdpcore/presentation/SessionActivity.java")
            exclude("com/freerdp/freerdpcore/services/LibFreeRDP.java")
        }
        from(inputOverride)
        into(layout.buildDirectory.dir("remotePcJava"))
    }
    val nativeOverrides = rootProject.layout.projectDirectory.dir("freerdp-native-overrides")
    val prepareNative by tasks.registering(Sync::class) {
        from(layout.projectDirectory.dir("src/main/cpp")) {
            exclude(nativeOverrides.asFile.listFiles()!!.map { it.name })
        }
        from(nativeOverrides)
        into(layout.buildDirectory.dir("remotePcCpp"))
    }
    plugins.withId("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            namespace = "com.freerdp.freerdpcore"
            sourceSets.getByName("main").java.setSrcDirs(listOf(prepareJava.map { it.destinationDir }))
        }
        tasks.named("preBuild").configure { dependsOn(prepareJava) }
        tasks.configureEach {
            if (name.startsWith("configureCMake") || name.startsWith("buildCMake") || name == "preBuild") {
                dependsOn(prepareNative)
            }
        }
    }
}

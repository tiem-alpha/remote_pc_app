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

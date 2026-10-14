import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localSettings = Properties().apply {
    val settingsFile = rootProject.file("local.properties")
    if (settingsFile.exists()) settingsFile.inputStream().use { load(it) }
}

fun configuredValue(name: String): String =
    localSettings.getProperty(name)
        ?: providers.environmentVariable(name).orNull
        ?: ""

fun String.asBuildConfigLiteral(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "org.sibaq.screening"
    compileSdk = 35

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "org.sibaq.screening"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "SUPABASE_URL", configuredValue("SIBAQ_SUPABASE_URL").asBuildConfigLiteral())
        buildConfigField("String", "SUPABASE_ANON_KEY", configuredValue("SIBAQ_SUPABASE_ANON_KEY").asBuildConfigLiteral())
    }

    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10"
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) {
        propertiesFile.inputStream().use(::load)
    }
}

fun configurationValue(name: String): String =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name).orEmpty()

fun String.asBuildConfigStringLiteral(): String {
    val escaped = buildString {
        for (character in this@asBuildConfigStringLiteral) {
            append(
                when (character) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\t' -> "\\t"
                    '\u000C' -> "\\f"
                    '\b' -> "\\b"
                    else -> character
                },
            )
        }
    }
    return "\"$escaped\""
}

fun isSecureReleaseApiUrl(value: String): Boolean = runCatching {
    URI(value.trim())
}.getOrNull()?.let { uri ->
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
} == true

val debugApiBaseUrl = configurationValue("API_BASE_URL")
val releaseApiBaseUrl = configurationValue("RELEASE_API_BASE_URL")

android {
    namespace = "com.apptive.slowtalk"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.apptive.slowtalk"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        getByName("debug") {
            buildConfigField(
                "String",
                "API_BASE_URL",
                debugApiBaseUrl.asBuildConfigStringLiteral(),
            )
        }
        getByName("release") {
            buildConfigField(
                "String",
                "API_BASE_URL",
                releaseApiBaseUrl.asBuildConfigStringLiteral(),
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

val verifyBuildConfigEscaping by tasks.registering {
    group = "verification"
    description = "Verifies BuildConfig string values are valid Java string literals."
    doLast {
        val rawValue = "slash\\quote\"newline\nreturn\rtab\tform\u000Cback\b"
        val expected = "\"slash\\\\quote\\\"newline\\nreturn\\rtab\\tform\\fback\\b\""
        check(rawValue.asBuildConfigStringLiteral() == expected) {
            "BuildConfig string escaping did not encode control characters safely."
        }
    }
}

val validateReleaseApiBaseUrl by tasks.registering {
    group = "verification"
    description = "Requires a non-blank HTTPS RELEASE_API_BASE_URL for release builds."
    doLast {
        check(isSecureReleaseApiUrl(releaseApiBaseUrl)) {
            "RELEASE_API_BASE_URL must be set to a non-blank HTTPS URL, for example " +
                "-PRELEASE_API_BASE_URL=https://api.example.org/api/v1/."
        }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        dependsOn(validateReleaseApiBaseUrl)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

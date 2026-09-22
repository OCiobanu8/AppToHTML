plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.apptohtml"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.apptohtml"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

/**
 * Forwards the validation tool's own properties to the unit-test JVM.
 *
 * `ScreenIdentityValidationEntryPoint` runs the screen-identity validator on the host with no
 * device, and Gradle does not pass system properties to test JVMs by default. Only the `a2h.*` keys
 * are forwarded, and only when set: with none present nothing is forwarded, the entry point's
 * assumptions skip it, and a plain `./gradlew test` behaves exactly as before. Standard streams are
 * shown only for those runs, so the report reaches the console without making the ordinary suite
 * noisy.
 */
val validationProperties = listOf(
    "a2h.target",
    "a2h.observed",
    "a2h.known",
    "a2h.crawl",
    "a2h.root",
)

tasks.withType<Test>().configureEach {
    val supplied = validationProperties.mapNotNull { key ->
        System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { key to it }
    }
    supplied.forEach { (key, value) -> systemProperty(key, value) }
    if (supplied.isNotEmpty()) {
        // A validation run is asked for by hand and its whole point is the printed report.
        outputs.upToDateWhen { false }
        testLogging { showStandardStreams = true }
    }
}

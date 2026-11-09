plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// In CI geeft de workflow het runnummer mee, zodat elke nieuwe APK een hogere
// versiecode heeft en netjes over de vorige heen installeert.
val buildNumber = (System.getenv("ADEMPAUZE_BUILD_NUMBER") ?: "1").toInt()

android {
    namespace = "nl.adempauze"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.adempauze"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // Eén vaste sleutel, zodat een nieuwe versie altijd over de oude heen
        // installeert. Wie een eigen sleutel wil, zet de vier ADEMPAUZE_*-
        // variabelen; anders wordt de sleutel uit de repository gebruikt.
        create("sideload") {
            val customStore = System.getenv("ADEMPAUZE_KEYSTORE")
            storeFile = file(customStore ?: "../signing/adempauze-sideload.jks")
            storePassword = System.getenv("ADEMPAUZE_KEYSTORE_PASSWORD") ?: "adempauze"
            keyAlias = System.getenv("ADEMPAUZE_KEY_ALIAS") ?: "adempauze"
            keyPassword = System.getenv("ADEMPAUZE_KEY_PASSWORD") ?: "adempauze"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                // Optioneel een andere Maven-spiegel voor de Android-jars van Robolectric.
                providers.gradleProperty("robolectricRepo").orNull?.let { repo ->
                    it.systemProperty("robolectric.dependency.repo.url", repo)
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Echte org.json voor de JVM-tests; op de telefoon zit die in Android zelf.
    testImplementation("org.json:json:20250517")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.52.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.52.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

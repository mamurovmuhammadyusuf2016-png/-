import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The Groq key never lives in git. It comes from local.properties (git-ignored) or from
// the GROQ_API_KEY environment variable / CI secret. If neither is set the app still
// builds — you just paste the key into the app's own settings screen instead.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val groqApiKey: String = (localProperties.getProperty("GROQ_API_KEY")
    ?: System.getenv("GROQ_API_KEY")
    ?: "").trim()

android {
    namespace = "com.jarvis.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "GROQ_API_KEY", "\"$groqApiKey\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // MVP: release is signed with the debug key so that CI can produce
            // an installable APK without any secret. Do not publish this build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
}

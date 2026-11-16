plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Debug builds carry a number that goes up on every install to a phone (scripts/install-debug.sh), so the About page shows
// which build is on the phone. It lives in the home folder, so every worktree shares one counter; 0 when there is none.
val debugBuildNumber: String = File(System.getProperty("user.home"), ".chapterplayer-debug-build")
    .takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: "0"

android {
    namespace = "com.chapterplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.chapterplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug.$debugBuildNumber"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        // New problems fail the build; what already existed is in lint-baseline.xml.
        baseline = file("lint-baseline.xml")
        abortOnError = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.activity.compose)
    implementation(libs.core.ktx)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Koin starts in the application class.
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.android)
}

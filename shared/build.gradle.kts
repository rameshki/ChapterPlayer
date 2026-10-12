import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

kotlin {
    android {
        namespace = "com.chapterplayer.shared"
        compileSdk = 37
        minSdk = 26

        // Hilt generates Java sources, so Java compilation must be on.
        withJava()
        // Fonts live in src/androidMain/res until Compose Multiplatform resources arrive.
        androidResources { enable = true }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        androidMain.dependencies {
            // AndroidX Core
            implementation(libs.core.ktx)
            implementation(libs.activity.compose)
            implementation(libs.lifecycle.runtime.ktx)

            // Compose
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui)
            implementation(libs.compose.ui.graphics)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.compose.material3)
            implementation(libs.compose.icons.extended)

            // Lifecycle + ViewModel
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)

            // Navigation
            implementation(libs.navigation.compose)

            // Hilt (Dependency Injection)
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)

            // Room (Local Database)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)

            // Networking
            implementation(libs.retrofit)
            implementation(libs.retrofit.moshi)
            implementation(libs.okhttp)
            implementation(libs.okhttp.logging)
            implementation(libs.moshi)
            implementation(libs.moshi.kotlin)

            // Coroutines
            implementation(libs.coroutines.android)

            // Image Loading
            implementation(libs.coil.compose)

            // DataStore (Settings persistence)
            implementation(libs.datastore.preferences)
        }
    }
}

dependencies {
    add("kspAndroid", libs.hilt.android.compiler)
    add("kspAndroid", libs.room.compiler)
    add("kspAndroid", libs.moshi.kotlin.codegen)
}

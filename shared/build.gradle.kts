import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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

        // JVM unit tests for code in androidMain (src/androidHostTest).
        withHostTest { }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.test)
        }

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

            // Koin (Dependency Injection)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.android)
            implementation(libs.koin.compose.viewmodel)

            // Hilt (Dependency Injection)
            implementation(libs.hilt.android)
            implementation(libs.hilt.navigation.compose)

            // Room (Local Database)
            implementation(libs.room.runtime)
            implementation(libs.room.ktx)

            // Ktor + kotlinx.serialization
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)


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
}

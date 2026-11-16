import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.roborazzi)
}

kotlin {
    android {
        namespace = "com.chapterplayer.shared"
        compileSdk = 37
        minSdk = 26

        // Fonts live in src/androidMain/res until Compose Multiplatform resources arrive.
        androidResources { enable = true }

        // JVM unit tests for code in androidMain (src/androidHostTest). Android resources (and the Compose resources
        // packed as assets) are on the test classpath so Robolectric can draw the app's screens for screenshot tests.
        withHostTest {
            isIncludeAndroidResources = true
        }

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // The iOS app links this module as a static framework named Shared.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    compilerOptions {
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }

    sourceSets {
        // Data layer: models, entities, DAOs and the database. Nothing here may use Android APIs.
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.coil3.compose)
            implementation(libs.coil3.network.ktor3)
            implementation(libs.room.runtime)
            implementation(libs.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.jetbrains.lifecycle.viewmodel)
            implementation(libs.jetbrains.lifecycle.runtime.compose)
            implementation(libs.jetbrains.navigation.compose)
            implementation(libs.compose.icons.extended.cmp)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.datastore.preferences)
        }

        // Tests of pure shared code: they run on the JVM (with the host tests) and on iOS (iosSimulatorArm64Test).
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }

        getByName("androidHostTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.test)
            // Real SQLite on the host for the Room migration tests (the Android variant has only device natives)
            implementation(libs.sqlite.bundled.jvm)
            // Screenshot and UI tests: Robolectric (native graphics) draws Compose; Roborazzi records and verifies goldens
            implementation(libs.junit4)
            implementation(libs.robolectric)
            implementation(libs.roborazzi)
            implementation(libs.roborazzi.compose)
            implementation(project.dependencies.platform(libs.compose.bom))
            implementation(libs.compose.ui.test.junit4)
            implementation(libs.compose.ui.test.manifest)
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

            // Koin (Dependency Injection)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.android)


            // Ktor: the Android engine (the client set-up itself is common)
            implementation(libs.ktor.client.okhttp)


            // Coroutines
            implementation(libs.coroutines.android)

            // Media3: the playback service and its player
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.session)
            implementation(libs.media3.common)
            implementation(libs.media3.datasource)
            // Audio over OkHttp with redirects followed by hand, so a token header never goes to another host
            implementation(libs.media3.datasource.okhttp)

            // WorkManager: the download worker
            implementation(libs.work.runtime.ktx)

            // Glance: the home screen widgets
            implementation(libs.glance.appwidget)
        }

        iosMain.dependencies {
            // Ktor: the iOS engine (NSURLSession)
            implementation(libs.ktor.client.darwin)
            // Room: the bundled SQLite driver
            implementation(libs.sqlite.bundled)
        }
    }
}

compose.resources {
    packageOfResClass = "com.chapterplayer.resources"
}

// Screenshot goldens are committed next to the tests. Record: recordRoborazziAndroidHostTest; verify:
// verifyRoborazziAndroidHostTest (fails when an image changed). See docs/architecture/testing.md.
roborazzi {
    outputDir.set(file("src/androidHostTest/screenshots"))
}

// The exported schema records what installed apps already hold; RoomSchemaTest pins it.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

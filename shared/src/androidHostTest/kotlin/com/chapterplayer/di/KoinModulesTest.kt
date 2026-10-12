package com.chapterplayer.di

import android.content.Context
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.Test

/**
 * Hilt failed the build when a binding was missing. Koin only fails when a screen opens,
 * so these tests walk every constructor in the graph and fail if a dependency is not declared.
 */
class KoinModulesTest {

    // What the Android app module provides: the Application context, and the debug flag
    // that the network module reads through a qualifier.
    private val providedByApp = listOf(Context::class, Boolean::class) +
        // HttpClient is built by createHttpClient, not through its constructor, so the
        // engine and config its constructor mentions are not bindings of ours.
        listOf(HttpClientEngine::class, HttpClientConfig::class)

    @Test
    fun `the database module resolves`() {
        databaseModule.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the network module resolves`() {
        networkModule.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the repository module resolves`() {
        // Repositories need the DAOs and API services, which other modules declare.
        module { includes(databaseModule, networkModule, repositoryModule) }.verify(extraTypes = providedByApp)
    }

    @Test
    fun `every screen's view model can be built`() {
        module { includes(databaseModule, networkModule, repositoryModule, viewModelModule) }
            .verify(extraTypes = providedByApp)
    }

    @Test
    fun `the whole shared graph resolves`() {
        module { includes(sharedModules) }.verify(extraTypes = providedByApp)
    }
}

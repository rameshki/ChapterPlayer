package com.chapterplayer.di

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.chapterplayer.ui.library.LibraryView
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        listOf(HttpClientEngine::class, HttpClientConfig::class) +
        // The group detail screen passes its kind and key with parametersOf.
        listOf(LibraryView::class, String::class) +
        // The library view keeps its chosen view in the saved state; the view model owner provides it.
        listOf(SavedStateHandle::class)

    @Test
    fun `the database module resolves`() {
        databaseModule.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the network module resolves`() {
        networkModule.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the security module resolves`() {
        securityModule.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the device module resolves`() {
        deviceModule.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the repository module resolves`() {
        // Repositories need the DAOs and API services, which other modules declare.
        module { includes(databaseModule, networkModule, securityModule, repositoryModule) }.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the playback module resolves`() {
        module { includes(databaseModule, networkModule, securityModule, repositoryModule, deviceModule, playbackModule) }
            .verify(extraTypes = providedByApp)
    }

    @Test
    fun `the download module resolves`() {
        // The length filler watches the network (deviceModule).
        module { includes(databaseModule, networkModule, securityModule, repositoryModule, deviceModule, downloadModule) }
            .verify(extraTypes = providedByApp)
    }

    @Test
    fun `every screen's view model can be built`() {
        module { includes(databaseModule, networkModule, securityModule, repositoryModule, deviceModule, playbackModule, downloadModule, browseModule, viewModelModule) }
            .verify(extraTypes = providedByApp)
    }

    @Test
    fun `the whole shared graph resolves`() {
        module { includes(sharedModules) }.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the app scope and the io dispatcher are only found by their names`() {
        val koin = koinApplication { modules(repositoryModule, browseModule) }.koin
        assertNotNull(koin.get<CoroutineScope>(AppScopeQualifier))
        assertNotNull(koin.get<CoroutineDispatcher>(IoDispatcherQualifier))
        assertNull(koin.getOrNull<CoroutineScope>())
        assertNull(koin.getOrNull<CoroutineDispatcher>())
    }
}

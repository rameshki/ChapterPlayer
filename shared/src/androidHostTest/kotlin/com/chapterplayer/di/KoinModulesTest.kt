package com.chapterplayer.di

import android.content.Context
import androidx.datastore.core.DataStore
import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.security.CredentialStore
import androidx.lifecycle.SavedStateHandle
import com.chapterplayer.ui.library.LibraryView
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import org.koin.core.annotation.KoinInternalApi
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
        // The length filler watches the network (deviceModule); Plex downloads read the tracks through the Plex source (sourceModule).
        module { includes(databaseModule, networkModule, securityModule, repositoryModule, sourceModule, deviceModule, downloadModule) }
            .verify(extraTypes = providedByApp)
    }

    @Test
    fun `every screen's view model can be built`() {
        module { includes(databaseModule, networkModule, securityModule, repositoryModule, sourceModule, deviceModule, playbackModule, downloadModule, browseModule, viewModelModule) }
            .verify(extraTypes = providedByApp)
    }

    @Test
    fun `the widget module resolves`() {
        // The widgets read the player, the books, the downloads, the network and the settings.
        module { includes(databaseModule, networkModule, securityModule, repositoryModule, sourceModule, deviceModule, playbackModule, downloadModule, browseModule, widgetModule) }
            .verify(extraTypes = providedByApp)
    }

    @Test
    fun `the whole shared graph resolves`() {
        module { includes(sharedModules) }.verify(extraTypes = providedByApp)
    }

    // What a platform module provides for the common modules besides a BookRepository (the iOS module: iosPlatformModule),
    // plus the HTTP engine and config the client constructor mentions.
    private val providedByPlatform = listOf(
        ChapterPlayerDatabase::class, DataStore::class, CredentialStore::class, PlexClientIdentifier::class,
        ConnectivityMonitor::class, AppInfo::class, CoroutineDispatcher::class, Boolean::class,
        HttpClientEngine::class, HttpClientConfig::class,
    )

    @Test
    fun `the common modules resolve with what a platform provides and the iOS stand-ins`() {
        module { includes(commonModules + notYetOnIosModule) }.verify(extraTypes = providedByPlatform)
    }

    @Test
    fun `the common modules need a book repository from the platform`() {
        val missing = runCatching { module { includes(commonModules) }.verify(extraTypes = providedByPlatform) }.exceptionOrNull()
        assertNotNull(missing)
        assertTrue(BookRepository::class.qualifiedName!! in missing.message.orEmpty())
    }

    @Test
    fun `the iOS stand-in module gives the not-yet book repository`() {
        val koin = koinApplication { modules(notYetOnIosModule) }.koin
        assertSame(com.chapterplayer.data.repository.NotYetOnIosBookRepository, koin.get<BookRepository>())
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun `Android's modules include every common module`() {
        val android = sharedModules.flatMap { it.includedModules + it }.toSet()
        commonModules.forEach { common -> assertTrue(common in android, "a common module is not part of Android's set") }
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

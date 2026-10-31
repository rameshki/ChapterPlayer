package com.chapterplayer.di

import android.content.Context
import androidx.datastore.core.DataStore
import com.chapterplayer.data.AppInfo
import com.chapterplayer.data.connectivity.ConnectivityMonitor
import com.chapterplayer.data.local.ChapterPlayerDatabase
import com.chapterplayer.data.remote.plex.PlexClientIdentifier
import com.chapterplayer.data.repository.BookRepository
import com.chapterplayer.data.security.CredentialStore
import com.chapterplayer.data.PlatformFeatures
import com.chapterplayer.data.downloads.DownloadController
import com.chapterplayer.data.downloads.DownloadStorage
import com.chapterplayer.data.downloads.LocalBookFiles
import com.chapterplayer.data.downloads.NoDownloadStorage
import com.chapterplayer.data.downloads.NoLocalBookFiles
import com.chapterplayer.data.downloads.NotYetOnIosDownloadController
import com.chapterplayer.data.permissions.NoNotificationPermission
import com.chapterplayer.data.permissions.NotificationPermission
import com.chapterplayer.data.playback.NotYetOnIosPlaybackController
import com.chapterplayer.data.playback.PlaybackController
import com.chapterplayer.data.stats.ListeningClock
import com.chapterplayer.domain.stats.DayZone
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
import kotlin.test.assertEquals
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
        // Repositories need the DAOs and API services, which other modules declare; the foreground sync reads connectivity.
        module { includes(databaseModule, networkModule, securityModule, deviceModule, repositoryModule) }.verify(extraTypes = providedByApp)
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

    // What a platform module provides for the common modules besides the player and download parts (the iOS module:
    // iosPlatformModule), plus the HTTP engine and config the client constructor mentions and the view model parameters.
    private val providedByPlatform = listOf(
        ChapterPlayerDatabase::class, DataStore::class, CredentialStore::class, PlexClientIdentifier::class,
        ConnectivityMonitor::class, AppInfo::class, CoroutineDispatcher::class, Boolean::class,
        DayZone::class, ListeningClock::class,
        HttpClientEngine::class, HttpClientConfig::class,
        LibraryView::class, String::class, SavedStateHandle::class,
    )

    // iOS binds its AVPlayer controller in iosMain (iosPlaybackModule), which the host tests cannot build: a stand-in plays its part.
    private val testPlayer = module { single<PlaybackController> { NotYetOnIosPlaybackController() } }

    @Test
    fun `the common modules resolve with what a platform provides and the iOS stand-ins`() {
        module { includes(commonModules + notYetOnIosModule + testPlayer) }.verify(extraTypes = providedByPlatform)
    }

    /** The platform module built from fakes, as iOS builds it from its parts; the database is never opened here. */
    private fun fakePlatform(dir: java.io.File) = platformModule(
        PlatformParts(
            debuggable = false,
            paths = {
                object : com.chapterplayer.data.PathProvider {
                    override fun preferencesFile(name: String) = java.io.File(dir, "$name.preferences_pb").absolutePath
                    override fun databaseFile(name: String) = java.io.File(dir, name).absolutePath
                }
            },
            database = { error("no database in this test") },
            credentials = { object : CredentialStore {
                override suspend fun get(serverId: String): String? = null
                override suspend fun put(serverId: String, token: String) {}
                override suspend fun remove(serverId: String) {}
            } },
            plexClientId = { com.chapterplayer.testing.TestPlexClientId },
            connectivity = { object : ConnectivityMonitor { override val isOnline = kotlinx.coroutines.flow.flowOf(true) } },
            appInfo = { object : AppInfo { override val versionName = "test" } },
            blockingIo = kotlinx.coroutines.Dispatchers.IO,
            dayZone = { DayZone.UTC },
            listeningClock = { object : ListeningClock {
                override fun monoMs() = 0L
                override fun nowMs() = 0L
            } },
        )
    )

    @Test
    fun `every qualified binding the common modules ask for is there with the platform module`() {
        val dir = kotlin.io.path.createTempDirectory("koin").toFile()
        val koin = koinApplication { modules(commonModules + notYetOnIosModule + testPlayer + fakePlatform(dir)) }.koin
        // The qualified look-ups inside the common definitions; verify() cannot see qualifiers, so each is resolved.
        assertNotNull(koin.get<Boolean>(DebuggableQualifier))
        assertNotNull(koin.get<DataStore<androidx.datastore.preferences.core.Preferences>>(SettingsStoreQualifier))
        assertNotNull(koin.get<DataStore<androidx.datastore.preferences.core.Preferences>>(ServerStatusStoreQualifier))
        assertNotNull(koin.get<CoroutineDispatcher>(BlockingIoQualifier))
        assertNotNull(koin.get<CoroutineDispatcher>(IoDispatcherQualifier))
        assertNotNull(koin.get<CoroutineScope>(AppScopeQualifier))
        assertNotNull(koin.get<io.ktor.client.HttpClient>(ProbeClientQualifier))
        // And what is built from them without the database.
        assertNotNull(koin.get<com.chapterplayer.data.settings.AppSettings>())
        assertNotNull(koin.get<com.chapterplayer.data.sync.ServerStatusStore>())
        assertNotNull(koin.get<com.chapterplayer.data.sync.PlexConnectionProbe>())
        assertNotNull(koin.get<com.chapterplayer.data.remote.plex.PlexApiService>())
        assertNotNull(koin.get<com.chapterplayer.data.auth.AbsSignInPort>())
        koin.close()
    }

    @Test
    fun `the common modules resolve with the platform module and the iOS stand-ins`() {
        val dir = kotlin.io.path.createTempDirectory("koin").toFile()
        module { includes(commonModules + notYetOnIosModule + testPlayer + fakePlatform(dir)) }
            .verify(extraTypes = listOf(HttpClientEngine::class, HttpClientConfig::class, LibraryView::class, String::class, SavedStateHandle::class))
    }

    @Test
    fun `the iOS player module verifies with the common modules and the platform module`() {
        val dir = kotlin.io.path.createTempDirectory("koin").toFile()
        val player = headerPlayerModule { _, _, _, _ -> NotYetOnIosPlaybackController() }
        module { includes(commonModules + notYetOnIosModule + player + fakePlatform(dir)) }
            .verify(extraTypes = listOf(HttpClientEngine::class, HttpClientConfig::class, LibraryView::class, String::class, SavedStateHandle::class))
    }

    @Test
    fun `the iOS player module builds the player with the stream authorizer on the probe client`() {
        val dir = kotlin.io.path.createTempDirectory("koin").toFile()
        var given: com.chapterplayer.data.playback.StreamAuthorizer? = null
        var givenFiles: LocalBookFiles? = null
        val player = headerPlayerModule { _, streams, _, files ->
            given = streams
            givenFiles = files
            NotYetOnIosPlaybackController()
        }
        // No database here: the server list and the book repository are fakes.
        val noDatabase = module {
            single<com.chapterplayer.data.repository.ServerRepository> { com.chapterplayer.testing.FakeServerList() }
            single<BookRepository> { com.chapterplayer.testing.FakeBookRepository() }
        }
        val koin = koinApplication { modules(commonModules + notYetOnIosModule + fakePlatform(dir) + player + noDatabase) }.koin
        assertTrue(koin.get<PlaybackController>() is NotYetOnIosPlaybackController)
        assertSame(koin.get<com.chapterplayer.data.playback.StreamAuthorizer>(), given)
        assertSame(NoLocalBookFiles, givenFiles)
        assertSame(PlatformFeatures.IosStreaming, koin.get<PlatformFeatures>())
        koin.close()
    }

    @Test
    fun `a missing qualified platform binding fails`() {
        val dir = kotlin.io.path.createTempDirectory("koin").toFile()
        val withoutBlockingIo = module { includes(commonModules + notYetOnIosModule + testPlayer) }
        val koin = koinApplication { modules(withoutBlockingIo) }.koin
        assertNull(koin.getOrNull<CoroutineDispatcher>(BlockingIoQualifier))
        assertNull(koin.getOrNull<DataStore<androidx.datastore.preferences.core.Preferences>>(SettingsStoreQualifier))
        val full = koinApplication { modules(commonModules + notYetOnIosModule + testPlayer + fakePlatform(dir)) }.koin
        assertNotNull(full.getOrNull<CoroutineDispatcher>(BlockingIoQualifier))
    }

    @Test
    fun `the common modules need the player from the platform`() {
        val missing = runCatching { module { includes(commonModules) }.verify(extraTypes = providedByPlatform) }.exceptionOrNull()
        assertNotNull(missing)
        assertTrue(PlaybackController::class.qualifiedName!! in missing.message.orEmpty())
    }

    @OptIn(KoinInternalApi::class)
    @Test
    fun `the book repository is the shared one on every platform`() {
        val name = BookRepository::class.qualifiedName!!
        assertTrue(commonRepositoryModule.mappings.keys.any { name in it })
        // Neither iOS's stand-ins nor Android's own modules bind another one (an included module counts once, from common).
        assertTrue(notYetOnIosModule.mappings.keys.none { name in it })
        val androidOwn = listOf(
            databaseModule, networkModule, securityModule, sourceModule, repositoryModule, deviceModule, playbackModule,
            downloadModule, browseModule, viewModelModule, widgetModule,
        )
        assertTrue(androidOwn.none { m -> m.mappings.keys.any { name in it } })
        // In the whole Android graph there is exactly one, and it is the common one.
        val all = sharedModules.flatMap { it.includedModules + it }.toSet()
        assertEquals(listOf(commonRepositoryModule), all.filter { m -> m.mappings.keys.any { name in it } })
    }

    @Test
    fun `the iOS stand-in module gives the not-yet downloads and the streaming features but no player`() {
        val koin = koinApplication { modules(notYetOnIosModule) }.koin
        assertNull(koin.getOrNull<PlaybackController>())
        assertTrue(koin.get<DownloadController>() is NotYetOnIosDownloadController)
        assertSame(NoLocalBookFiles, koin.get<LocalBookFiles>())
        assertSame(NoDownloadStorage, koin.get<DownloadStorage>())
        assertSame(NoNotificationPermission, koin.get<NotificationPermission>())
        assertSame(PlatformFeatures.IosStreaming, koin.get<PlatformFeatures>())
    }

    @Test
    fun `Android has every feature`() {
        val koin = koinApplication { modules(deviceModule) }.koin
        assertSame(PlatformFeatures.Full, koin.get<PlatformFeatures>())
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

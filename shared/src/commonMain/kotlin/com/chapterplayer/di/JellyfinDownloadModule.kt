package com.chapterplayer.di

import com.chapterplayer.data.downloads.DownloadSource
import com.chapterplayer.data.downloads.JellyfinDownloadSource
import com.chapterplayer.data.repository.ServerRepository
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * The Jellyfin download source, defined ONCE and included by both download modules (Android's `downloadModule` and the common
 * `inAppDownloadModule`). It was first added to only one of them, so on Android the downloader found no source for Jellyfin and
 * refused every Jellyfin book ("Books from this server cannot be downloaded"), which a host test of either module alone cannot see.
 *
 * Jellyfin tokens cannot be refreshed, so a refused one is blanked ("Sign in again") the way Plex's and the library source's are.
 */
val jellyfinDownloadModule = module {
    single {
        val servers = get<ServerRepository>()
        JellyfinDownloadSource(
            api = get(), installId = get(), appInfo = get(),
            markSignedOut = { refused -> blankRefusedPlexToken(servers, refused) },
        )
    } bind DownloadSource::class
}

package com.chapterplayer.data.images

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.serviceLoaderEnabled
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.chapterplayer.data.remote.createImageHttpClient

/**
 * Sets the app's one image loader: Coil's defaults, a network fetcher whose client drops credential headers on a
 * redirect to another host ([createImageHttpClient]), and the Plex token header for covers on a signed-in Plex server.
 * The service loader is off so no other network fetcher (with Ktor's default redirects) can take the requests.
 * Call once, before the first image loads (Android's Application and iOS's `initKoin` do). The engine is the
 * platform's (OkHttp; Darwin with no URL cache and no cookies), so a token never lands in a cache or a stored address.
 */
fun installCoverImageLoader(servers: PlexImageServers) {
    SingletonImageLoader.setSafe { platformContext ->
        ImageLoader.Builder(platformContext)
            .serviceLoaderEnabled(false)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { createImageHttpClient() }))
                add(PlexCoverInterceptor(servers::plexServers))
            }
            .build()
    }
}

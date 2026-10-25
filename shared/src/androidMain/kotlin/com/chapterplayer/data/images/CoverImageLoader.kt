package com.chapterplayer.data.images

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.serviceLoaderEnabled
import coil3.network.ktor3.KtorNetworkFetcherFactory
import com.chapterplayer.data.remote.createImageHttpClient

/**
 * Sets the app's one image loader: Coil's defaults, a network fetcher whose client drops credential headers on a
 * redirect to another host ([createImageHttpClient]), and the Plex token header for covers on a signed-in Plex server.
 * The service loader is off so no other network fetcher (with Ktor's default redirects) can take the requests.
 * Call once, before the first image loads (the Application does).
 */
fun installCoverImageLoader(context: Context, servers: PlexImageServers) {
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

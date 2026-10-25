package com.chapterplayer.data.images

import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader

/**
 * Sets the app's one image loader: Coil's defaults (the Ktor network fetcher is found on its own) plus the Plex token
 * header for covers on a signed-in Plex server. Call once, before the first image loads (the Application does).
 */
fun installCoverImageLoader(context: Context, servers: PlexImageServers) {
    SingletonImageLoader.setSafe { platformContext ->
        ImageLoader.Builder(platformContext)
            .components { add(PlexCoverInterceptor(servers::plexServers)) }
            .build()
    }
}

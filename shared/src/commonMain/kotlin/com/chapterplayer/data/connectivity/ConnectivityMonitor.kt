package com.chapterplayer.data.connectivity

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Whether the device has a network. Starts as true until the platform reports, and never throws. */
interface ConnectivityMonitor {
    val isOnline: Flow<Boolean>

    /**
     * A name for the network in use, which changes when the phone moves to another network (Wi-Fi to mobile data, one
     * Wi-Fi to another); null with no network. Without a platform answer it only follows [isOnline].
     */
    val currentNetwork: Flow<String?> get() = isOnline.map { online -> if (online) "online" else null }
}

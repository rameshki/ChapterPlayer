package com.chapterplayer.data.connectivity

import com.chapterplayer.domain.downloads.downloadAllowedOn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Whether the device has a network. Starts as true until the platform reports, and never throws. */
interface ConnectivityMonitor {
    val isOnline: Flow<Boolean>

    /**
     * A name for the network in use, which changes when the phone moves to another network (Wi-Fi to mobile data, one
     * Wi-Fi to another); null with no network. Without a platform answer it only follows [isOnline].
     */
    val currentNetwork: Flow<String?> get() = isOnline.map { online -> if (online) "online" else null }

    /**
     * The network as downloads see it: online, and metered (mobile data, a personal hotspot, Low Data Mode). iOS reads it
     * from `NWPathMonitor` (expensive or constrained); without a platform answer nothing is metered (Android's downloads
     * leave the Wi-Fi only rule to WorkManager and do not read this).
     */
    val networkState: Flow<NetworkState> get() = isOnline.map { NetworkState(online = it, metered = false) }
}

/** Whether there is a network and whether it is metered. */
data class NetworkState(val online: Boolean, val metered: Boolean)

/** Whether a download may run now under the Wi-Fi only rule [wifiOnly], following the network. */
fun ConnectivityMonitor.downloadAllowed(wifiOnly: Boolean): Flow<Boolean> =
    networkState.map { downloadAllowedOn(wifiOnly, online = it.online, metered = it.metered) }.distinctUntilChanged()

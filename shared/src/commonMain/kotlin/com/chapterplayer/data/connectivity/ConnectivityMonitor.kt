package com.chapterplayer.data.connectivity

import kotlinx.coroutines.flow.Flow

/** Whether the device has a network. Starts as true until the platform reports, and never throws. */
interface ConnectivityMonitor {
    val isOnline: Flow<Boolean>
}

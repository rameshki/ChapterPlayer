package com.chapterplayer.data.connectivity

/**
 * One report from a platform that describes the network as a path (iOS `NWPathMonitor`): whether it can carry
 * traffic, and the names of the interfaces it uses ("en0" for Wi-Fi, "pdp_ip0" for mobile data).
 */
data class NetworkPath(val satisfied: Boolean, val interfaces: List<String>)

/**
 * Names the network in use for [ConnectivityMonitor.currentNetwork] when the platform gives no network handle (iOS):
 * no usable path is null; a path that becomes usable again or moves to other interfaces gets a new name, so the Plex
 * connection choice runs again. One Wi-Fi to another without a gap keeps the name (iOS does not say which Wi-Fi it is
 * without an extra entitlement); the 30 s re-probe after a failure covers that case. One tracker per collector.
 */
class NetworkPathTracker {
    private var generation = 0
    private var last: NetworkPath? = null

    fun update(path: NetworkPath): String? {
        val previous = last
        last = path
        if (!path.satisfied) return null
        val interfaces = path.interfaces.sorted()
        if (previous == null || !previous.satisfied || previous.interfaces.sorted() != interfaces) generation++
        return "path-$generation:" + interfaces.joinToString(",")
    }
}

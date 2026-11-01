package com.chapterplayer.data.connectivity

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import platform.Network.nw_interface_get_name
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * Follows the device's network with `NWPathMonitor` (one monitor per collector, cancelled when collection stops).
 * Online means a satisfied path; the network's name comes from [NetworkPathTracker]. Like Android, it starts as online
 * until the first report, which the monitor sends as soon as it starts.
 */
@OptIn(ExperimentalForeignApi::class)
class IosConnectivityMonitor : ConnectivityMonitor {

    private val paths: Flow<NetworkPath> = callbackFlow {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor) { path ->
            val names = mutableListOf<String>()
            nw_path_enumerate_interfaces(path) { iface ->
                nw_interface_get_name(iface)?.toKString()?.let { names += it }
                true
            }
            // Expensive: mobile data or a personal hotspot; constrained: Low Data Mode. Both count as metered for Wi-Fi only.
            val metered = nw_path_is_expensive(path) || nw_path_is_constrained(path)
            trySend(NetworkPath(satisfied = nw_path_get_status(path) == nw_path_status_satisfied, interfaces = names, metered = metered))
        }
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("com.chapterplayer.network", null))
        nw_path_monitor_start(monitor)
        awaitClose { nw_path_monitor_cancel(monitor) }
    }

    override val isOnline: Flow<Boolean> = paths.map { it.satisfied }.onStart { emit(true) }.distinctUntilChanged()

    /** From the monitor's own first report (no optimistic start), so a download never starts on a guess. */
    override val networkState: Flow<NetworkState> = paths.map { NetworkState(online = it.satisfied, metered = it.metered) }.distinctUntilChanged()

    override val currentNetwork: Flow<String?> = flow {
        val tracker = NetworkPathTracker()
        emitAll(paths.map { tracker.update(it) })
    }.distinctUntilChanged()
}

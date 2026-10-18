package com.chapterplayer.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Follows the default network with [ConnectivityManager.registerDefaultNetworkCallback].
 * The first value comes from the active network; a missing service or a failed query
 * leaves the flow at online instead of throwing.
 */
class AndroidConnectivityMonitor(context: Context) : ConnectivityMonitor {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override val isOnline: Flow<Boolean> = callbackFlow {
        trySend(initialValue())
        // The system sends onCapabilitiesChanged right after onAvailable, so only capabilities
        // (INTERNET, not validation) decide "online"; losing the network means offline.
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(
                    isOnline(hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                )
            }
            override fun onLost(network: Network) { trySend(false) }
        }
        val registered = try {
            manager?.registerDefaultNetworkCallback(callback)
            manager != null
        } catch (e: RuntimeException) { // SecurityException, or too many callbacks registered
            false
        }
        awaitClose {
            if (registered) {
                try { manager?.unregisterNetworkCallback(callback) } catch (_: RuntimeException) {}
            }
        }
    }.distinctUntilChanged()

    private fun initialValue(): Boolean {
        val m = manager
        var failed = false
        var caps: NetworkCapabilities? = null
        if (m != null) {
            try {
                caps = m.activeNetwork?.let { m.getNetworkCapabilities(it) }
            } catch (e: RuntimeException) {
                failed = true
            }
        }
        return initialOnline(
            managerAvailable = m != null,
            queryFailed = failed,
            networkPresent = caps != null,
            hasInternet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        )
    }
}

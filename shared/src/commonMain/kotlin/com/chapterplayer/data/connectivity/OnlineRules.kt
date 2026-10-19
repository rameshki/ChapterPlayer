package com.chapterplayer.data.connectivity

/**
 * A network counts as online when it has the INTERNET capability. It does not have to be validated:
 * self-hosted servers often live on a LAN with no route to the system's check servers, and a captive
 * portal only makes requests fail with visible errors.
 */
fun isOnline(hasInternet: Boolean): Boolean = hasInternet

/**
 * The first value before any callback arrives. A missing service or a failed query keeps "online"
 * (we cannot tell); with a service but no active network the device is offline.
 */
fun initialOnline(
    managerAvailable: Boolean,
    queryFailed: Boolean,
    networkPresent: Boolean,
    hasInternet: Boolean
): Boolean = when {
    !managerAvailable || queryFailed -> true
    !networkPresent -> false
    else -> isOnline(hasInternet)
}

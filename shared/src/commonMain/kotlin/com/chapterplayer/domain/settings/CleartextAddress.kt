package com.chapterplayer.domain.settings

import com.chapterplayer.data.auth.normalizeServerAddress

/*
 * Plain http is right for a home server, so the app allows it. It is not right for a public host: the password and the
 * token would travel unencrypted across the internet (review R11). These pure rules decide when to say so. They work on
 * what the person typed, through [normalizeServerAddress] (a missing scheme counts as http, as it does when connecting).
 */

/** How exposed an address is. */
enum class AddressExposure {
    /** https, or not an address yet: nothing to warn about. */
    ENCRYPTED,

    /** http inside a home or private network (private ranges, loopback, .local names, single-word names, Tailscale). */
    PRIVATE_NETWORK,

    /** http to a host that is on the public internet. */
    PUBLIC_CLEARTEXT,
}

/** See [AddressExposure]. */
fun addressExposure(input: String): AddressExposure {
    val normalized = normalizeServerAddress(input) ?: return AddressExposure.ENCRYPTED
    if (normalized.startsWith("https://", ignoreCase = true)) return AddressExposure.ENCRYPTED
    return if (isPrivateHost(hostOf(normalized))) AddressExposure.PRIVATE_NETWORK else AddressExposure.PUBLIC_CLEARTEXT
}

/** Whether the address is plain http (to any host): the Servers page says "Not encrypted". */
fun isCleartextAddress(input: String): Boolean = addressExposure(input) != AddressExposure.ENCRYPTED

/** Whether signing in to this address needs the person's confirmation: http to a public host. */
fun needsCleartextConfirmation(input: String): Boolean = addressExposure(input) == AddressExposure.PUBLIC_CLEARTEXT

/** The same address with https, for "Use https". Anything that is not an address is returned as typed. */
fun withHttps(input: String): String {
    val normalized = normalizeServerAddress(input) ?: return input
    return if (normalized.startsWith("http://", ignoreCase = true)) "https://" + normalized.substring("http://".length) else normalized
}

/** The host of a normalised address, lower case, without brackets, port or path. */
internal fun hostOf(normalized: String): String {
    val authority = normalized.substringAfter("://").substringBefore('/')
    val host = if (authority.startsWith("[")) {
        authority.substring(1, authority.indexOf(']').coerceAtLeast(1))
    } else {
        authority.substringBeforeLast(':', authority)
    }
    return host.lowercase()
}

private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".localdomain", ".home.arpa", ".intranet")

// Tailscale's MagicDNS names: the traffic between the devices is WireGuard encrypted already.
private const val TAILSCALE_SUFFIX = ".ts.net"

internal fun isPrivateHost(host: String): Boolean {
    if (host.isEmpty()) return false
    if (host.contains(':')) return isPrivateIpv6(host)
    ipv4Parts(host)?.let { return isPrivateIpv4(it) }
    if ('.' !in host) return true // one word: a name only the home network knows ("nas", "localhost")
    return LOCAL_SUFFIXES.any { host.endsWith(it) } || host.endsWith(TAILSCALE_SUFFIX)
}

private fun ipv4Parts(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    return if (numbers.all { it in 0..255 }) numbers else null
}

private fun isPrivateIpv4(p: List<Int>): Boolean = when {
    p[0] == 10 -> true
    p[0] == 127 -> true
    p[0] == 192 && p[1] == 168 -> true
    p[0] == 172 && p[1] in 16..31 -> true
    p[0] == 169 && p[1] == 254 -> true // link local
    p[0] == 100 && p[1] in 64..127 -> true // shared address space, which Tailscale uses
    else -> false
}

private fun isPrivateIpv6(host: String): Boolean {
    if (host == "::1") return true
    val first = host.substringBefore(':')
    // unique local fc00::/7, link local fe80::/10
    return first.startsWith("fc") || first.startsWith("fd") ||
        first.startsWith("fe8") || first.startsWith("fe9") || first.startsWith("fea") || first.startsWith("feb")
}

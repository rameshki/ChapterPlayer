package com.chapterplayer.data.security

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * The iOS [CredentialStore]: each secret is a generic-password Keychain item of the app's own service, its account the
 * key the app uses everywhere (the server id, or "<server id>.refresh" for an Audiobookshelf refresh token), its data the
 * token as UTF-8. Items are readable after the first unlock (so a background sync can use them) and never leave this
 * device (no iCloud Keychain, not in backups restored to another phone). A missing item is null; any other failed read
 * (errSecInteractionNotAllowed while the phone is locked, for one) throws [KeychainUnavailableException], so it is never
 * mistaken for "no token" (the server list reads it as empty for now and keeps the stored item). A failed write throws.
 * Keychain items outlive an uninstall, so a fresh install clears the old ones first ([clearAfterReinstall]).
 * Tokens are never logged or put in a message.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainCredentialStore(
    private val service: String = SERVICE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {

    // One Keychain change at a time, so an update-or-add of the same key never races itself.
    private val lock = Mutex()

    override suspend fun get(serverId: String): String? = withContext(io) {
        memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = withQuery(serverId, kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { query ->
                SecItemCopyMatching(query, result.ptr)
            }
            if (status == errSecItemNotFound) return@memScoped null
            if (status != errSecSuccess) throw KeychainUnavailableException(status)
            val data = CFBridgingRelease(result.value) as? NSData ?: return@memScoped null
            NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
        }
    }

    override suspend fun put(serverId: String, token: String) {
        lock.withLock {
            withContext(io) {
                val data = token.utf8() ?: error("The sign-in could not be stored")
                val value = CFBridgingRetain(data)
                try {
                    val updated = withQuery(serverId) { query ->
                        withDictionary(kSecValueData to value) { changes -> SecItemUpdate(query, changes) }
                    }
                    val status = if (updated == errSecItemNotFound) {
                        withQuery(serverId, kSecValueData to value, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) { item ->
                            SecItemAdd(item, null)
                        }
                    } else updated
                    check(status == errSecSuccess) { "The sign-in could not be stored (Keychain status $status)" }
                } finally {
                    CFRelease(value)
                }
            }
        }
    }

    override suspend fun remove(serverId: String) {
        lock.withLock {
            withContext(io) {
                val status = withQuery(serverId) { query -> SecItemDelete(query) }
                check(status == errSecSuccess || status == errSecItemNotFound) { "The sign-in could not be removed (Keychain status $status)" }
            }
        }
    }

    /**
     * Keychain items survive an uninstall, unlike the app's files: on the first launch of a fresh install (no marker in
     * the app's user defaults, which an uninstall removes) every item of [service] is deleted, then the marker is set.
     * Without this a reinstalled app would find tokens of servers its new, empty database no longer knows.
     */
    fun clearAfterReinstall(defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults) {
        if (defaults.boolForKey(INSTALLED_MARKER)) return
        val serviceRef = CFBridgingRetain(service)
        val status = try {
            withDictionary(kSecClass to kSecClassGenericPassword, kSecAttrService to serviceRef) { query -> SecItemDelete(query) }
        } finally {
            CFRelease(serviceRef)
        }
        // A failure is tried again on the next launch.
        if (status == errSecSuccess || status == errSecItemNotFound) defaults.setBool(true, forKey = INSTALLED_MARKER)
    }

    /** Runs [operation] with the query for [key]'s item (class, service, account) plus [extra] entries. */
    private fun withQuery(key: String, vararg extra: Pair<CFTypeRef?, CFTypeRef?>, operation: (CFDictionaryRef?) -> OSStatus): OSStatus {
        val serviceRef = CFBridgingRetain(service)
        val accountRef = CFBridgingRetain(key)
        try {
            return withDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to serviceRef,
                kSecAttrAccount to accountRef,
                *extra,
                block = operation,
            )
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
        }
    }

    /** A CoreFoundation dictionary of [entries] (retained by it, released after [block]). */
    private fun withDictionary(vararg entries: Pair<CFTypeRef?, CFTypeRef?>, block: (CFDictionaryRef?) -> OSStatus): OSStatus {
        val dictionary = CFDictionaryCreateMutable(null, entries.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            for ((key, value) in entries) CFDictionaryAddValue(dictionary, key, value)
            return block(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun String.utf8(): NSData? = (this as NSString).dataUsingEncoding(NSUTF8StringEncoding)

    companion object {
        /** The Keychain service every ChapterPlayer secret is filed under. */
        const val SERVICE = "com.chapterplayer.credentials"

        /** Set in user defaults once the Keychain was cleared for this install. */
        const val INSTALLED_MARKER = "credentials.install_marker"
    }
}

/** The Keychain answered with [status] (not "not found"): the item may be there but cannot be read now. */
class KeychainUnavailableException(val status: Int) : IllegalStateException("The Keychain cannot be read now (status $status)")

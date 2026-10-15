package com.chapterplayer.data.security

/** Turns a secret into text that is safe to store, and back. */
interface TokenCipher {
    fun encrypt(plain: String): String

    /** Throws [TokenCipherException] if [encoded] is damaged or was made with another key. */
    fun decrypt(encoded: String): String
}

class TokenCipherException(cause: Throwable? = null) : Exception("Could not decrypt the stored value", cause)

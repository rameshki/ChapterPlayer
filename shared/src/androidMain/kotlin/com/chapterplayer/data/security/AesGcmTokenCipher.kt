package com.chapterplayer.data.security

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/**
 * AES-256-GCM. The stored text is Base64 of a random 12-byte IV followed by the ciphertext
 * and its authentication tag, so damaged or edited text fails to decrypt.
 */
class AesGcmTokenCipher(private val key: () -> SecretKey) : TokenCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return Base64.encode(iv + cipher.doFinal(plain.encodeToByteArray()))
    }

    override fun decrypt(encoded: String): String {
        try {
            val data = Base64.decode(encoded)
            if (data.size <= IV_BYTES) throw TokenCipherException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES).decodeToString()
        } catch (e: TokenCipherException) {
            throw e
        } catch (e: Exception) { // bad Base64, bad data, wrong key, or the key supplier failing
            throw TokenCipherException(e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

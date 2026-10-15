package com.chapterplayer.data.security

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JwtTest {

    private fun jwt(payloadJson: String): String {
        val enc = { s: String -> Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(s.encodeToByteArray()) }
        return enc("""{"alg":"HS256","typ":"JWT"}""") + "." + enc(payloadJson) + ".signature"
    }

    @Test fun `reads exp`() = assertEquals(1_800_000_000L, jwtExpiryEpochSeconds(jwt("""{"userId":"u","exp":1800000000,"iat":1799996400}""")))
    @Test fun `no exp means no expiry`() = assertNull(jwtExpiryEpochSeconds(jwt("""{"keyId":"k","name":"n"}""")))
    @Test fun `exp that is not a number is ignored`() = assertNull(jwtExpiryEpochSeconds(jwt("""{"exp":"soon"}""")))
    @Test fun `not a jwt`() {
        assertNull(jwtExpiryEpochSeconds(""))
        assertNull(jwtExpiryEpochSeconds("plain-token"))
        assertNull(jwtExpiryEpochSeconds("a.b.c"))
        assertNull(jwtExpiryEpochSeconds("a.%%%.c"))
    }
    @Test fun `payload that is not json`() = assertNull(jwtExpiryEpochSeconds(jwt("not json")))
    @Test fun `payload that is a json array`() = assertNull(jwtExpiryEpochSeconds(jwt("[1,2]")))
}

package com.chapterplayer.data.security

import kotlin.io.encoding.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

private val lenientBase64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/** The expiry (`exp`, seconds since 1970) a JWT carries, or null if it has none or is not a JWT. Never throws. */
fun jwtExpiryEpochSeconds(token: String): Long? = try {
    val payload = token.split('.').getOrNull(1) ?: return null
    val json = Json.parseToJsonElement(lenientBase64.decode(payload).decodeToString())
    ((json as? JsonObject)?.get("exp") as? JsonPrimitive)?.longOrNull
} catch (_: Exception) {
    null
}

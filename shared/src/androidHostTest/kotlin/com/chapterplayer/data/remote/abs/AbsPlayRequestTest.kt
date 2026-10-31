package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.abs.dto.AbsPlayerProfile
import com.chapterplayer.data.remote.installApiDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The body of `POST /api/items/{id}/play` per platform: Android's is the one it always sent. */
class AbsPlayRequestTest {

    private suspend fun sentBody(profile: AbsPlayerProfile?): JsonObject {
        var body = ""
        val engine = MockEngine { request ->
            body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond("""{"id":"s","duration":1.0}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine) { installApiDefaults(isDebuggable = false, sink = {}) }
        val api = if (profile == null) AbsApiService(client) else AbsApiService(client, profile)
        api.startPlayback("https://abs.example.test", "Bearer t", "item")
        return Json.parseToJsonElement(body).jsonObject
    }

    @Test
    fun `Android asks for direct play of every file type with ExoPlayer exactly as before`() = runTest {
        val json = sentBody(null)
        assertEquals(setOf("deviceInfo", "forceDirectPlay", "forceTranscode", "supportedMimeTypes", "mediaPlayer"), json.keys)
        assertEquals(setOf("clientName", "deviceId"), json["deviceInfo"]!!.jsonObject.keys)
        assertEquals("ChapterPlayer", json["deviceInfo"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
        assertEquals("true", json["forceDirectPlay"]!!.jsonPrimitive.content)
        assertEquals("false", json["forceTranscode"]!!.jsonPrimitive.content)
        assertEquals("exoplayer", json["mediaPlayer"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("audio/mpeg", "audio/mp4", "audio/flac", "audio/ogg", "audio/aac", "audio/x-m4b", "audio/m4b", "audio/x-m4a"),
            json["supportedMimeTypes"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `iOS lets the server choose and never claims Ogg so those books come as HLS`() = runTest {
        val json = sentBody(AbsPlayerProfile.Ios)
        assertEquals("false", json["forceDirectPlay"]!!.jsonPrimitive.content)
        assertEquals("false", json["forceTranscode"]!!.jsonPrimitive.content)
        assertEquals("AVPlayer", json["mediaPlayer"]!!.jsonPrimitive.content)
        val types = json["supportedMimeTypes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertFalse(types.any { "ogg" in it || "opus" in it || "webm" in it })
        assertEquals(true, "audio/mpeg" in types && "audio/mp4" in types && "audio/x-m4b" in types && "audio/flac" in types)
        assertEquals("Apple", json["deviceInfo"]!!.jsonObject["manufacturer"]!!.jsonPrimitive.content)
    }
}

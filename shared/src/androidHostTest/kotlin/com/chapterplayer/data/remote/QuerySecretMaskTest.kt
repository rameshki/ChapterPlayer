package com.chapterplayer.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuerySecretMaskTest {
    private val secret = "feedfacefeedfacefeedfacefeedface"

    @Test
    fun `a secret in the query is masked and the rest of the line is kept`() {
        assertEquals(
            "REQUEST: https://jf.test/QuickConnect/Connect?secret=[removed]",
            maskQuerySecrets("REQUEST: https://jf.test/QuickConnect/Connect?secret=$secret"),
        )
        assertEquals(
            "GET https://x.test/a?b=1&token=[removed]&c=2 done",
            maskQuerySecrets("GET https://x.test/a?b=1&token=abc123&c=2 done"),
        )
        assertEquals("https://x.test/a?SECRET=[removed]", maskQuerySecrets("https://x.test/a?SECRET=abc"))
        assertEquals("?api_key=[removed]&apikey=[removed]&access_token=[removed]", maskQuerySecrets("?api_key=1&apikey=2&access_token=3"))
    }

    @Test
    fun `lines without a secret are untouched`() {
        for (line in listOf("REQUEST: https://jf.test/Items?ParentId=abc&Limit=100", "secrets are fine in words", "https://jf.test/secret/path", "")) {
            assertEquals(line, maskQuerySecrets(line))
        }
    }

    @Test
    fun `the debug logger never prints a Quick Connect secret`() = runTest {
        val lines = mutableListOf<String>()
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) {
            installApiDefaults(isDebuggable = true, sink = { lines += it })
        }
        client.get("https://jf.test/QuickConnect/Connect?secret=$secret")
        assertTrue(lines.any { "QuickConnect/Connect" in it }, "the request line is logged: $lines")
        assertFalse(lines.any { secret in it }, lines.toString())
    }
}

package com.chapterplayer.data.remote.abs

import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.installApiDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AbsApiServiceTest {

    private val requests = mutableListOf<HttpRequestData>()

    private fun service(body: String, status: HttpStatusCode = HttpStatusCode.OK) = AbsApiService(
        HttpClient(
            MockEngine { request ->
                requests += request
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { installApiDefaults(isDebuggable = false) }
    )

    @Test
    fun `lists libraries with the bearer token`() = runTest {
        val libraries = service(fixture("abs/libraries.json")).getLibraries("http://abs.test:13378", "Bearer abc")

        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("http://abs.test:13378/api/libraries", request.url.toString())
        assertEquals("Bearer abc", request.headers[HttpHeaders.Authorization])
        assertEquals(3, libraries.libraries.size)
    }

    @Test
    fun `a trailing slash on the server address makes no difference`() = runTest {
        service(fixture("abs/libraries.json")).getLibraries("http://abs.test/", "Bearer abc")

        assertEquals("http://abs.test/api/libraries", requests.single().url.toString())
    }

    @Test
    fun `asks for one page of items with progress`() = runTest {
        val page = service(fixture("abs/items-page-with-total.json"))
            .getLibraryItems("http://abs.test", "Bearer abc", libraryId = "lib_1", limit = 100, page = 2)

        val url = requests.single().url
        assertEquals("/api/libraries/lib_1/items", url.encodedPath)
        assertEquals("100", url.parameters["limit"])
        assertEquals("2", url.parameters["page"])
        assertEquals("progress", url.parameters["include"])
        assertEquals("Bearer abc", requests.single().headers[HttpHeaders.Authorization])
        assertEquals(7, page.results.size)
    }

    @Test
    fun `a rejected token fails with the http status`() = runTest {
        val error = assertFailsWith<HttpStatusException> {
            service("Unauthorized", HttpStatusCode.Unauthorized).getLibraries("http://abs.test", "Bearer bad")
        }

        assertEquals("HTTP 401 Unauthorized", error.message)
    }
}

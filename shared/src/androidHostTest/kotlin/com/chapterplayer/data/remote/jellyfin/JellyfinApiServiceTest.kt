package com.chapterplayer.data.remote.jellyfin

import com.chapterplayer.data.remote.ApiJson
import com.chapterplayer.data.remote.abs.fixture
import com.chapterplayer.data.remote.installApiDefaults
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinAuthResultDto
import com.chapterplayer.data.remote.jellyfin.dto.JellyfinItemsPageDto
import com.chapterplayer.domain.jellyfin.JellyfinClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinApiServiceTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val token = "a6f98b2a08d92285b19af86a9503bf50"
    private val deviceId = "chapterplayer-client-1-row-1"

    /** Answers each path with [routes] (path suffix to fixture text); anything else is a 404. */
    private fun service(routes: (HttpRequestData) -> String?, status: HttpStatusCode = HttpStatusCode.OK) = JellyfinApiService(
        HttpClient(
            MockEngine { request ->
                requests += request
                val body = routes(request)
                if (body == null) respond("", HttpStatusCode.NotFound)
                else respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { installApiDefaults(isDebuggable = false) },
        clientInfo = { id -> JellyfinClient("Android", id, "0.4.1") },
    )

    private fun one(name: String) = service({ fixture("jellyfin/$name") })

    private fun bodyText(content: OutgoingContent): String = when (content) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> error("unexpected body type ${content::class}")
    }

    @Test
    fun `sign in posts the fixed body with a header and no token`() = runTest {
        val result = one("authenticate-by-name.json")
            .authenticateByName("https://jf.test", deviceId, username = "u", password = "p")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/Users/AuthenticateByName", request.url.encodedPath)
        assertEquals("""{"Username":"u","Pw":"p"}""", bodyText(request.body))
        val auth = request.headers[HttpHeaders.Authorization].orEmpty()
        assertTrue(auth.startsWith("MediaBrowser "), auth)
        assertFalse(auth.contains("Token="), auth)
        assertTrue(auth.contains("DeviceId=\"$deviceId\""), auth)
        assertEquals(token, result.accessToken)
        assertEquals("36b7674f7149d16771400fae9b9eb1f9", result.serverId)
        assertEquals("52d8fc64326c7e0848c4f143abb47ba1", result.user?.id)
    }

    @Test
    fun `public info is asked without any auth header`() = runTest {
        val info = one("system-info-public.json").getPublicInfo("https://jf.test")
        val request = requests.single()
        assertEquals("/System/Info/Public", request.url.encodedPath)
        assertNull(request.headers[HttpHeaders.Authorization])
        assertEquals("Jellyfin Server", info.productName)
        assertEquals("12.4.0", info.version)
        assertEquals("36b7674f7149d16771400fae9b9eb1f9", info.id)
    }

    @Test
    fun `authorized calls send the token in the header`() = runTest {
        val views = one("user-views.json").getUserViews("https://jf.test", deviceId, token)
        val request = requests.single()
        assertEquals("/UserViews", request.url.encodedPath)
        assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().endsWith("Token=\"$token\""))
        assertEquals(listOf("books", "music", "movies"), views.items.map { it.collectionType })
    }

    @Test
    fun `list call has the fixed query`() = runTest {
        val page = one("items-audiobooks-page-1.json")
            .getAudiobooksPage("https://jf.test", deviceId, token, viewId = "40fd54c7fab6b3a985f36b461a437081", startIndex = 0)

        val url = requests.single().url
        assertEquals("/Items", url.encodedPath)
        val expected = mapOf(
            "ParentId" to "40fd54c7fab6b3a985f36b461a437081",
            "IncludeItemTypes" to "AudioBook",
            "Recursive" to "true",
            "Fields" to "People,Overview,Genres,DateCreated,ProviderIds",
            "EnableUserData" to "true",
            "EnableImageTypes" to "Primary",
            "StartIndex" to "0",
            "Limit" to "200",
            "EnableTotalRecordCount" to "true",
        )
        assertEquals(expected, url.parameters.names().associateWith { url.parameters.getAll(it)!!.single() })
        assertEquals(3, page.totalRecordCount)
        assertEquals(2, page.items.size)
    }

    @Test
    fun `detail call has the fixed fields`() = runTest {
        val item = one("item-detail-media-sources.json")
            .getItem("https://jf.test", deviceId, token, itemId = "0bdd952d73af33d8d4421dfdb2f25919")

        val url = requests.single().url
        assertEquals("/Items/0bdd952d73af33d8d4421dfdb2f25919", url.encodedPath)
        assertEquals(mapOf("Fields" to "Chapters,People,Overview,Genres,Studios,MediaSources"),
            url.parameters.names().associateWith { url.parameters.getAll(it)!!.single() })
        assertEquals("AudioBook", item.type)
        assertEquals(360_000_000_000L, item.runTimeTicks)
        assertEquals("mov,mp4,m4a,3gp,3g2,mj2", item.mediaSources.single().container)
        assertEquals(4, item.chapters.size)
        assertEquals(listOf("Author" to "Ada Example", "Narrator" to "Ned Reader"), item.people.map { it.type to it.name })
        assertEquals("Example Press", item.studios.single().name)
        assertEquals("7d1c2b3a", item.imageTags["Primary"])
    }

    @Test
    fun `user data is read from the user items route`() = runTest {
        val data = one("user-data-in-progress.json")
            .getUserData("https://jf.test", deviceId, token, itemId = "0bdd952d73af33d8d4421dfdb2f25919")
        assertEquals("/UserItems/0bdd952d73af33d8d4421dfdb2f25919/UserData", requests.single().url.encodedPath)
        assertEquals(13_500_000_000L, data.playbackPositionTicks)
        assertEquals(false, data.played)
        assertEquals("2026-10-20T08:30:00.0000000Z", data.lastPlayedDate)
    }

    @Test
    fun `paging follows TotalRecordCount`() = runTest {
        val api = service({ r ->
            when (r.url.parameters["StartIndex"]) {
                "0" -> fixture("jellyfin/items-audiobooks-page-1.json")
                "2" -> fixture("jellyfin/items-audiobooks-page-2.json")
                else -> null
            }
        })
        val items = fetchAllAudiobooks(api, "https://jf.test", deviceId, token, viewId = "40fd54c7fab6b3a985f36b461a437081")

        assertEquals(3, items.size)
        assertEquals(listOf("0", "2"), requests.map { it.url.parameters["StartIndex"] })
    }

    @Test
    fun `paging stops on an empty page`() = runTest {
        val api = service({ """{"Items":[],"TotalRecordCount":50,"StartIndex":0}""" })
        assertTrue(fetchAllAudiobooks(api, "https://jf.test", deviceId, token, viewId = "v").isEmpty())
        assertEquals(1, requests.size)
    }

    @Test
    fun `paging gives up on a server that never ends`() = runTest {
        var n = 0
        val api = service({ """{"Items":[{"Id":"id${n++}","Type":"AudioBook"}],"TotalRecordCount":100000}""" })
        assertFailsWith<IllegalStateException> { fetchAllAudiobooks(api, "https://jf.test", deviceId, token, viewId = "v") }
        assertEquals(JELLYFIN_MAX_PAGES, requests.size)
    }

    @Test
    fun `public info larger than 64 KB is refused`() = runTest {
        val huge = "{\"ProductName\":\"Jellyfin Server\",\"Padding\":\"" + "x".repeat(100_000) + "\"}"
        val api = service({ huge })
        assertFailsWith<IllegalStateException> { api.getPublicInfo("https://jf.test") }
        // Just under the cap still reads.
        val fine = "{\"ProductName\":\"Jellyfin Server\",\"Version\":\"12.4.0\",\"Id\":\"x\",\"Padding\":\"" + "x".repeat(60_000) + "\"}"
        assertEquals("12.4.0", service({ fine }).getPublicInfo("https://jf.test").version)
    }

    @Test
    fun `public info stops reading at 64 KB even when the answer never ends`() {
        val chunk = ByteArray(8 * 1024) { 'x'.code.toByte() }
        val endless = JellyfinApiService(
            HttpClient(MockEngine {
                val body = kotlinx.coroutines.GlobalScope.writer(kotlinx.coroutines.Dispatchers.IO) {
                    channel.writeFully("{\"ProductName\":\"".encodeToByteArray())
                    while (true) channel.writeFully(chunk)
                }.channel
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }) { installApiDefaults(isDebuggable = false) },
        ) { JellyfinClient("Android", it, "0.4.1") }
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeout(10_000) {
                assertFailsWith<IllegalStateException> { endless.getPublicInfo("https://jf.test") }
            }
        }
    }

    @Test
    fun `a non-2xx answer throws`() = runTest {
        val api = service({ "{}" }, status = HttpStatusCode.Unauthorized)
        val e = assertFailsWith<Exception> { api.getUserViews("https://jf.test", deviceId, token) }
        assertEquals("HttpStatusException", e::class.simpleName)
    }

    @Test
    fun `address with a path keeps it in every call`() = runTest {
        for (base in listOf("https://jf.test/jellyfin", "https://jf.test/jellyfin/")) {
            requests.clear()
            val api = service({ r ->
                val p = r.url.encodedPath
                when {
                    p.endsWith("/System/Info/Public") -> fixture("jellyfin/system-info-public.json")
                    p.endsWith("/Users/AuthenticateByName") -> fixture("jellyfin/authenticate-by-name.json")
                    p.endsWith("/UserViews") -> fixture("jellyfin/user-views.json")
                    p.endsWith("/UserData") -> fixture("jellyfin/user-data-zero.json")
                    p.endsWith("/Items") -> fixture("jellyfin/items-audiobooks-page-2.json")
                    else -> fixture("jellyfin/item-detail-chapters.json")
                }
            })
            api.getPublicInfo(base)
            api.authenticateByName(base, deviceId, "u", "p")
            api.getUserViews(base, deviceId, token)
            api.getAudiobooksPage(base, deviceId, token, "v1", 0)
            api.getItem(base, deviceId, token, "i1")
            api.getUserData(base, deviceId, token, "i1")

            assertEquals(
                listOf("/jellyfin/System/Info/Public", "/jellyfin/Users/AuthenticateByName", "/jellyfin/UserViews",
                    "/jellyfin/Items", "/jellyfin/Items/i1", "/jellyfin/UserItems/i1/UserData"),
                requests.map { it.url.encodedPath },
            )
            assertTrue(requests.none { it.url.toString().substringAfter("://").contains("//") })
            assertTrue(requests.all { it.url.host == "jf.test" })
        }
    }

    @Test
    fun `no request carries a token in its url`() = runTest {
        val api = service({ r ->
            val p = r.url.encodedPath
            when {
                p.endsWith("/System/Info/Public") -> fixture("jellyfin/system-info-public.json")
                p.endsWith("/Users/AuthenticateByName") -> fixture("jellyfin/authenticate-by-name.json")
                p.endsWith("/UserViews") -> fixture("jellyfin/user-views.json")
                p.endsWith("/UserData") -> fixture("jellyfin/user-data-played.json")
                p.endsWith("/Items") -> fixture("jellyfin/items-audiobooks-page-1.json")
                else -> fixture("jellyfin/item-detail-ogg.json")
            }
        })
        api.getPublicInfo("https://jf.test")
        api.authenticateByName("https://jf.test", deviceId, "u", "p")
        api.getUserViews("https://jf.test", deviceId, token)
        api.getAudiobooksPage("https://jf.test", deviceId, token, "v1", 0)
        api.getItem("https://jf.test", deviceId, token, "i1")
        api.getUserData("https://jf.test", deviceId, token, "i1")

        assertEquals(6, requests.size)
        for (r in requests) {
            val names = r.url.parameters.names().map { it.lowercase() }
            assertFalse(names.any { it == "api_key" || it == "apikey" || it == "token" }, r.url.toString())
            assertFalse(r.url.toString().contains(token), r.url.toString())
            assertTrue(r.headers.names().none { it.startsWith("X-Emby", ignoreCase = true) })
        }
    }

    @Test
    fun `auth result toString hides the token`() {
        val result = ApiJson.decodeFromString<JellyfinAuthResultDto>(fixture("jellyfin/authenticate-by-name.json"))
        assertEquals(token, result.accessToken)
        assertFalse(result.toString().contains(token), result.toString())
    }

    @Test
    fun `fixtures parse`() {
        val old = ApiJson.decodeFromString<JellyfinItemsPageDto>(fixture("jellyfin/items-audiobooks-10-10.json"))
        assertTrue(old.items.single().people.isEmpty())
        assertEquals("Eve Placeholder", old.items.single().albumArtist)
        val parts = ApiJson.decodeFromString<JellyfinItemsPageDto>(fixture("jellyfin/items-folder-of-parts.json"))
        assertEquals(2, parts.items.size)
        assertEquals(listOf(1, 2), parts.items.map { it.indexNumber })
        val page2 = ApiJson.decodeFromString<JellyfinItemsPageDto>(fixture("jellyfin/items-audiobooks-page-2.json"))
        assertEquals(true, page2.items.single().userData?.played)
        // A bare item with nothing but an id still parses (every field is optional).
        val bare = ApiJson.decodeFromString<JellyfinItemsPageDto>("""{"Items":[{"Id":"x"}]}""")
        assertNull(bare.items.single().runTimeTicks)
        assertTrue(bare.items.single().mediaSources.isEmpty())
        assertNull(bare.totalRecordCount)
    }
}

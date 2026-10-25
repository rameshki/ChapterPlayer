package com.chapterplayer.data.playback

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.HttpStatusException
import com.chapterplayer.data.remote.abs.AbsAuthorizer
import com.chapterplayer.data.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The opener behind the player's data source, with a fake HTTP source underneath. */
class OpenStreamTest {

    private class Servers(private val list: List<ServerConfig>) : ServerRepository {
        override fun observeAll(): Flow<List<ServerConfig>> = flowOf(list)
        override suspend fun getById(id: String) = list.firstOrNull { it.id == id }
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
        override suspend fun setLibraryEnabled(serverId: String, libraryId: String, enabled: Boolean) {}
    }

    /** Hands out "Bearer first", and "Bearer second" after the call failed with a 401, like a refresh. */
    private object RetryOnce : AbsAuthorizer {
        override suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T =
            try {
                call("Bearer first")
            } catch (e: HttpStatusException) {
                if (e.status.value != 401) throw e
                call("Bearer second")
            }
    }

    private class Unauthorized : IOException("401")

    /** A 3xx answer pointing at [location]. */
    private class Redirect(val location: String) : IOException("302")

    private class FakeUpstream(
        private val answers: MutableList<() -> Long>,
        var answeredFrom: String? = null,
    ) : Upstream {
        val authorizations = mutableListOf<String?>()
        val headerNames = mutableListOf<String?>()
        val urls = mutableListOf<String>()
        var closes = 0
        override fun open(url: String, header: AuthHeader?): Long {
            urls += url
            authorizations += header?.value
            headerNames += header?.name
            return answers.removeAt(0)()
        }
        override fun close() { closes++ }
        // Without a fixed answer, the address that was opened answered (no redirect is followed underneath).
        override fun resolvedUrl() = answeredFrom ?: urls.lastOrNull()
        override fun isUnauthorized(e: Exception) = e is Unauthorized
        override fun redirectLocation(e: Exception) = (e as? Redirect)?.location
    }

    private val servers = Servers(
        listOf(ServerConfig("s", ServerSource.ABS, "S", "http://abs.test", "tok", refreshToken = "r"))
    )
    private val url = "http://abs.test/api/items/1/file/2"

    @Test
    fun `the authorization header is passed up for a configured server`() {
        val up = FakeUpstream(mutableListOf({ 42L }), answeredFrom = url)
        assertEquals(42L, openStream(url, servers, RetryOnce, up))
        assertEquals(listOf<String?>("Bearer first"), up.authorizations)
        assertEquals(listOf<String?>("Authorization"), up.headerNames)
        assertEquals(0, up.closes)
    }

    @Test
    fun `a plex stream gets the token header and is refused when answered from another host`() {
        val plex = Servers(listOf(ServerConfig("p", ServerSource.PLEX, "P", "http://plex.test:32400", "plextok")))
        val part = "http://plex.test:32400/library/parts/1/1700000000/file.mp3"
        val up = FakeUpstream(mutableListOf({ 3L }), answeredFrom = part)
        assertEquals(3L, openStream(part, plex, RetryOnce, up))
        assertEquals(listOf<String?>("X-Plex-Token"), up.headerNames)
        assertEquals(listOf<String?>("plextok"), up.authorizations)

        val moved = FakeUpstream(mutableListOf({ 3L }), answeredFrom = "http://evil.test/file.mp3")
        assertFailsWith<UnsafeRedirectException> { openStream(part, plex, RetryOnce, moved) }
        assertEquals(1, moved.closes)
    }

    @Test
    fun `the auth header never shows its value`() {
        assertFalse("plextok" in AuthHeader("X-Plex-Token", "plextok").toString())
    }

    @Test
    fun `an unknown address is opened without a header`() {
        val up = FakeUpstream(mutableListOf({ 1L }), answeredFrom = "http://elsewhere.test/x")
        assertEquals(1L, openStream("http://elsewhere.test/x", servers, RetryOnce, up))
        assertEquals(listOf<String?>(null), up.authorizations)
    }

    @Test
    fun `a 401 closes the upstream and retries once with the next token`() {
        val up = FakeUpstream(mutableListOf({ throw Unauthorized() }, { 7L }), answeredFrom = url)
        assertEquals(7L, openStream(url, servers, RetryOnce, up))
        assertEquals(listOf<String?>("Bearer first", "Bearer second"), up.authorizations)
        assertEquals(1, up.closes)
    }

    @Test
    fun `another failure closes the upstream and is not retried`() {
        val up = FakeUpstream(mutableListOf({ throw IOException("boom") }))
        assertFailsWith<IOException> { openStream(url, servers, RetryOnce, up) }
        assertEquals(1, up.authorizations.size)
        assertEquals(1, up.closes)
    }

    @Test
    fun `an answer from another host is closed and refused with a safe message`() {
        val up = FakeUpstream(mutableListOf({ 5L }), answeredFrom = "http://evil.test/audio")
        val e = assertFailsWith<UnsafeRedirectException> { openStream(url, servers, RetryOnce, up) }
        assertEquals(1, up.closes)
        assertFalse("evil" in e.message.orEmpty() || "abs.test" in e.message.orEmpty() || "tok" in e.message.orEmpty())
    }

    @Test
    fun `a redirect within the same host is fine, whatever the case`() {
        val up = FakeUpstream(mutableListOf({ 5L }), answeredFrom = "HTTP://ABS.test/other/path?x=1")
        assertEquals(5L, openStream(url, servers, RetryOnce, up))
        assertEquals(0, up.closes)
    }

    @Test
    fun `same host compares scheme host and port only`() {
        assertTrue(sameHost("http://a.test:80/x", "http://A.TEST:80/y"))
        assertFalse(sameHost("http://a.test/x", "https://a.test/x"))
        assertFalse(sameHost("http://a.test:1/x", "http://a.test:2/x"))
        assertFalse(sameHost("http://a.test/x", "http://a.test.evil/x"))
    }

    @Test
    fun `sign-in failures are recognised from what arrives over the session connection`() {
        val text = "Sign in again in Settings"
        // The cause arrives as a plain exception that only has the original's class name and message.
        val chain = listOf(
            ErrorLink("androidx.media3.common.PlaybackException", "Source error"),
            ErrorLink("androidx.media3.exoplayer.upstream.Loader\$UnexpectedLoaderException", "Unexpected SignInRequiredException: $text"),
        )
        assertEquals(text, describeError(2000, chain))
        assertEquals(text, describeError(1000, listOf(ErrorLink("com.chapterplayer.data.auth.SignInRequiredException", null))))
        assertEquals(text, describeError(2004, listOf(ErrorLink("x", "Response code: 401"))))
        assertEquals("The audio was not found on the server", describeError(2004, listOf(ErrorLink("x", "Response code: 404"))))
        assertEquals("The audio was not found on the server", describeError(2004, listOf(ErrorLink("x", null, 404))))
        assertEquals("Playback failed", describeError(1000, listOf(ErrorLink("x", "Something http://secret.example broke"))))
        assertEquals("Cannot reach the server", describeError(2001, emptyList()))
    }

    @Test
    fun `the marker is the real exception message`() {
        assertEquals(SIGN_IN_AGAIN_TEXT, com.chapterplayer.data.auth.SignInRequiredException("x").message)
    }

    // -- Redirects ------------------------------------------------------------------------------

    private val plex = Servers(listOf(ServerConfig("p", ServerSource.PLEX, "P", "http://plex.test:32400", "plextok")))
    private val part = "http://plex.test:32400/library/parts/1/1700000000/file.mp3"

    @Test
    fun `a redirect within the server is followed by hand with the token`() {
        val up = FakeUpstream(mutableListOf({ throw Redirect("/library/parts/1/moved.mp3") }, { 9L }))
        assertEquals(9L, openStream(part, plex, RetryOnce, up))
        assertEquals(listOf(part, "http://plex.test:32400/library/parts/1/moved.mp3"), up.urls)
        assertEquals(listOf<String?>("plextok", "plextok"), up.authorizations)
    }

    @Test
    fun `a redirect to another host is never sent the token`() {
        val up = FakeUpstream(mutableListOf({ throw Redirect("http://evil.test/file.mp3") }, { 9L }))
        assertFailsWith<UnsafeRedirectException> { openStream(part, plex, RetryOnce, up) }
        assertEquals(listOf(part), up.urls)
        val bearer = FakeUpstream(mutableListOf({ throw Redirect("http://evil.test/file.mp3") }, { 9L }))
        assertFailsWith<UnsafeRedirectException> { openStream(url, servers, RetryOnce, bearer) }
        assertEquals(listOf(url), bearer.urls)
    }

    @Test
    fun `an address of no server may be redirected elsewhere, without a header`() {
        val up = FakeUpstream(mutableListOf({ throw Redirect("http://cdn.test/a.mp3") }, { 4L }))
        assertEquals(4L, openStream("http://free.test/a.mp3", servers, RetryOnce, up))
        assertEquals(listOf<String?>(null, null), up.authorizations)
    }

    @Test
    fun `at most five redirects are followed`() {
        val loops = MutableList<() -> Long>(6) { { throw Redirect("/again") } }
        val up = FakeUpstream(loops)
        assertFailsWith<UnsafeRedirectException> { openStream(part, plex, RetryOnce, up) }
        assertEquals(6, up.urls.size)
    }

    @Test
    fun `the redirect decision`() {
        assertEquals("http://h.test/b", redirectTarget("http://h.test/a", "/b", hop = 1, withToken = true))
        assertEquals("http://h.test/dir/c", redirectTarget("http://h.test/dir/a", "c", hop = 1, withToken = true))
        assertEquals("https://h.test/b", redirectTarget("http://h.test/a", "https://h.test/b", hop = 1, withToken = false))
        // Another origin with a token: refused; without one: followed.
        assertNull(redirectTarget("http://h.test/a", "http://o.test/b", hop = 1, withToken = true))
        assertNull(redirectTarget("http://h.test:1/a", "http://h.test:2/b", hop = 1, withToken = true))
        assertEquals("http://o.test/b", redirectTarget("http://h.test/a", "http://o.test/b", hop = 1, withToken = false))
        // Never from https to http, never another scheme, never past five.
        assertNull(redirectTarget("https://h.test/a", "http://h.test/b", hop = 1, withToken = false))
        assertNull(redirectTarget("http://h.test/a", "ftp://h.test/b", hop = 1, withToken = false))
        assertNull(redirectTarget("http://h.test/a", "/b", hop = 6, withToken = false))
        assertEquals("http://h.test/b", redirectTarget("http://h.test/a", "/b", hop = 5, withToken = false))
    }
}

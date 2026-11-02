package com.chapterplayer.domain.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {

    private fun assertGone(text: String, vararg secrets: String) {
        val out = redact(text)
        for (s in secrets) assertFalse(out.contains(s), "'$s' survived in: $out")
    }

    @Test fun `a bearer token and an authorization header are removed`() {
        assertGone(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiJhYmMifQ.c2lnbmF0dXJlMTIz",
            "eyJhbGci", "c2lnbmF0dXJl"
        )
        assertGone("sent header authorization=Bearer abcDEF123456", "abcDEF123456")
        assertTrue(redact("Authorization: Bearer abc.def.ghi").contains("Authorization: [removed]"))
        assertGone("calling with Bearer q1w2e3r4t5y6u7i8o9p0", "q1w2e3r4t5y6u7i8o9p0")
    }

    @Test fun `a plex token header and query values are removed`() {
        assertGone("X-Plex-Token: sD3fG9hJkL2mN5pQ7rT1", "sD3fG9hJkL2mN5pQ7rT1")
        assertGone("failed with X-Plex-Token=sD3fG9hJkL2mN5pQ7rT1&other=1", "sD3fG9hJkL2mN5pQ7rT1")
        assertGone("token=Zx81qPlm20", "Zx81qPlm20")
        assertGone("""{"password": "hunter2hunter2", "user":"a"}""", "hunter2hunter2")
        assertGone("api_key = 12345abcde", "12345abcde")
    }

    @Test fun `a bare plex style token is removed by shape`() {
        assertGone("the server said sD3fG9hJkL2mN5pQ7rT1 is wrong", "sD3fG9hJkL2mN5pQ7rT1")
    }

    @Test fun `urls with query tokens are removed whole`() {
        val out = redact("GET https://plex.example.org:32400/library/metadata/48213/children?X-Plex-Token=sD3fG9hJkL2mN5pQ7rT1 failed")
        assertEquals("GET [address] failed", out)
        assertGone("see http://192.168.1.20:13378/api/items/li_abc/play?token=abc123", "192.168.1.20", "api/items", "abc123")
    }

    @Test fun `ipv4 addresses with and without a port are removed`() {
        assertEquals("server at [address] and [address] ok", redact("server at 192.168.1.20 and 10.0.0.5:32400 ok"))
    }

    @Test fun `ipv6 addresses are removed but clock times stay`() {
        assertGone("local fe80::1ff:fe23:4567:890a and 2001:db8:85a3:0:0:8a2e:370:7334", "fe80", "2001:db8", "8a2e")
        assertEquals("position 1:02:03 and 14:03:05", redact("position 1:02:03 and 14:03:05"))
        assertEquals("loopback [address]", redact("loopback ::1"))
    }

    @Test fun `host names are removed`() {
        assertGone("could not resolve my-server.example.com", "my-server", "example.com")
        assertGone("host 192-168-1-20.0123456789abcdef0123456789abcdef.plex.direct unreachable", "plex.direct", "192-168-1-20")
        assertGone("audio.home.lan down", "home.lan")
    }

    @Test fun `file names and plain text are not taken for hosts`() {
        assertEquals("files chapter01.mp3, part2.m4b, cover.jpg", redact("files chapter01.mp3, part2.m4b, cover.jpg"))
        assertEquals("1.5 MB of 12.3 MB", redact("1.5 MB of 12.3 MB"))
        assertEquals("Version 0.3.3 ready", redact("Version 0.3.3 ready"))
    }

    @Test fun `machine ids and uuids are removed`() {
        assertGone("machine 0123456789abcdef0123456789abcdef01234567 here", "0123456789abcdef")
        assertGone("id 123e4567-e89b-12d3-a456-426614174000 done", "123e4567")
    }

    @Test fun `the users own book ids can be kept`() {
        val ids = listOf("li_8f3k2m9x1q7w4e6r5t0y", "plex_48213")
        val out = redact("Book id: li_8f3k2m9x1q7w4e6r5t0y and plex_48213 with sD3fG9hJkL2mN5pQ7rT1", keep = ids)
        assertTrue(out.contains("li_8f3k2m9x1q7w4e6r5t0y"))
        assertTrue(out.contains("plex_48213"))
        assertFalse(out.contains("sD3fG9hJkL2mN5pQ7rT1"))
    }

    @Test fun `ordinary report text is untouched`() {
        val text = "Local progress: position 1:02:03 (3723 s) of 10:00:00, finished no, mirrored no"
        assertEquals(text, redact(text))
        assertEquals("Title: The Long Way Home by A. N. Author", redact("Title: The Long Way Home by A. N. Author"))
    }
}

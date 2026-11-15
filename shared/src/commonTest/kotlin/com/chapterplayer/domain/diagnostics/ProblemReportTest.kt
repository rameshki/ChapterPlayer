package com.chapterplayer.domain.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProblemReportTest {

    private fun input(
        crash: String? = null,
        log: List<String> = emptyList(),
        servers: List<ProblemServerLine> = listOf(ProblemServerLine("Audiobookshelf", true, "last sync fine, 120 books")),
    ) = ProblemReportInput("0.5.3", "Android 15 (API 35), Maker Model", servers, 2, crash, log)

    @Test
    fun `the report names the version - system - servers by kind and the queue`() {
        val report = buildProblemReport(input())
        assertTrue("App version: 0.5.3" in report)
        assertTrue("System: Android 15 (API 35), Maker Model" in report)
        assertTrue("Audiobookshelf, signed in: last sync fine, 120 books" in report)
        assertTrue("Progress waiting to be sent: 2" in report)
        assertTrue("none saved" in report)
    }

    @Test
    fun `a signed out server and no servers are written plainly`() {
        assertTrue("Plex, signed out: last sync failed: timeout" in
            buildProblemReport(input(servers = listOf(ProblemServerLine("Plex", false, "last sync failed: timeout")))))
        assertTrue("  none" in buildProblemReport(input(servers = emptyList())))
    }

    @Test
    fun `tokens - addresses - paths and ids in a crash trace or a log line are removed`() {
        val crash = "java.io.IOException: GET https://books.example.org:13378/api/items?token=abc123SECRET failed\n" +
            "  at com.chapterplayer.Foo(Foo.kt:1)\nAuthorization: Bearer eyJhbGciOi.payload.sig\n/data/user/0/com.chapterplayer/files/x"
        val log = listOf("HTTP GET failed: 401 for 192.168.1.20:8096 key 0123456789abcdef0123456789abcdef")
        val report = buildProblemReport(input(crash = crash, log = log))
        for (secret in listOf("books.example.org", "abc123SECRET", "eyJhbGciOi", "192.168.1.20", "0123456789abcdef0123456789abcdef", "/data/user/0")) {
            assertFalse(secret in report, "$secret must not survive")
        }
        assertTrue("com.chapterplayer.Foo" in report, "the stack frames stay")
    }

    @Test
    fun `a real stack trace keeps its class names and frames but not its message secrets`() {
        val trace = "java.lang.IllegalStateException: failed for https://books.example.org/x token=abc123SECRET\n" +
            "\tat com.chapterplayer.data.Foo.bar(Foo.kt:12)\n\tat kotlin.coroutines.Continuation.resume(Native Method)\n" +
            "Caused by: java.io.IOException: reset by 10.0.0.5:443\n\tat com.chapterplayer.data.Foo.baz(Foo.kt:30)"
        val report = buildProblemReport(input(crash = trace))
        assertTrue("java.lang.IllegalStateException" in report)
        assertTrue("Caused by: java.io.IOException" in report)
        assertTrue("at com.chapterplayer.data.Foo.bar(Foo.kt:12)" in report)
        for (secret in listOf("books.example.org", "abc123SECRET", "10.0.0.5")) assertFalse(secret in report, secret)
    }

    @Test
    fun `a line shaped like a frame cannot carry a host or a token through`() {
        val report = buildProblemReport(input(log = listOf("at books.example.org/abc123SECRETvalue456789-xyz(a:1)")))
        assertFalse("books.example.org" in report)
        assertFalse("abc123SECRETvalue456789" in report)
        val inCrash = buildProblemReport(input(crash = "boom\n  at books.example.org/abc123SECRETvalue456789-xyz(a.kt:1)"))
        assertFalse("books.example.org" in inCrash)
        assertFalse("abc123SECRETvalue456789" in inCrash)
    }

    @Test
    fun `only the newest log lines and a bounded trace are kept`() {
        val lines = (1..PROBLEM_LOG_LINES + 50).map { "event $it" }
        val report = buildProblemReport(input(crash = "x".repeat(PROBLEM_TRACE_CHARS * 2), log = lines))
        assertFalse("event 50\n" in report)
        assertTrue("event ${PROBLEM_LOG_LINES + 50}" in report)
        assertEquals(PROBLEM_LOG_LINES, report.lines().count { it.trim().startsWith("event ") })
        assertTrue(report.length < PROBLEM_TRACE_CHARS + 20_000)
    }
}

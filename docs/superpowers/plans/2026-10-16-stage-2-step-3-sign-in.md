# Stage 2 Step 3: Sign-in and the library picker, Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Audiobookshelf server with a username and password (API token under "Advanced"), keep the sign-in alive when the short-lived access token expires, choose which libraries to show, and show a clear "sign in again" state when a login can no longer be used.

**Architecture:** The server's own login and refresh calls are wrapped in `AbsApiService`. A small `AbsSessionManager` turns a stored server login into a usable token, refreshing it before it expires or once after a 401, and every Audiobookshelf call goes through it. Which libraries are turned off is stored per server in a new Room table (database version 2). Sign-in is a pure use case (`AbsSignIn`) that the setup view model drives in two steps: sign in, then choose libraries.

**Tech Stack:** Kotlin 2.4.21 Multiplatform, Ktor 3.6 client (MockEngine in tests), kotlinx.serialization, Room KMP 2.8.5 with an auto-migration, Koin 4.2.2, Compose Multiplatform 1.12.1, kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` ("Adding a server", "Sign-in and credentials", "Libraries and the combined view"). One deliberate deviation from the spec, explained under "Verified server behavior": the empty database `token` column is NOT dropped in this step.

## Verified server behavior (Audiobookshelf 2.37.1, checked live on 2026-10-16)

Checked against the owner's server with a throwaway user that was deleted afterwards. Values below are the shapes only.

- `GET /status` (no token): `{app, serverVersion, isInit, language, authMethods:["local"], authFormData:{authLoginCustomMessage}}`. `authMethods` lists the enabled sign-in methods; a server with only OpenID has no `"local"`.
- `POST /login` with JSON `{username, password}` and the header `x-return-tokens: true`: `200`, body `{user:{id, username, email, type, token, accessToken, refreshToken, ...}, userDefaultLibraryId, serverSettings:{...}, Source}`.
  - `user.accessToken`: a JWT, claims `userId, username, jti, type:"access", iat, exp`, valid 3600 seconds.
  - `user.refreshToken`: a JWT, claims as above with `type:"refresh"`, valid 2592000 seconds (30 days). It is only a string in the body when `x-return-tokens: true` is sent.
  - `user.token`: the older token, claims `userId, username, iat` (no expiry). It still works as a Bearer token on 2.37.1 but is the deprecated form.
- `POST /auth/refresh` with the header `x-refresh-token: <refresh token>` (no body): `200`, same body shape with new `accessToken` and a new `refreshToken` (it rotates; the old one was still accepted in testing, so do not rely on either behavior). Sending the token in a JSON body gets `401`.
- Errors are plain: a wrong password, a missing token, an invalid token, and a bad refresh token all give `401` (bodies `"Unauthorized"` or `{"error":"Invalid refresh token"}`).
- `GET /api/libraries` returns `{libraries:[{id, name, folders, displayOrder, icon, mediaType, provider, settings, lastScan, lastScanVersion, createdAt, lastUpdate}]}`; `mediaType` is `book` or `podcast`.
- The API key used until now is a JWT with claims `keyId, name, type` and no expiry; it keeps working as a Bearer token.
- Servers older than 2.26 have no `accessToken`/`refreshToken`; there `user.token` is the only token, so the app must fall back to it.

**Deviation from the spec (ruling):** the spec says migration 1 to 2 "also drops the empty token column". Step 1 empties that column lazily on first read. A user who goes straight from v0.1.0 to this build would still have tokens in that column when the migration ran, and dropping it would delete them. So this step keeps the column (always written empty) and a later release drops it. Cost if wrong: a few unused bytes per server.

## Global Constraints

- Branch `stage-2/sign-in`, one pull request into protected `main`. Stage 2 is tagged `v0.2.0` after step 9. Merge only with the owner's go-ahead.
- The repository is public: never commit real server data (addresses, usernames, passwords, tokens, library names or ids from the owner's server). Fixtures are made up with the real structure. Test tokens in fixtures are plain strings such as `"access.fixture.token"`; tests that need a real JWT shape build one in code.
- The password is never stored and never logged. Tokens and the refresh token are never logged. Debug HTTP logging must not print request or response bodies, nor the `Authorization`, `x-refresh-token`, `Cookie` or `Set-Cookie` headers.
- Tokens live only in the encrypted `CredentialStore` (step 1). The Room `token` column stays and is always written as an empty string.
- Room schema: version 2 adds one table and changes nothing else. The exported `shared/schemas/.../2.json` is committed, `1.json` stays untouched, and CI fails on any difference (`git diff --exit-code -- shared/schemas`).
- The Koin graph is checked by `KoinModulesTest`; add new classes to a module and keep it passing.
- File-based DataStore writes fail on the owner's Windows machine, so tests that write to a DataStore use `com.chapterplayer.testing.InMemoryPreferencesDataStore`.
- Java 17 (CI uses Temurin 17). Gradle 9.5, AGP 9.3, compileSdk 37, minSdk 26. Host tests: `./gradlew :shared:testAndroidHostTest`.
- Commits end with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`. Pull request descriptions end with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

## Review Focus

1. The password or a token reaches a log (debug HTTP logging prints bodies today at `LogLevel.ALL`), an exception message, or the database. Task 3.1 and Task 3.4.
2. The access token expires while the app is in use, or several calls expire together: exactly one refresh, the new tokens are saved, and the calls continue. A rejected refresh token must end in "sign in again", never a crash and never a retry loop. Task 3.5.
3. The server address is typed sloppily: no `http://`, trailing slash, spaces, a sub-path behind a reverse proxy (`https://host/audiobooks/`), uppercase scheme. Task 3.3.
4. A server that allows only OpenID sign-in (no `local` method), is not Audiobookshelf at all, is unreachable, or rate-limits logins (429). Each gets its own plain message. Task 3.3.
5. Sign-in works but there are no book libraries (only podcasts, or none): say so, and never save a server with every library turned off. Task 3.6.
6. A server older than 2.26 that returns only `token`: sign-in and every later call still work. Task 3.1 and Task 3.5.
7. Upgrading from v0.1.0 straight to this build keeps the tokens that are still in the database. Task 3.4.
8. Sign-in succeeds but the library call fails: nothing is half-saved. Task 3.6.
9. A saved server whose login is missing or no longer accepted shows "Sign in again" in Settings and in the sync message; it never shows an empty-looking library without explanation. Task 3.7.

## File structure (`shared/src/...` unless noted)

- Create `androidMain/.../data/remote/abs/dto/AbsAuthDto.kt`: status, login request and login response DTOs.
- Modify `androidMain/.../data/remote/abs/AbsApiService.kt`: `getStatus`, `login`, `refresh`.
- Modify `androidMain/.../data/remote/HttpClientFactory.kt`: logging level and redacted headers.
- Create `commonMain/.../data/security/Jwt.kt`: `jwtExpiryEpochSeconds`.
- Create `androidMain/.../data/remote/abs/AbsSignIn.kt`: address normalization, `AbsSignIn`, `AbsSignInError`.
- Modify `commonMain/.../data/model/ServerConfig.kt`: `refreshToken`, `excludedLibraryIds`, `needsSignIn`.
- Create `commonMain/.../data/local/entity/ExcludedLibraryEntity.kt` and `dao/ExcludedLibraryDao.kt`; modify `ChapterPlayerDatabase.kt`, `androidMain/.../di/KoinModules.kt`.
- Modify `androidMain/.../data/repository/ServerRepositoryImpl.kt`.
- Create `androidMain/.../data/remote/abs/AbsSessionManager.kt`; modify `androidMain/.../data/source/AbsLibrarySource.kt`.
- Modify `commonMain/.../ui/settings/SettingsViewModel.kt` (`ServerSetupViewModel`) and `SettingsScreen.kt`; modify `commonMain/.../ui/library/LibraryViewModel.kt`.
- Tests under `androidHostTest/...` matching each source file; fixtures under `androidHostTest/resources/fixtures/abs/`.

---

### Task 3.1: Safe logging, and the status, login and refresh calls

**Files:**
- Modify: `shared/src/androidMain/kotlin/com/chapterplayer/data/remote/HttpClientFactory.kt`
- Create: `shared/src/androidMain/kotlin/com/chapterplayer/data/remote/abs/dto/AbsAuthDto.kt`
- Modify: `shared/src/androidMain/kotlin/com/chapterplayer/data/remote/abs/AbsApiService.kt`
- Create fixtures: `shared/src/androidHostTest/resources/fixtures/abs/status.json`, `login-response.json`, `refresh-response.json`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/remote/abs/AbsAuthApiTest.kt`; modify `shared/src/androidHostTest/kotlin/com/chapterplayer/data/remote/HttpClientTest.kt`

**Interfaces:**
- Produces: `AbsApiService.getStatus(baseUrl): AbsStatusDto`, `AbsApiService.login(baseUrl, username, password): AbsLoginResponseDto`, `AbsApiService.refresh(baseUrl, refreshToken): AbsLoginResponseDto`; DTOs `AbsStatusDto(serverVersion: String?, isInit: Boolean?, authMethods: List<String>)`, `AbsLoginResponseDto(user: AbsUserDto, userDefaultLibraryId: String?)`, `AbsUserDto(id: String, username: String, type: String?, token: String?, accessToken: String?, refreshToken: String?)`. Debug logging level `HEADERS` with `Authorization`, `X-Plex-Token`, `x-refresh-token`, `Cookie`, `Set-Cookie` hidden.

- [ ] **Step 1: Write the fixtures** (all values made up)

`status.json`:
```json
{"app":"audiobookshelf","serverVersion":"2.37.1","isInit":true,"language":"en-us","authMethods":["local"],"authFormData":{"authLoginCustomMessage":null}}
```
`login-response.json`:
```json
{"user":{"id":"usr_fixture01","username":"fixture-user","email":null,"type":"user","token":"legacy.fixture.token","accessToken":"access.fixture.token","refreshToken":"refresh.fixture.token","isActive":true,"permissions":{"download":true}},"userDefaultLibraryId":"lib_fixture_books","serverSettings":{"version":"2.37.1"},"Source":"docker"}
```
`refresh-response.json`:
```json
{"user":{"id":"usr_fixture01","username":"fixture-user","type":"user","token":"legacy.fixture.token","accessToken":"access.fixture.token2","refreshToken":"refresh.fixture.token2"},"userDefaultLibraryId":"lib_fixture_books"}
```

- [ ] **Step 2: Write the failing tests**

`AbsAuthApiTest.kt`:
```kotlin
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
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbsAuthApiTest {

    private val requests = mutableListOf<HttpRequestData>()
    private val logs = mutableListOf<String>()

    private fun service(body: String, status: HttpStatusCode = HttpStatusCode.OK) = AbsApiService(
        HttpClient(
            MockEngine { request ->
                requests += request
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { installApiDefaults(isDebuggable = true, sink = { logs += it }) }
    )

    @Test
    fun `reads the server status`() = runTest {
        val status = service(fixture("abs/status.json")).getStatus("http://abs.test:13378/")

        assertEquals("http://abs.test:13378/status", requests.single().url.toString())
        assertEquals(HttpMethod.Get, requests.single().method)
        assertNull(requests.single().headers[HttpHeaders.Authorization])
        assertEquals("2.37.1", status.serverVersion)
        assertEquals(listOf("local"), status.authMethods)
    }

    @Test
    fun `a status with no sign-in methods listed parses`() = runTest {
        val status = service("""{"app":"audiobookshelf"}""").getStatus("http://abs.test")
        assertEquals(emptyList(), status.authMethods)
    }

    @Test
    fun `login posts the credentials and asks for the tokens`() = runTest {
        val result = service(fixture("abs/login-response.json")).login("http://abs.test", "fixture-user", "pw-123")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://abs.test/login", request.url.toString())
        assertEquals("true", request.headers["x-return-tokens"])
        val body = (request.body as TextContent).text
        assertTrue(""""username":"fixture-user"""" in body && """"password":"pw-123"""" in body, body)
        assertEquals("access.fixture.token", result.user.accessToken)
        assertEquals("refresh.fixture.token", result.user.refreshToken)
        assertEquals("legacy.fixture.token", result.user.token)
        assertEquals("lib_fixture_books", result.userDefaultLibraryId)
    }

    @Test
    fun `a server that only returns the legacy token parses`() = runTest {
        val old = """{"user":{"id":"u1","username":"fixture-user","type":"user","token":"legacy.only.token"}}"""
        val result = service(old).login("http://abs.test", "fixture-user", "pw")

        assertNull(result.user.accessToken)
        assertNull(result.user.refreshToken)
        assertEquals("legacy.only.token", result.user.token)
    }

    @Test
    fun `a wrong password fails with the http status`() = runTest {
        val error = assertFailsWith<HttpStatusException> {
            service("Unauthorized", HttpStatusCode.Unauthorized).login("http://abs.test", "u", "wrong")
        }
        assertEquals(HttpStatusCode.Unauthorized, error.status)
    }

    @Test
    fun `refresh sends the refresh token in the header and returns new tokens`() = runTest {
        val result = service(fixture("abs/refresh-response.json")).refresh("http://abs.test", "refresh.fixture.token")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("http://abs.test/auth/refresh", request.url.toString())
        assertEquals("refresh.fixture.token", request.headers["x-refresh-token"])
        assertEquals("access.fixture.token2", result.user.accessToken)
        assertEquals("refresh.fixture.token2", result.user.refreshToken)
    }

    @Test
    fun `logging never shows the password, a token, a refresh token or a cookie`() = runTest {
        service(fixture("abs/login-response.json")).login("http://abs.test", "fixture-user", "pw-very-secret")
        service(fixture("abs/refresh-response.json")).refresh("http://abs.test", "refresh.fixture.token")

        val text = logs.joinToString("\n")
        assertTrue("http://abs.test/login" in text, "requests are still logged in debug builds")
        for (secret in listOf("pw-very-secret", "access.fixture.token", "refresh.fixture.token", "legacy.fixture.token")) {
            assertTrue(secret !in text, "the log must not contain $secret")
        }
    }
}
```
In `HttpClientTest.kt` add one test next to the existing redaction test: a request with headers `x-refresh-token: secret-refresh` and `Cookie: sid=secret-cookie`, and a response with `Set-Cookie: refresh_token=secret-cookie2`, logged at `isDebuggable = true`, must not contain `secret-refresh`, `secret-cookie` or `secret-cookie2`. (The MockEngine in that file's `client()` helper returns no cookie; add a `setCookie` parameter or build the client inline in the new test.)

- [ ] **Step 3: Run the tests to see them fail.** `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.remote.abs.AbsAuthApiTest" --tests "com.chapterplayer.data.remote.HttpClientTest"`. Expected: compile errors (`getStatus`, `login`, `refresh` unresolved).

- [ ] **Step 4: Write `AbsAuthDto.kt`**
```kotlin
package com.chapterplayer.data.remote.abs.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** GET /status: answered without signing in. */
@Serializable
data class AbsStatusDto(
    @SerialName("serverVersion") val serverVersion: String? = null,
    @SerialName("isInit") val isInit: Boolean? = null,
    // "local" means username and password sign-in is on; OpenID-only servers do not list it.
    @SerialName("authMethods") val authMethods: List<String> = emptyList()
)

@Serializable
data class AbsLoginRequest(
    @SerialName("username") val username: String,
    @SerialName("password") val password: String
)

/** POST /login and POST /auth/refresh. Servers before 2.26 return only [AbsUserDto.token]. */
@Serializable
data class AbsLoginResponseDto(
    @SerialName("user") val user: AbsUserDto,
    @SerialName("userDefaultLibraryId") val userDefaultLibraryId: String? = null
)

@Serializable
data class AbsUserDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("type") val type: String? = null,
    /** The older, non-expiring token (deprecated; the only token on servers before 2.26). */
    @SerialName("token") val token: String? = null,
    /** Valid for an hour. */
    @SerialName("accessToken") val accessToken: String? = null,
    /** Valid for 30 days; rotates each time it is used. */
    @SerialName("refreshToken") val refreshToken: String? = null
)
```

- [ ] **Step 5: Add the calls to `AbsApiService`** (new imports: `io.ktor.client.request.post`, `io.ktor.client.request.setBody`, `io.ktor.http.ContentType`, `io.ktor.http.contentType`, and the new DTOs)
```kotlin
    /** Answers without a token: the server version and which sign-in methods are on. */
    suspend fun getStatus(baseUrl: String): AbsStatusDto =
        client.get(endpoint(baseUrl, "status")).body()

    /** Signs in. The header makes the server put the refresh token in the response body. */
    suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto =
        client.post(endpoint(baseUrl, "login")) {
            contentType(ContentType.Application.Json)
            header("x-return-tokens", "true")
            setBody(AbsLoginRequest(username, password))
        }.body()

    /** Trades a refresh token for a new access token and a new refresh token. */
    suspend fun refresh(baseUrl: String, refreshToken: String): AbsLoginResponseDto =
        client.post(endpoint(baseUrl, "auth/refresh")) {
            header("x-refresh-token", refreshToken)
        }.body()
```

- [ ] **Step 6: Make debug logging safe.** In `HttpClientFactory.kt`, change `level = if (isDebuggable) LogLevel.ALL else LogLevel.NONE` to `level = if (isDebuggable) LogLevel.HEADERS else LogLevel.NONE` (no request or response bodies, so the password and the login response never reach the log), and replace the `sanitizeHeader` block with:
```kotlin
        sanitizeHeader { name ->
            name.equals(HttpHeaders.Authorization, ignoreCase = true) ||
                name.equals("X-Plex-Token", ignoreCase = true) ||
                name.equals("x-refresh-token", ignoreCase = true) ||
                name.equals(HttpHeaders.Cookie, ignoreCase = true) ||
                name.equals(HttpHeaders.SetCookie, ignoreCase = true)
        }
```
Update the comment above it: bodies are not logged because login carries a password.

- [ ] **Step 7: Run the tests to see them pass.** Same command. Expected: PASS. Then the full suite (`./gradlew :shared:testAndroidHostTest`): PASS. If an existing test relied on bodies being logged, change it to assert the request line instead and say so in the commit.
- [ ] **Step 8: Commit** `git add shared/src && git commit -m "Stage 2: add the status, login and refresh calls; stop logging bodies"`

### Task 3.2: Reading a token's expiry

**Files:**
- Create: `shared/src/commonMain/kotlin/com/chapterplayer/data/security/Jwt.kt`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/security/JwtTest.kt`

**Interfaces:**
- Produces: `fun jwtExpiryEpochSeconds(token: String): Long?`: the `exp` claim in seconds since 1970, or null when the text is not a JWT, has no `exp`, or `exp` is not a number. It never throws.

- [ ] **Step 1: Write the failing tests**
```kotlin
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
```
- [ ] **Step 2: Run to see it fail.** `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.security.JwtTest"`. Expected: compile error (`jwtExpiryEpochSeconds` unresolved).
- [ ] **Step 3: Implement**
```kotlin
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
```
(If `ABSENT_OPTIONAL` is not available in this Kotlin version, decode with `Base64.UrlSafe` after adding `=` padding by hand.)
- [ ] **Step 4: Run to see it pass**, then the full suite.
- [ ] **Step 5: Commit** `git add shared/src && git commit -m "Stage 2: read a token's expiry"`

### Task 3.3: Server address and the sign-in use case

**Files:**
- Create: `shared/src/androidMain/kotlin/com/chapterplayer/data/remote/abs/AbsSignIn.kt`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/remote/abs/AbsSignInTest.kt`

**Interfaces:**
- Consumes: `AbsApiService` (Task 3.1).
- Produces:
  - `fun normalizeServerAddress(input: String): String?`: trims; adds `http://` when there is no scheme; lower-cases the scheme and host only; keeps a sub-path; removes trailing slashes, a query and a fragment; returns null when empty or not a plausible address.
  - `class AbsLibraryInfo(val id: String, val name: String, val mediaType: String)`.
  - `class AbsSignInResult(val baseUrl: String, val token: String, val refreshToken: String?, val libraries: List<AbsLibraryInfo>)` (`libraries` are only the book libraries).
  - `sealed interface AbsSignInError` with `Unreachable`, `NotAudiobookshelf`, `LocalLoginOff`, `WrongCredentials`, `TooManyAttempts`, `Rejected` (the token was refused), `ServerError(code: Int)`, `NoBookLibraries`; and `class AbsSignInException(val error: AbsSignInError) : Exception()`.
  - `class AbsSignIn(private val api: AbsApiService)` with `suspend fun withPassword(address: String, username: String, password: String): AbsSignInResult` and `suspend fun withApiKey(address: String, apiKey: String): AbsSignInResult`. Both throw `AbsSignInException` only.

Behavior of `withPassword`: normalize the address (an address that cannot be used gives `NotAudiobookshelf`); `getStatus` (an `HttpStatusException` or a parse failure gives `NotAudiobookshelf`; an IO failure gives `Unreachable`); if `authMethods` is non-empty and lacks `"local"` give `LocalLoginOff`; `login` (401 gives `WrongCredentials`, 429 `TooManyAttempts`, other status `ServerError`); choose `accessToken` when present, else the legacy `token`; if neither is present give `ServerError(0)`; refresh token = `refreshToken`; `getLibraries` with that token; keep `mediaType == "book"`; none left gives `NoBookLibraries`. `withApiKey` skips login: `getStatus` for reachability then `getLibraries` with the key (401 gives `Rejected`).

- [ ] **Step 1: Write the failing tests** covering at least: the address table (`"abs.local:13378"` -> `"http://abs.local:13378"`, `"  HTTP://Abs.Example.com/  "` -> `"http://abs.example.com"`, `"https://host/audiobooks/"` -> `"https://host/audiobooks"`, `"http://host:13378/?x=1#f"` -> `"http://host:13378"`, `""`, `"   "`, `"http://"`, `"not a host"` -> null); password sign-in success with the fixtures (requests in order `/status`, `/login`, `/api/libraries`, the library call carrying `Bearer access.fixture.token`, only book libraries returned, podcast library dropped); legacy-only server uses `token`; OpenID-only status gives `LocalLoginOff`; empty `authMethods` does NOT give `LocalLoginOff`; 401 on login gives `WrongCredentials`; 429 gives `TooManyAttempts`; 500 gives `ServerError(500)`; `/status` returning a non-JSON page gives `NotAudiobookshelf`; a `java.net.ConnectException` thrown by the engine gives `Unreachable`; only podcast libraries gives `NoBookLibraries`; API key success; API key 401 gives `Rejected`. Build the service with a `MockEngine` that routes by `request.url.encodedPath` (reuse the pattern in `AbsLibrarySourceTest`) and the fixtures `abs/status.json`, `abs/login-response.json`, `abs/libraries.json` (which has book and podcast libraries).
- [ ] **Step 2: Run to see it fail** (compile error: `AbsSignIn`, `normalizeServerAddress` unresolved).
- [ ] **Step 3: Implement** as specified above. Use `io.ktor.http.Url` parsing inside a try/catch for the address (reject when the host is empty or contains a space). Map errors in one private function `toError(t: Throwable, whenHttp: (Int) -> AbsSignInError)` so the status mapping differs for login and library calls. Never include the password, a token or the response body in an exception message.
- [ ] **Step 4: Run to see it pass**, then the full suite.
- [ ] **Step 5: Mutation check.** Remove the `"local"` check: `OpenID-only status gives LocalLoginOff` must fail. Restore.
- [ ] **Step 6: Commit** `git add shared/src && git commit -m "Stage 2: add the Audiobookshelf sign-in use case"`

### Task 3.4: Refresh token and turned-off libraries in the model, the database and the repository

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/data/model/ServerConfig.kt`
- Create: `shared/src/commonMain/kotlin/com/chapterplayer/data/local/entity/ExcludedLibraryEntity.kt`, `.../dao/ExcludedLibraryDao.kt`
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/data/local/ChapterPlayerDatabase.kt`, `shared/src/androidMain/kotlin/com/chapterplayer/di/KoinModules.kt`, `shared/src/androidMain/kotlin/com/chapterplayer/data/repository/ServerRepositoryImpl.kt`
- Modify tests: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/local/RoomSchemaTest.kt`, `.../data/repository/ServerRepositoryTest.kt`
- Create: `shared/schemas/com.chapterplayer.data.local.ChapterPlayerDatabase/2.json` (generated by the build)

**Interfaces:**
- Produces: `ServerConfig(..., val refreshToken: String? = null, val excludedLibraryIds: Set<String> = emptySet())` and `val needsSignIn: Boolean get() = token.isBlank()`; `@Entity(tableName = "excluded_libraries", primaryKeys = ["serverId","libraryId"]) data class ExcludedLibraryEntity(val serverId: String, val libraryId: String)`; `ExcludedLibraryDao { observeAll(): Flow<List<ExcludedLibraryEntity>>; getAll(): List<...>; insertAll(...); deleteForServer(serverId) }`; database version 2 with `autoMigrations = [AutoMigration(from = 1, to = 2)]`; `ServerRepositoryImpl(serverConfigDao, excludedLibraryDao, credentials)`.
- Repository behavior: the refresh token is stored in the `CredentialStore` under the key `"<serverId>.refresh"`, next to the token under `"<serverId>"`; `save` writes both (removing the refresh entry when `refreshToken == null`) and replaces the server's turned-off libraries (`deleteForServer`, then `insertAll`); reads attach both; `delete` removes both entries and the exclusions. Existing behavior from step 1 (lazy token move, per-row failure handling, ordering, locks) is unchanged.

- [ ] **Step 1: Spike (5 minutes, report the result).** Confirm Room 2.8.5 KMP generates an auto-migration for "add a table" and writes `2.json`: add the entity and `@Database(version = 2, entities = [..., ExcludedLibraryEntity::class], autoMigrations = [AutoMigration(from = 1, to = 2)], exportSchema = true)`, add `abstract fun excludedLibraryDao(): ExcludedLibraryDao`, run `./gradlew :shared:assembleDebug`. Expected: `shared/schemas/.../2.json` appears and the build passes. If auto-migration is not supported for this target, write `Migration(1, 2)` by hand instead (SQL: `CREATE TABLE IF NOT EXISTS excluded_libraries (serverId TEXT NOT NULL, libraryId TEXT NOT NULL, PRIMARY KEY(serverId, libraryId))`) and register it with `.addMigrations(...)` in `databaseModule`.
- [ ] **Step 2: Write the failing tests.**
  - `RoomSchemaTest`: keep every existing assertion but point the "shipped" checks at `1.json` (it stays the record of v0.1.0), and add tests against `2.json`: version is 2; tables are `books, progress, server_configs, excluded_libraries`; `server_configs` still has the `token` column (a comment explains why); `excluded_libraries` has the two TEXT NOT NULL columns and the composite primary key; every other table's columns equal their version 1 columns.
  - `ServerRepositoryTest` (fakes, no DataStore): add a `FakeExcludedDao` and update the existing helper construction. New tests: saving stores the refresh token under `"<id>.refresh"` and clears the entry when it becomes null; reads return `refreshToken` and `excludedLibraryIds`; saving replaces (does not append to) the exclusions; deleting removes both credential entries and the exclusions; a missing refresh entry reads as null; **a legacy row whose database `token` is still filled (never migrated) keeps its token after the version 2 schema change** (this is Review Focus 7: build a repository over a `FakeDao` holding a filled token, read it, assert it is returned and then moved to the credential store, exactly as in step 1; the point is that nothing in this task removes the `token` column).
- [ ] **Step 3: Run to see them fail.** `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.local.RoomSchemaTest" --tests "com.chapterplayer.data.repository.ServerRepositoryTest"`. Expected: compile errors (new constructor, new fields).
- [ ] **Step 4: Implement** the model, entity, DAO, database change (from Step 1), Koin (`single { get<ChapterPlayerDatabase>().excludedLibraryDao() }`, and `ServerRepositoryImpl` receives the new DAO), and the repository. In `observeAll` combine the servers flow with `excludedLibraryDao.observeAll()`: `combine(serverConfigDao.observeAll(), excludedLibraryDao.observeAll()) { rows, excluded -> ... }` and keep the existing `withToken` logic (extend it to also read the refresh entry). `ServerConfigEntity.fromDomain` still writes `token = ""`.
- [ ] **Step 5: Run to see them pass**; then the full suite and `./gradlew :androidApp:assembleDebug`. `git status --short shared/schemas` must show only the new `2.json` (and no change to `1.json`).
- [ ] **Step 6: Commit** `git add shared/src shared/schemas && git commit -m "Stage 2: store the refresh token and turned-off libraries; database version 2"`

### Task 3.5: Keeping the sign-in alive

**Files:**
- Create: `shared/src/androidMain/kotlin/com/chapterplayer/data/remote/abs/AbsSessionManager.kt`
- Modify: `shared/src/androidMain/kotlin/com/chapterplayer/data/source/AbsLibrarySource.kt`, `shared/src/androidMain/kotlin/com/chapterplayer/di/KoinModules.kt`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/remote/abs/AbsSessionManagerTest.kt`; modify `.../data/source/AbsLibrarySourceTest.kt`

**Interfaces:**
- Consumes: `AbsApiService.refresh` (3.1), `jwtExpiryEpochSeconds` (3.2), `ServerRepository.save/getById` (3.4).
- Produces: `interface AbsAuthorizer { suspend fun <T> authorized(server: ServerConfig, call: suspend (bearerToken: String) -> T): T }`; `class SignInRequiredException(val serverName: String) : Exception("Sign in again to <serverName>")`; `class AbsSessionManager(api: AbsApiService, servers: ServerRepository, clock: () -> Long = { System.currentTimeMillis() / 1000 }) : AbsAuthorizer`; `AbsLibrarySource(api: AbsApiService, authorizer: AbsAuthorizer)`. `call` receives the full header value (`"Bearer <token>"`).
- Behavior of `authorized(server, call)`:
  1. If `server.token` is blank: throw `SignInRequiredException`.
  2. If a refresh token exists and the access token's expiry is within 60 seconds (or already past), refresh first.
  3. Run `call("Bearer " + token)`. If it throws `HttpStatusException` with status 401 and a refresh token exists, refresh once and run `call` once more with the new token; any further 401 throws `SignInRequiredException`. A 401 with no refresh token throws `SignInRequiredException`.
  4. Refresh: serialized per server id with a `Mutex`; inside the lock re-read the server from the repository (another caller may already have refreshed) and skip the network call if its token differs from the one that failed; otherwise `api.refresh(baseUrl, refreshToken)`; save the new access token (or the legacy token when the answer has none), the new refresh token (keep the old one if the answer has none) with `servers.save(...)`; a 401 on refresh throws `SignInRequiredException`; any other failure on refresh is rethrown unchanged (the server may just be offline).
  5. A token with no `exp` claim (API key, legacy token) is never refreshed ahead of time.

- [ ] **Step 1: Write the failing tests** with a fake `ServerRepository` (holds one server, records saves), a MockEngine that serves `/auth/refresh` and a protected route, and a fixed `clock`. Cover: a fresh token is used as is (no refresh call); a token expiring in 30 seconds is refreshed before the call and the new tokens are saved; an expired token is refreshed; a 401 triggers one refresh and one retry that succeeds; a second 401 after the refresh throws `SignInRequiredException` and does not refresh again; a refresh answered 401 throws `SignInRequiredException`; a refresh failing with a connection error is rethrown (not turned into sign-in-required); no refresh token plus a 401 throws `SignInRequiredException`; a blank token throws `SignInRequiredException` without any request; an API key (no `exp`) is used as is; **ten concurrent calls with an expired token make exactly one refresh request** (use `async` and `awaitAll`; the fake refresh endpoint counts requests); the refresh answer without a new refresh token keeps the old one; the answer with only `token` (legacy) saves that as the token. Update `AbsLibrarySourceTest` to construct `AbsLibrarySource(api, PassThroughAuthorizer)` where `PassThroughAuthorizer` is a small test object that runs `call("Bearer " + server.token)`.
- [ ] **Step 2: Run to see them fail**, **Step 3: Implement** as specified, wire `single { AbsSessionManager(get(), get()) }` plus `bind<AbsAuthorizer>()` in `repositoryModule` (it needs `ServerRepository`) or a new module, keep `KoinModulesTest` passing, and make `AbsLibrarySource` call `authorizer.authorized(server) { bearer -> ... }` around its library calls instead of building `"Bearer ${server.token}"` itself.
- [ ] **Step 4: Run to see them pass**, then the full suite and `assembleDebug`.
- [ ] **Step 5: Mutation check.** Remove the `Mutex`: the ten-concurrent-calls test must fail. Restore.
- [ ] **Step 6: Commit** `git add shared/src && git commit -m "Stage 2: refresh the Audiobookshelf sign-in before it expires"`

### Task 3.6: Library choice honored when syncing, and the sign-in screens

**Files:**
- Modify: `shared/src/androidMain/kotlin/com/chapterplayer/data/source/AbsLibrarySource.kt`; `shared/src/commonMain/kotlin/com/chapterplayer/ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt`; `shared/src/androidMain/kotlin/com/chapterplayer/di/KoinModules.kt`
- Test: `AbsLibrarySourceTest.kt`; create `shared/src/androidHostTest/kotlin/com/chapterplayer/ui/settings/AbsSetupViewModelTest.kt`

**Interfaces:**
- Consumes: `AbsSignIn` (3.3), `ServerConfig.excludedLibraryIds` (3.4).
- Produces: `AbsLibrarySource` uses the book libraries of the server minus `excludedLibraryIds`; an ABS server whose legacy `libraryId` is set still reads only that library. `ServerSetupViewModel` gains an Audiobookshelf path with these states: `AbsStep.SIGN_IN` then `AbsStep.LIBRARIES`; fields `address`, `username`, `password`, `useApiKey`, `apiKey`, `signInError: String?`, `isSigningIn`, `libraries: List<LibraryChoice(id, name, enabled)>`.

- [ ] **Step 1: Tests first.** (a) `AbsLibrarySourceTest`: excluded library ids are skipped; podcast libraries are still skipped; with `libraryId` set only that library is read (existing behavior); excluding every book library yields an empty list without error. (b) `AbsSetupViewModelTest` (fake `AbsSignIn` via an interface `AbsSignInPort` the view model depends on, plus a fake `ServerRepository`): signing in moves to `LIBRARIES` with every library ticked; a `AbsSignInException` shows the plain message for each error and stays on `SIGN_IN` (messages: Unreachable "Couldn't reach the server. Check the address and your network."; NotAudiobookshelf "That address doesn't look like an Audiobookshelf server."; LocalLoginOff "This server only allows OpenID sign-in, which isn't supported yet."; WrongCredentials "The username or password is wrong."; TooManyAttempts "Too many attempts. Wait a few minutes and try again."; Rejected "The server didn't accept that API token."; ServerError "The server returned an error (code N)."; NoBookLibraries "This server has no audiobook libraries."); the Save button is not enabled while every library is unticked; saving writes a `ServerConfig` with the token, the refresh token and `excludedLibraryIds` equal to the unticked ids; nothing is saved when sign-in fails; the password is cleared from state after a successful sign-in; **a failed `servers.save` leaves the screen on `LIBRARIES` with an error and `savedSuccessfully == false`** (Review Focus 8); two quick taps on Sign in start only one request. Existing Plex view-model tests keep passing.
- [ ] **Step 2: Run to see them fail.**
- [ ] **Step 3: Implement.** The ABS source change is small. In `ServerSetupViewModel` add the ABS path described above; keep the Plex path and its code untouched. Register `AbsSignIn` in Koin (`singleOf(::AbsSignIn)`; bind `AbsSignInPort`). In `ServerSetupScreen`, for `ServerSource.ABS` show: "Server address", "Username", "Password" (hidden input), a "Sign in" button (shows a progress indicator and is disabled while signing in), and an "Advanced" row that expands to an "API token" field and switches the button to "Connect"; on success show the library step: a heading "Choose libraries", one row per book library with a checkbox (all ticked), the note "Libraries added to this server later show up automatically. You can change this in Settings later.", and a "Save" button. Use the existing `ChapterTextField`, theme-aware colors and `MaterialTheme.colorScheme.onPrimary` on amber buttons; errors use the existing error text style; the keyboard action on the password field submits. The display name defaults to the host of the address when left empty. Remove the "Library ID" field from the ABS form (the Plex form keeps it).
- [ ] **Step 4: Run to see the view-model and source tests pass**, then the full suite and `assembleDebug`.
- [ ] **Step 5: Commit** `git add shared/src && git commit -m "Stage 2: sign in to Audiobookshelf and choose libraries"`

### Task 3.7: "Sign in again"

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/ui/settings/SettingsScreen.kt`, `SettingsViewModel.kt`, `shared/src/commonMain/kotlin/com/chapterplayer/ui/library/LibraryViewModel.kt`
- Test: extend `AbsSetupViewModelTest.kt`; create `shared/src/androidHostTest/kotlin/com/chapterplayer/ui/library/LibrarySignInMessageTest.kt` (or add to an existing library view model test if one exists)

**Interfaces:**
- Consumes: `ServerConfig.needsSignIn` (3.4), `SignInRequiredException` (3.5).
- Produces: a server card in Settings that shows the line "Sign in again" (error color) when `needsSignIn`; opening Edit on an ABS server shows the sign-in fields (address prefilled and editable, username empty, password empty, Advanced for an API token) and saving keeps the server id, name and turned-off libraries; the sync message for a `SignInRequiredException` is `"<name>: Sign in again in Settings"` (not the raw exception text); a library screen with a server that needs sign-in is not shown as simply empty.

- [ ] **Step 1: Tests first.** The sync message mapping for `SignInRequiredException` and for a blank-token server (the repository returns blank when the keystore key was lost); editing an ABS server and signing in again keeps `id`, `name` and `excludedLibraryIds` and replaces the tokens; the edit form for a server with a token does not show the token.
- [ ] **Step 2: Run to see them fail.** **Step 3: Implement.** **Step 4: Run to see them pass**, then the full suite and `assembleDebug`.
- [ ] **Step 5: Commit** `git add shared/src && git commit -m "Stage 2: show when a server needs signing in again"`

### Task 3.8: Checks on a device, docs, pull request (done by the controller)

- [ ] **Step 1: Full verification.** `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug`; `git status --short shared/schemas` shows only the new `2.json`; privacy scan of `git diff main` (server address, usernames, tokens, library names): none.
- [ ] **Step 2: Emulator, real server.** Sign-in needs a real username and password. Ask the owner first whether it is fine to create one throwaway user with the admin API key for this test (the owner approved that once for the probe on 2026-10-16; that approval does not carry over). Delete the user as soon as the checks are done, and check it is gone.
  - upgrade over the previous build with a saved server: the server still syncs (tokens kept; schema 2 opens);
  - add a server with username and password: the library step lists the book libraries, untick one, Save; the books of only the ticked libraries appear after sync; the debug log has no password, token or body;
  - wrong password and an unreachable address show their messages; OpenID-only cannot be tested (note it);
  - add a server with an API token under Advanced;
  - the refresh path cannot be reached on a device in a reasonable time (the access token lasts an hour), so it is covered by the unit tests in Task 3.5 and by the live `/auth/refresh` check already done against the owner's server; state that in the pull request;
  - blank token (corrupt the credentials file) shows "Sign in again" on the server card and the sync message; signing in again fixes it;
  - signed release build (R8) repeats sign-in and a sync.
- [ ] **Step 3: Docs.** `docs/ROADMAP.md`: tick `ABS sign-in: username/password and API key; library picker`; add a note under it that the token column is dropped in a later release. `CLAUDE.md` Current state: sign-in goes through `AbsSignIn`; calls go through `AbsSessionManager` (access token refreshed ahead of expiry); libraries turned off are in the `excluded_libraries` table; HTTP debug logging does not print bodies.
- [ ] **Step 4: Push and open the pull request** `Stage 2: sign in to Audiobookshelf and choose libraries (step 3)` with checked and not-checked lists, ending with the PR attribution line. Wait for CI. Do not merge without the owner's go-ahead.

---

## Self-review

- **Spec coverage:** username and password sign-in with the API token under Advanced (3.3, 3.6); the password is never stored (3.3, 3.6, Review Focus 1); library picker with new libraries on by default because only turned-off libraries are stored (3.4, 3.6); the database migration (3.4); refresh with the fallback to signing in again (3.5, 3.7); the "sign in again" message the step 1 review asked for (3.7). The spec's "drops the empty token column" is replaced by keeping it (explained at the top).
- **Placeholders:** the steps that say "implement as specified" give the complete behavior list and the interfaces in the same task; fixtures and the API tests are fully written; the sign-in, session and view-model tests list every case by name because their code follows the existing test patterns in this repository. The one thing deliberately left to the task is the Room auto-migration spike (3.4 step 1), because it depends on what Room 2.8.5 generates for this target; both outcomes are specified.
- **Type consistency:** `AbsApiService.getStatus/login/refresh`, `AbsLoginResponseDto`, `AbsUserDto`, `jwtExpiryEpochSeconds`, `AbsSignIn`/`AbsSignInResult`/`AbsSignInError`, `ServerConfig.refreshToken/excludedLibraryIds/needsSignIn`, `AbsAuthorizer.authorized`, `SignInRequiredException` and the `"<id>.refresh"` credential key are named identically wherever they are used.

# Stage 1, PR 3: Networking swap (Retrofit + Moshi to Ktor + kotlinx.serialization)

**Goal:** Replace Retrofit, OkHttp-as-client and Moshi with Ktor and kotlinx.serialization with no change in behaviour, backed by tests built from recorded server responses.

**Spec:** `docs/superpowers/specs/2026-10-12-stage-1-kmp-migration-design.md` (PR 3 row). Refactor rules from the `kotlin-kmp-refactor-safety` skill apply: tests first, one implementation at the end, no opportunistic fixes.

## Behaviour that must not change

| Behaviour | Today | How it is preserved |
|---|---|---|
| Parsing | Moshi: unknown keys ignored, missing nullable field is null, missing required field fails | `Json { ignoreUnknownKeys = true }`, and every nullable property gets `= null` |
| HTTP errors | Retrofit `HttpException`, shown to users as `HTTP 401 Unauthorized` (Library screen shows `throwable.message`) | Response validator throws an exception with the same message format |
| Auth header logging | OkHttp logging, `Authorization` and `X-Plex-Token` redacted, BODY level in debug builds only | Ktor `Logging` plugin with `sanitizeHeader`, same level rule via the injected `debuggable` flag |
| Timeouts | connect/read/write 30 s | `HttpTimeout`: connect and socket 30 s |
| Engine | OkHttp | Ktor OkHttp engine (same HTTP stack, cleartext and TLS behaviour) |
| Per-server base URL | `baseUrl.trimEnd('/') + "/"` | Same trimming when building request URLs |
| Chapters in Room | Moshi JSON `[{"id","title","startMs","endMs"}]` | `ChapterJson` pinned by tests, existing rows must still decode |
| Request shapes | Headers, query params, paths per `AbsApiService`, `PlexApiService`, `PlexTvApiService` | `MockEngine` tests assert each request |

## Task order (each ends green and committed)

1. **ABS fixtures and characterization tests on the current Moshi code.** Fixtures recorded from a real Audiobookshelf 2.37.1 server and scrubbed (repo is public): libraries list, an items page with `total`, an items page without `total`, an empty last page, synthetic edge cases (null cover, empty narrator, zero duration, non-ASCII title, apostrophe). Tests assert parsed values through a test helper, so the same assertions run after the swap.
2. **`ChapterJson` seam.** Move the private Moshi chapter adapter in `BookRepositoryImpl` into `ChapterJson` (encode/decode), pin the exact output and decode of old rows with tests.
3. **Plex fixtures and tests.** plex.tv (pin, resources) and Plex server (sections, library items with chapters). Captured from a real sign-in if available, otherwise written from Plex's documented shapes and labelled synthetic.
4. **Request-shape characterization.** Record what the app sends today (URLs, headers, query) from the debug HTTP log and pin it as expected values for the new client tests.
5. **Swap serialization.** kotlinx.serialization plugin and dependency, `@Serializable` DTOs with `= null` defaults, `Chapter` serializable, `ChapterJson` on kotlinx. Fixture and chapter tests pass unchanged.
6. **Swap the HTTP layer.** `HttpClient` provided from `NetworkModule`; `AbsApi`, `PlexApi`, `PlexTvApi` classes replace the Retrofit interfaces; `MockEngine` tests for every call; repository and view models rewired; Retrofit, OkHttp, Moshi dependencies and ProGuard rules removed.
7. **Verify on a device.** Real ABS sync (all books present), in-place upgrade from the previous build with existing data, Plex sign-in and sync, release build with R8 and a size comparison.
8. **PR.**

## Versions (checked 2026-10-12)

Ktor 3.6.0 (core, okhttp engine, content-negotiation, serialization-kotlinx-json, logging, mock for tests), kotlinx.serialization 1.11.0, Kotlin serialization compiler plugin matching Kotlin 2.4.21. If Ktor 3.6.0 fails to build, fall back to 3.5.2.

## Design note for Stage 3

The HTTP client is built by a factory that takes the timeout settings as parameters. PR 3 uses the 30-second values the app has today. Stage 3 downloads (very slow links, possibly 30 hours per book) need a client with no overall request timeout and only a stall check, so that must be a different configuration of the same factory and not a rewrite.

## Out of scope

Moving code to `commonMain` (the code stays in `androidMain`; the Ktor engine is Android-only for now), Koin (PR 4), fixing the stream URL or chapters (Stage 2), token storage.

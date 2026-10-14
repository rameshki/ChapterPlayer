# Stage 2 Implementation Plan: foundations (steps 1 and 2) and the nine-step outline

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first two steps of Stage 2: secure token storage with a settings store (step 1) and the light theme with a Dark / Light / Follow system choice (step 2). The remaining seven steps are outlined here and each gets its own detailed plan when it starts.

**Architecture:** Server tokens move out of the Room database into a DataStore file whose values are encrypted with an AES-GCM key held in the Android Keystore. App preferences (theme, library layout, car resume) live in a second DataStore behind an `AppSettings` interface. The theme gets a light palette; the existing colour names become theme-aware, so screens do not need to change.

**Tech Stack:** Kotlin 2.4.21, Kotlin Multiplatform (`shared/`), Jetpack DataStore Preferences 1.2.1, Compose Multiplatform 1.12.1, Koin 4.2.2, Room KMP 2.8.5 (schema does not change), kotlinx-coroutines-test, JUnit via `kotlin.test`.

**Spec:** `docs/superpowers/specs/2026-10-14-stage-2-abs-core-design.md` (approved 2026-10-15).

## Global Constraints

- Branches are `stage-2/<feature>`, one pull request per step, into protected `main`. Stage 2 is tagged `v0.2.0` after step 9.
- Each step builds, passes CI and gets an emulator pass before it merges. Merge only with the owner's go-ahead.
- The repository is public: never commit real server data (titles, authors, addresses, usernames, tokens). Test data is made up.
- Server tokens are never logged. The password is never stored.
- Tokens are stored encrypted with a key in the Android Keystore. Today the token is plain text in the Room database.
- The Room schema in `shared/schemas` must not change in steps 1 and 2. CI fails on any difference (`git diff --exit-code -- shared/schemas`).
- Theme: three choices (Dark, Light, Follow system), new installs use Follow system. The amber-gold accent, Playfair Display and DM Sans stay in both themes.
- Java 17 (CI uses Temurin 17). Gradle 9.5, AGP 9.3, compileSdk 37, minSdk 26.
- Host tests run with `./gradlew :shared:testAndroidHostTest`. The Koin graph is checked by `KoinModulesTest` (add new classes to a module and keep it passing).
- Commits end with these two lines, and pull request descriptions end with the line after them:
  - `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`
  - `Claude-Session: https://claude.ai/code/session_01M55YRaxmRRG8NmDaVhSmey`
  - `🤖 Generated with [Claude Code](https://claude.com/claude-code)` (pull requests only)

## Review Focus

These inputs are implied by the spec but easy to miss. Each has a test in the task that owns the code.

1. The Keystore key is gone or unusable (phone restored from a backup, lock screen removed). Decrypting fails. The app must not crash: the token reads as missing and the server shows an empty token. Task 1.3 and Task 1.5.
2. The token migration is interrupted after the token is saved to the new store and before the old column is blanked. Running it again must finish the job and never lose the token. Task 1.5.
3. A stored preference value the app does not know (a newer version wrote it, then the user downgraded). The app falls back to the default, not a crash. Task 1.1.
4. The settings file is corrupted. The app falls back to defaults. Task 1.1.
5. Deleting a server removes its token from the encrypted store, so tokens do not pile up. Two servers with the same token do not share an entry. Task 1.5.
6. Light-theme text is readable: body text, secondary text and the amber accent meet WCAG AA (4.5:1) against their backgrounds. Task 2.2.
7. Colours read inside non-composable code (draw lambdas) fail to compile once colours become theme-aware. The compiler finds them, and Task 2.3 fixes each by reading the colour first.

## File structure

Step 1 (`shared/src/...`):
- Create `commonMain/.../data/settings/ThemeMode.kt`: `ThemeMode` and `LibraryLayout` enums.
- Create `commonMain/.../data/settings/AppSettings.kt`: the `AppSettings` interface and `DataStoreAppSettings`.
- Create `commonMain/.../data/security/TokenCipher.kt`: the `TokenCipher` interface and `TokenCipherException`.
- Create `commonMain/.../data/security/CredentialStore.kt`: the `CredentialStore` interface and `DataStoreCredentialStore`.
- Create `androidMain/.../data/security/AesGcmTokenCipher.kt` and `AndroidKeystoreKey.kt`.
- Create `androidMain/.../data/DataStores.kt`: builds the two DataStore files.
- Modify `androidMain/.../data/repository/ServerRepositoryImpl.kt`, `commonMain/.../data/local/entity/ServerConfigEntity.kt`, `commonMain/.../data/local/dao/ServerConfigDao.kt`.
- Modify `androidMain/.../di/KoinModules.kt`, `shared/build.gradle.kts`.
- Tests in `androidHostTest/.../data/settings/`, `data/security/`, `data/repository/`, and `di/KoinModulesTest.kt`.

Step 2:
- Create `commonMain/.../ui/theme/Palette.kt`: the palette class and the dark and light palettes.
- Modify `commonMain/.../ui/theme/Color.kt` (colour names become theme-aware) and `Theme.kt`.
- Modify `commonMain/.../ui/settings/SettingsViewModel.kt` and `SettingsScreen.kt`.
- Modify `androidApp/src/main/kotlin/com/chapterplayer/MainActivity.kt`.
- Tests in `androidHostTest/.../ui/theme/` and `ui/settings/`.

---

# Step 1: Secure token storage and the preferences store

Branch `stage-2/secure-storage`.

### Task 1.1: App settings (theme, layout, car resume)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/chapterplayer/data/settings/ThemeMode.kt`
- Create: `shared/src/commonMain/kotlin/com/chapterplayer/data/settings/AppSettings.kt`
- Modify: `shared/build.gradle.kts` (add DataStore to `commonMain`)
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/settings/AppSettingsTest.kt`

**Interfaces:**
- Produces: `enum class ThemeMode { DARK, LIGHT, SYSTEM }`, `enum class LibraryLayout { LARGE_GRID, SMALL_GRID, LIST }`, `interface AppSettings` with `themeMode: Flow<ThemeMode>` (default `SYSTEM`), `libraryLayout: Flow<LibraryLayout>` (default `LARGE_GRID`), `resumeOnCarConnect: Flow<Boolean>` (default `true`) and one `suspend fun set...` for each, and `class DataStoreAppSettings(store: DataStore<Preferences>) : AppSettings`.

- [ ] **Step 1: Add the DataStore dependency to `commonMain`**

In `shared/build.gradle.kts`, in `commonMain.dependencies { ... }` add `implementation(libs.datastore.preferences)` after `implementation(libs.kotlinx.serialization.json)`. In `androidMain.dependencies { ... }` delete the line `implementation(libs.datastore.preferences)` and the comment line above it (`// DataStore (Settings persistence)`), since `commonMain` now provides it.

- [ ] **Step 2: Write the failing tests**

Create `AppSettingsTest.kt`:

```kotlin
package com.chapterplayer.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsTest {

    private fun TestScope.newStore(file: File) =
        PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })

    private fun tempFile(): File = File.createTempFile("settings", ".preferences_pb").also { it.delete(); it.deleteOnExit() }

    @Test
    fun `a new install uses follow system, large covers and resume on car connect`() = runTest {
        val settings = DataStoreAppSettings(newStore(tempFile()))

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(LibraryLayout.LARGE_GRID, settings.libraryLayout.first())
        assertEquals(true, settings.resumeOnCarConnect.first())
    }

    @Test
    fun `changes are saved and read back`() = runTest {
        val settings = DataStoreAppSettings(newStore(tempFile()))

        settings.setThemeMode(ThemeMode.LIGHT)
        settings.setLibraryLayout(LibraryLayout.LIST)
        settings.setResumeOnCarConnect(false)

        assertEquals(ThemeMode.LIGHT, settings.themeMode.first())
        assertEquals(LibraryLayout.LIST, settings.libraryLayout.first())
        assertEquals(false, settings.resumeOnCarConnect.first())
    }

    @Test
    fun `an unknown stored value falls back to the default`() = runTest {
        val store = newStore(tempFile())
        store.edit { it[stringPreferencesKey("theme_mode")] = "PURPLE" }

        assertEquals(ThemeMode.SYSTEM, DataStoreAppSettings(store).themeMode.first())
    }

    @Test
    fun `a corrupted settings file falls back to the defaults`() = runTest {
        val file = tempFile().also { it.writeBytes(byteArrayOf(0x7F, 0x00, 0x13, 0x37, 0x42)) }

        val settings = DataStoreAppSettings(newStore(file))

        assertEquals(ThemeMode.SYSTEM, settings.themeMode.first())
        assertEquals(true, settings.resumeOnCarConnect.first())
    }
}
```

- [ ] **Step 3: Run the tests to see them fail**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.settings.AppSettingsTest"`
Expected: FAIL to compile with `Unresolved reference: DataStoreAppSettings` (and `ThemeMode`).

- [ ] **Step 4: Write the implementation**

`ThemeMode.kt`:

```kotlin
package com.chapterplayer.data.settings

/** How the app picks its colours. */
enum class ThemeMode { DARK, LIGHT, SYSTEM }

/** How the Library view lays out books. */
enum class LibraryLayout { LARGE_GRID, SMALL_GRID, LIST }
```

`AppSettings.kt`:

```kotlin
package com.chapterplayer.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/** The user's app preferences. Every value has a default, so a new install needs no setup. */
interface AppSettings {
    val themeMode: Flow<ThemeMode>
    suspend fun setThemeMode(mode: ThemeMode)

    val libraryLayout: Flow<LibraryLayout>
    suspend fun setLibraryLayout(layout: LibraryLayout)

    /** Whether Android Auto resumes the book when the car connects. */
    val resumeOnCarConnect: Flow<Boolean>
    suspend fun setResumeOnCarConnect(enabled: Boolean)
}

class DataStoreAppSettings(private val store: DataStore<Preferences>) : AppSettings {

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val layout = stringPreferencesKey("library_layout")
        val carResume = booleanPreferencesKey("resume_on_car_connect")
    }

    // A damaged settings file reads as "nothing saved", so the defaults apply.
    private val prefs: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    override val themeMode: Flow<ThemeMode> = prefs.map { p ->
        p[Keys.theme]?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } } ?: ThemeMode.SYSTEM
    }

    override val libraryLayout: Flow<LibraryLayout> = prefs.map { p ->
        p[Keys.layout]?.let { name -> LibraryLayout.entries.firstOrNull { it.name == name } } ?: LibraryLayout.LARGE_GRID
    }

    override val resumeOnCarConnect: Flow<Boolean> = prefs.map { it[Keys.carResume] ?: true }

    override suspend fun setThemeMode(mode: ThemeMode) { store.edit { it[Keys.theme] = mode.name } }
    override suspend fun setLibraryLayout(layout: LibraryLayout) { store.edit { it[Keys.layout] = layout.name } }
    override suspend fun setResumeOnCarConnect(enabled: Boolean) { store.edit { it[Keys.carResume] = enabled } }
}
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.settings.AppSettingsTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git checkout -b stage-2/secure-storage
git add shared/build.gradle.kts shared/src
git commit -m "Stage 2: add the app settings store (theme, layout, car resume)"
```
(Append the two trailer lines from Global Constraints to every commit message.)

### Task 1.2: Token cipher (AES-GCM)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/chapterplayer/data/security/TokenCipher.kt`
- Create: `shared/src/androidMain/kotlin/com/chapterplayer/data/security/AesGcmTokenCipher.kt`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/security/AesGcmTokenCipherTest.kt`

**Interfaces:**
- Produces: `interface TokenCipher { fun encrypt(plain: String): String; fun decrypt(encoded: String): String }` (decrypt throws `TokenCipherException` when the text is damaged or the key is wrong), `class TokenCipherException(cause: Throwable?) : Exception`, and `class AesGcmTokenCipher(private val key: () -> SecretKey) : TokenCipher`. The encoded text is Base64 of a 12-byte random IV followed by the ciphertext.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.chapterplayer.data.security

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AesGcmTokenCipherTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = newKey()
    private val cipher = AesGcmTokenCipher { key }

    @Test
    fun `text comes back unchanged`() {
        val token = "abc.DEF-123_xyz"
        assertEquals(token, cipher.decrypt(cipher.encrypt(token)))
    }

    @Test
    fun `an empty token and unicode survive`() {
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
        assertEquals("tökén-日本語-🔑", cipher.decrypt(cipher.encrypt("tökén-日本語-🔑")))
    }

    @Test
    fun `a very long token survives`() {
        val long = "x".repeat(20_000)
        assertEquals(long, cipher.decrypt(cipher.encrypt(long)))
    }

    @Test
    fun `the stored text does not contain the token`() {
        val encoded = cipher.encrypt("super-secret-token-value")
        assertTrue("super-secret" !in encoded)
    }

    @Test
    fun `encrypting the same token twice gives different text`() {
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `a different key cannot decrypt`() {
        val encoded = cipher.encrypt("secret")
        val other = AesGcmTokenCipher { newKey() }
        assertFailsWith<TokenCipherException> { other.decrypt(encoded) }
    }

    @Test
    fun `tampered or damaged text is rejected`() {
        val encoded = cipher.encrypt("secret")
        val tampered = encoded.dropLast(4) + if (encoded.endsWith("AAAA")) "BBBB" else "AAAA"
        assertFailsWith<TokenCipherException> { cipher.decrypt(tampered) }
        assertFailsWith<TokenCipherException> { cipher.decrypt("not base64 !!!") }
        assertFailsWith<TokenCipherException> { cipher.decrypt("") }
        assertFailsWith<TokenCipherException> { cipher.decrypt("AAAA") }
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.security.AesGcmTokenCipherTest"`
Expected: FAIL to compile: `Unresolved reference: AesGcmTokenCipher`.

- [ ] **Step 3: Write the implementation**

`TokenCipher.kt`:

```kotlin
package com.chapterplayer.data.security

/** Turns a secret into text that is safe to store, and back. */
interface TokenCipher {
    fun encrypt(plain: String): String

    /** Throws [TokenCipherException] if [encoded] is damaged or was made with another key. */
    fun decrypt(encoded: String): String
}

class TokenCipherException(cause: Throwable? = null) : Exception("Could not decrypt the stored value", cause)
```

`AesGcmTokenCipher.kt`:

```kotlin
package com.chapterplayer.data.security

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.encoding.Base64

/**
 * AES-256-GCM. The stored text is Base64 of a random 12-byte IV followed by the ciphertext
 * and its authentication tag, so damaged or edited text fails to decrypt.
 */
class AesGcmTokenCipher(private val key: () -> SecretKey) : TokenCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return Base64.encode(iv + cipher.doFinal(plain.encodeToByteArray()))
    }

    override fun decrypt(encoded: String): String {
        try {
            val data = Base64.decode(encoded)
            if (data.size <= IV_BYTES) throw TokenCipherException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
            return cipher.doFinal(data, IV_BYTES, data.size - IV_BYTES).decodeToString()
        } catch (e: TokenCipherException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw TokenCipherException(e)
        } catch (e: IllegalArgumentException) { // bad Base64
            throw TokenCipherException(e)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.security.AesGcmTokenCipherTest"`
Expected: PASS, 7 tests. If `Base64.decode("not base64 !!!")` throws a different exception type, widen the second `catch` to `RuntimeException` and re-run.

- [ ] **Step 5: Commit** `git add shared/src && git commit -m "Stage 2: add the AES-GCM token cipher"`

### Task 1.3: Credential store

**Files:**
- Create: `shared/src/commonMain/kotlin/com/chapterplayer/data/security/CredentialStore.kt`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/security/CredentialStoreTest.kt`

**Interfaces:**
- Consumes: `TokenCipher` from Task 1.2.
- Produces: `interface CredentialStore { suspend fun get(serverId: String): String?; suspend fun put(serverId: String, token: String); suspend fun remove(serverId: String) }` and `class DataStoreCredentialStore(store: DataStore<Preferences>, cipher: TokenCipher) : CredentialStore`. `get` returns `null` when nothing is stored or the stored value cannot be decrypted.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.chapterplayer.data.security

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialStoreTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    // One fixed key per cipher. (A lambda that builds a new key on every call could never decrypt.)
    private fun cipher(key: SecretKey = newKey()) = AesGcmTokenCipher { key }
    private fun tempFile(): File = File.createTempFile("credentials", ".preferences_pb").also { it.delete(); it.deleteOnExit() }
    private fun TestScope.dataStore(file: File) = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })

    @Test
    fun `a saved token can be read back`() = runTest {
        val store = DataStoreCredentialStore(dataStore(tempFile()), cipher())

        store.put("srv-1", "tok-one")

        assertEquals("tok-one", store.get("srv-1"))
    }

    @Test
    fun `an unknown server has no token`() = runTest {
        val store = DataStoreCredentialStore(dataStore(tempFile()), cipher())
        assertNull(store.get("nope"))
    }

    @Test
    fun `two servers with the same token keep separate entries`() = runTest {
        val store = DataStoreCredentialStore(dataStore(tempFile()), cipher())

        store.put("a", "same")
        store.put("b", "same")
        store.remove("a")

        assertNull(store.get("a"))
        assertEquals("same", store.get("b"))
    }

    @Test
    fun `replacing a token keeps only the new one`() = runTest {
        val store = DataStoreCredentialStore(dataStore(tempFile()), cipher())

        store.put("a", "old")
        store.put("a", "new")

        assertEquals("new", store.get("a"))
    }

    @Test
    fun `the file on disk does not contain the token`() = runTest {
        val file = tempFile()
        val store = DataStoreCredentialStore(dataStore(file), cipher())

        store.put("a", "plain-text-token-1234567890")

        assertTrue(!file.readBytes().decodeToString().contains("plain-text-token"))
    }

    @Test
    fun `a token that cannot be decrypted reads as missing`() = runTest {
        val file = tempFile()
        val raw = dataStore(file)
        DataStoreCredentialStore(raw, cipher()).put("a", "secret")

        // Same file, but the Keystore key has been lost and replaced.
        val afterKeyLoss = DataStoreCredentialStore(raw, cipher())

        assertNull(afterKeyLoss.get("a"))
    }

    @Test
    fun `a damaged stored value reads as missing`() = runTest {
        val raw = dataStore(tempFile())
        raw.edit { it[stringPreferencesKey("token_a")] = "garbage!!" }

        assertNull(DataStoreCredentialStore(raw, cipher()).get("a"))
        assertEquals(1, raw.data.first().asMap().size)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.security.CredentialStoreTest"`
Expected: FAIL to compile: `Unresolved reference: DataStoreCredentialStore`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.chapterplayer.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first

/** Holds the secret that signs the app in to each server, apart from the server list itself. */
interface CredentialStore {
    /** The token for [serverId], or null if none is stored or it can no longer be decrypted. */
    suspend fun get(serverId: String): String?
    suspend fun put(serverId: String, token: String)
    suspend fun remove(serverId: String)
}

class DataStoreCredentialStore(
    private val store: DataStore<Preferences>,
    private val cipher: TokenCipher
) : CredentialStore {

    private fun key(serverId: String) = stringPreferencesKey("token_$serverId")

    override suspend fun get(serverId: String): String? {
        val encoded = store.data.first()[key(serverId)] ?: return null
        return try {
            cipher.decrypt(encoded)
        } catch (_: TokenCipherException) {
            null // the key was lost or the value is damaged: the user signs in again
        }
    }

    override suspend fun put(serverId: String, token: String) {
        val encoded = cipher.encrypt(token)
        store.edit { it[key(serverId)] = encoded }
    }

    override suspend fun remove(serverId: String) {
        store.edit { it.remove(key(serverId)) }
    }
}
```

- [ ] **Step 4: Run the tests to see them pass**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.security.CredentialStoreTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit** `git add shared/src && git commit -m "Stage 2: add the encrypted credential store"`

### Task 1.4: Android pieces (DataStore files and the Keystore key)

**Files:**
- Create: `shared/src/androidMain/kotlin/com/chapterplayer/data/security/AndroidKeystoreKey.kt`
- Create: `shared/src/androidMain/kotlin/com/chapterplayer/data/DataStores.kt`

**Interfaces:**
- Produces: `object AndroidKeystoreKey { fun getOrCreate(): SecretKey }` (an AES-256 key named `chapterplayer_credentials_v1` in the Android Keystore) and `fun createPreferencesDataStore(context: Context, name: String): DataStore<Preferences>`.

These need a real Android runtime, so they are checked on the emulator in Task 1.7, not by host tests.

- [ ] **Step 1: Write `AndroidKeystoreKey.kt`**

```kotlin
package com.chapterplayer.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The AES key that encrypts stored tokens. It lives in the Android Keystore and never leaves it. */
object AndroidKeystoreKey {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "chapterplayer_credentials_v1"

    fun getOrCreate(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
```

- [ ] **Step 2: Write `DataStores.kt`**

```kotlin
package com.chapterplayer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile

/** One DataStore file per purpose, so the encrypted credentials stay apart from ordinary settings. */
fun createPreferencesDataStore(context: Context, name: String): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(produceFile = { context.preferencesDataStoreFile(name) })
```

- [ ] **Step 3: Compile**

Run: `./gradlew :shared:compileAndroidMain` (if that task name is not found, run `./gradlew :shared:assemble`).
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit** `git add shared/src && git commit -m "Stage 2: add the Keystore key and the DataStore files"`

### Task 1.5: Servers keep their tokens in the credential store

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/data/local/dao/ServerConfigDao.kt` (add `getAll`)
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/data/local/entity/ServerConfigEntity.kt`
- Modify: `shared/src/androidMain/kotlin/com/chapterplayer/data/repository/ServerRepositoryImpl.kt`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/data/repository/ServerRepositoryTest.kt`

**Interfaces:**
- Consumes: `CredentialStore` (Task 1.3).
- Produces: `ServerRepositoryImpl(serverConfigDao: ServerConfigDao, credentials: CredentialStore)`. Behaviour: `save` writes the token to the credential store and stores an empty string in the `token` column; reads put the token back; `delete` removes it; the first use moves any tokens still in the database into the store. `ServerConfigEntity.toDomain(token: String)` and `ServerConfigEntity.fromDomain(config)` (which stores `""`). The Room schema is unchanged: the `token` column stays, empty, and is dropped by a later migration.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.ServerConfigEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerRepositoryTest {

    private class FakeDao(initial: List<ServerConfigEntity> = emptyList()) : ServerConfigDao {
        val rows = MutableStateFlow(initial.associateBy { it.id })
        override fun observeAll(): Flow<List<ServerConfigEntity>> = rows.map { it.values.sortedBy { e -> e.name } }
        override suspend fun getAll(): List<ServerConfigEntity> = rows.value.values.toList()
        override suspend fun getById(id: String): ServerConfigEntity? = rows.value[id]
        override fun observeBySource(source: String): Flow<List<ServerConfigEntity>> = rows.map { it.values.filter { e -> e.source == source } }
        override suspend fun insert(config: ServerConfigEntity) { rows.value = rows.value + (config.id to config) }
        override suspend fun update(config: ServerConfigEntity) { insert(config) }
        override suspend fun delete(config: ServerConfigEntity) { rows.value = rows.value - config.id }
        override suspend fun deleteById(id: String) { rows.value = rows.value - id }
    }

    private class FakeCredentials(val tokens: MutableMap<String, String> = mutableMapOf(), var failPutAfter: Int = Int.MAX_VALUE) : CredentialStore {
        private var puts = 0
        override suspend fun get(serverId: String) = tokens[serverId]
        override suspend fun put(serverId: String, token: String) {
            if (puts++ >= failPutAfter) error("interrupted")
            tokens[serverId] = token
        }
        override suspend fun remove(serverId: String) { tokens.remove(serverId) }
    }

    private fun entity(id: String, token: String) = ServerConfigEntity(id, "ABS", "Server $id", "http://s.test", token, null)
    private fun config(id: String, token: String) = ServerConfig(id, ServerSource.ABS, "Server $id", "http://s.test", token, null)

    @Test
    fun `saving keeps the token out of the database`() = runTest {
        val dao = FakeDao(); val creds = FakeCredentials()

        ServerRepositoryImpl(dao, creds).save(config("a", "tok-a"))

        assertEquals("", dao.rows.value.getValue("a").token)
        assertEquals("tok-a", creds.tokens["a"])
    }

    @Test
    fun `reading gives the token back`() = runTest {
        val repo = ServerRepositoryImpl(FakeDao(), FakeCredentials())
        repo.save(config("a", "tok-a"))

        assertEquals("tok-a", repo.getById("a")?.token)
        assertEquals(listOf("tok-a"), repo.observeAll().first().map { it.token })
    }

    @Test
    fun `deleting a server removes its token`() = runTest {
        val dao = FakeDao(); val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(dao, creds)
        repo.save(config("a", "same")); repo.save(config("b", "same"))

        repo.delete("a")

        assertNull(creds.tokens["a"])
        assertEquals("same", creds.tokens["b"])
        assertNull(repo.getById("a"))
    }

    @Test
    fun `a token the keystore can no longer decrypt reads as empty`() = runTest {
        val dao = FakeDao(listOf(entity("a", ""))) // migrated earlier, then the key was lost
        val repo = ServerRepositoryImpl(dao, FakeCredentials())

        assertEquals("", repo.getById("a")?.token)
    }

    @Test
    fun `tokens already in the database move to the credential store on first use`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a"), entity("b", "legacy-b")))
        val creds = FakeCredentials()
        val repo = ServerRepositoryImpl(dao, creds)

        assertEquals("legacy-a", repo.getById("a")?.token)

        assertEquals(mapOf("a" to "legacy-a", "b" to "legacy-b"), creds.tokens)
        assertEquals(listOf("", ""), dao.rows.value.values.map { it.token })
    }

    @Test
    fun `an interrupted migration finishes next time without losing a token`() = runTest {
        val dao = FakeDao(listOf(entity("a", "legacy-a"), entity("b", "legacy-b")))
        val creds = FakeCredentials(failPutAfter = 1) // the second write fails

        runCatching { ServerRepositoryImpl(dao, creds).getById("a") }
        creds.failPutAfter = Int.MAX_VALUE
        val second = ServerRepositoryImpl(dao, creds)

        assertEquals("legacy-b", second.getById("b")?.token)
        assertEquals("legacy-a", second.getById("a")?.token)
        assertEquals(mapOf("a" to "legacy-a", "b" to "legacy-b"), creds.tokens)
        assertEquals(listOf("", ""), dao.rows.value.values.map { it.token })
    }

    @Test
    fun `a token with unusual characters is kept exactly`() = runTest {
        val repo = ServerRepositoryImpl(FakeDao(), FakeCredentials())
        val odd = "e30=.\"quoted\" 'x' \\ \n tab\t ✓ ${"y".repeat(5000)}"

        repo.save(config("a", odd))

        assertEquals(odd, repo.getById("a")?.token)
    }
}
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.repository.ServerRepositoryTest"`
Expected: FAIL to compile (`getAll` is not an override of `ServerConfigDao`; the `ServerRepositoryImpl` constructor takes one argument).

- [ ] **Step 3: Add `getAll` to the DAO**

In `ServerConfigDao.kt`, after `observeAll`, add:

```kotlin
    @Query("SELECT * FROM server_configs")
    suspend fun getAll(): List<ServerConfigEntity>
```

- [ ] **Step 4: Change the entity**

Replace the body of `ServerConfigEntity` with the version below. Only `toDomain`/`fromDomain` and the comment on `token` change.

```kotlin
@Entity(tableName = "server_configs")
data class ServerConfigEntity(
    @PrimaryKey val id: String,
    val source: String,           // ServerSource.name()
    val name: String,
    val baseUrl: String,
    /**
     * Empty. Tokens live in the encrypted credential store. The column stays so the database
     * schema does not change; a later migration drops it. Older installs still hold the token
     * here until [com.chapterplayer.data.repository.ServerRepositoryImpl] moves it.
     */
    val token: String,
    val libraryId: String?
) {
    fun toDomain(token: String): ServerConfig = ServerConfig(
        id = id,
        source = ServerSource.valueOf(source),
        name = name,
        baseUrl = baseUrl,
        token = token,
        libraryId = libraryId
    )

    companion object {
        /** The row for [config]. The token is deliberately left out. */
        fun fromDomain(config: ServerConfig): ServerConfigEntity = ServerConfigEntity(
            id = config.id,
            source = config.source.name,
            name = config.name,
            baseUrl = config.baseUrl,
            token = "",
            libraryId = config.libraryId
        )
    }
}
```

- [ ] **Step 5: Rewrite `ServerRepositoryImpl`**

```kotlin
package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.ServerConfigDao
import com.chapterplayer.data.local.entity.ServerConfigEntity
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.security.CredentialStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Server list in Room, tokens in the encrypted [CredentialStore]. Installs from before this
 * change still hold tokens in the database; the first use moves them.
 */
class ServerRepositoryImpl(
    private val serverConfigDao: ServerConfigDao,
    private val credentials: CredentialStore
) : ServerRepository {

    private val migrationLock = Mutex()
    private var migrated = false

    override fun observeAll(): Flow<List<ServerConfig>> = flow {
        ensureTokensMigrated()
        emitAll(serverConfigDao.observeAll().map { rows -> rows.map { it.withToken() } })
    }

    override suspend fun getById(id: String): ServerConfig? {
        ensureTokensMigrated()
        return serverConfigDao.getById(id)?.withToken()
    }

    override suspend fun save(config: ServerConfig) {
        ensureTokensMigrated()
        credentials.put(config.id, config.token)
        serverConfigDao.insert(ServerConfigEntity.fromDomain(config))
    }

    override suspend fun delete(id: String) {
        serverConfigDao.deleteById(id)
        credentials.remove(id)
    }

    // A row that still holds a token (not migrated yet) wins over the store.
    private suspend fun ServerConfigEntity.withToken(): ServerConfig =
        toDomain(token.ifEmpty { credentials.get(id).orEmpty() })

    /**
     * Copies each token from the database into the store, then blanks the column. Safe to
     * repeat: a row is only blanked after its token has been stored, so an interruption
     * loses nothing and the next run finishes the job.
     */
    private suspend fun ensureTokensMigrated() {
        if (migrated) return
        migrationLock.withLock {
            if (migrated) return
            serverConfigDao.getAll().filter { it.token.isNotEmpty() }.forEach { row ->
                credentials.put(row.id, row.token)
                serverConfigDao.insert(row.copy(token = ""))
            }
            migrated = true
        }
    }
}
```

Add `import kotlinx.coroutines.flow.emitAll` to the imports.

- [ ] **Step 6: Run the tests to see them pass**

Run: `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.data.repository.ServerRepositoryTest"`
Expected: PASS, 7 tests. Then run `./gradlew :shared:testAndroidHostTest` (the whole suite): expected PASS. Compile errors elsewhere mean something else called `toDomain()` with no argument: change each to pass the token it has.

- [ ] **Step 7: Mutation check.** In `ensureTokensMigrated`, swap the two lines inside `forEach` (blank the column before storing the token). Run `ServerRepositoryTest`: `an interrupted migration finishes next time without losing a token` must FAIL. Undo the swap and re-run to see it pass again.

- [ ] **Step 8: Commit** `git add shared/src && git commit -m "Stage 2: keep server tokens in the encrypted credential store"`

### Task 1.6: Koin wiring

**Files:**
- Modify: `shared/src/androidMain/kotlin/com/chapterplayer/di/KoinModules.kt`
- Modify: `shared/src/androidHostTest/kotlin/com/chapterplayer/di/KoinModulesTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1.1 to 1.5.
- Produces: `securityModule` (the two DataStores, `TokenCipher`, `CredentialStore`, `AppSettings`), added to `sharedModules`; the qualifiers `SettingsStoreQualifier` and `CredentialsStoreQualifier`.

- [ ] **Step 1: Update the test first.** In `KoinModulesTest`, change the repository module test and the view model test so they include `securityModule`, and add one for the module itself:

```kotlin
    @Test
    fun `the security module resolves`() {
        securityModule.verify(extraTypes = providedByApp)
    }

    @Test
    fun `the repository module resolves`() {
        module { includes(databaseModule, networkModule, securityModule, repositoryModule) }.verify(extraTypes = providedByApp)
    }

    @Test
    fun `every screen's view model can be built`() {
        module { includes(databaseModule, networkModule, securityModule, repositoryModule, viewModelModule) }
            .verify(extraTypes = providedByApp)
    }
```

- [ ] **Step 2: Run to see it fail.** `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.di.KoinModulesTest"`. Expected: FAIL to compile (`securityModule` unresolved).

- [ ] **Step 3: Add the module.** In `KoinModules.kt` add the imports (`createPreferencesDataStore`, `AppSettings`, `DataStoreAppSettings`, `AesGcmTokenCipher`, `AndroidKeystoreKey`, `CredentialStore`, `DataStoreCredentialStore`, `TokenCipher`), then:

```kotlin
val SettingsStoreQualifier = named("settings")
val CredentialsStoreQualifier = named("credentials")

val securityModule = module {
    single(SettingsStoreQualifier) { createPreferencesDataStore(androidContext(), "settings") }
    single(CredentialsStoreQualifier) { createPreferencesDataStore(androidContext(), "credentials") }
    single<TokenCipher> { AesGcmTokenCipher { AndroidKeystoreKey.getOrCreate() } }
    single<CredentialStore> { DataStoreCredentialStore(get(CredentialsStoreQualifier), get()) }
    single<AppSettings> { DataStoreAppSettings(get(SettingsStoreQualifier)) }
}
```

Change the last line to `val sharedModules = listOf(databaseModule, networkModule, securityModule, sourceModule, repositoryModule, viewModelModule)`.

- [ ] **Step 4: Run all host tests.** `./gradlew :shared:testAndroidHostTest`. Expected: PASS. If `verify()` complains about a type such as `DataStore`, add it to `providedByApp` with a comment (the module builds it by hand, as it does for `HttpClientEngine`).

- [ ] **Step 5: Commit** `git add shared/src && git commit -m "Stage 2: wire the credential store and settings into Koin"`

### Task 1.7: Emulator check, release check and pull request

**Files:** none (verification), then `docs/ROADMAP.md` and `CLAUDE.md`.

- [ ] **Step 1: Prove the upgrade path.** The emulator already has the previous debug build with a signed-in server whose token is in the database.
  - Pull the database and confirm a non-empty `token`: `adb exec-out run-as com.chapterplayer.debug cat databases/chapter_player.db > "$SP/db.db"`, then check with Python `sqlite3` (print only whether the token column is empty; never print it).
  - Build and install the new debug build over it: `./gradlew :androidApp:assembleDebug` then `adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
  - Open the app, go to Library, tap refresh. Expected: the books load (the token was found).
  - Pull the database again. Expected: the `token` column is empty for every server.
  - Check the credential file contains no plain text token: `adb exec-out run-as com.chapterplayer.debug cat files/datastore/credentials.preferences_pb` and search it for the first 10 characters of the token. Expected: no match.
- [ ] **Step 2: Prove a fresh sign-in.** Delete the server, add it again with its token, refresh. Expected: books load and the token column is empty.
- [ ] **Step 3: Prove the lost-key path.** In the app's shell run `adb shell run-as com.chapterplayer.debug rm files/datastore/credentials.preferences_pb`, force-stop and reopen the app. Expected: no crash; the server is listed; refresh reports an error instead of loading (empty token); editing the server shows an empty token field.
- [ ] **Step 4: Release build.** `./gradlew :androidApp:assembleRelease`, sign it with the debug keystore, install, add a server, refresh. Expected: books load; no crash. This proves R8 does not strip the Keystore or DataStore classes.
- [ ] **Step 5: Privacy scan** of `git diff main` for server addresses, tokens and names. Expected: none.
- [ ] **Step 6: Docs.** In `docs/ROADMAP.md` tick `Move the server token out of the Room database into encrypted storage (before sign-in is built)`. In `CLAUDE.md` Current state, add one line: server tokens live in an AES-GCM encrypted DataStore (`CredentialStore`) keyed by the Android Keystore, settings live in `AppSettings`.
- [ ] **Step 7: Push and open the pull request** titled `Stage 2: secure token storage and the settings store (step 1)`, with checked and not-checked lists, ending with the PR attribution line. Wait for CI. Do not merge without the owner's go-ahead.

---

# Step 2: Light theme and the Dark / Light / Follow system choice

Branch `stage-2/theme` (from `main` after step 1 merges).

### Task 2.1: Resolving the theme

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/ui/theme/Theme.kt` (add `resolveDarkTheme`)
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/ui/theme/ThemeModeTest.kt`

**Interfaces:**
- Consumes: `ThemeMode` (Task 1.1).
- Produces: `fun resolveDarkTheme(mode: ThemeMode, systemIsDark: Boolean): Boolean`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.chapterplayer.ui.theme

import com.chapterplayer.data.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModeTest {
    @Test
    fun `dark and light ignore the system setting`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemIsDark = false))
        assertEquals(true, resolveDarkTheme(ThemeMode.DARK, systemIsDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemIsDark = false))
        assertEquals(false, resolveDarkTheme(ThemeMode.LIGHT, systemIsDark = true))
    }

    @Test
    fun `follow system uses the system setting`() {
        assertEquals(true, resolveDarkTheme(ThemeMode.SYSTEM, systemIsDark = true))
        assertEquals(false, resolveDarkTheme(ThemeMode.SYSTEM, systemIsDark = false))
    }
}
```

- [ ] **Step 2: Run to see it fail.** `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.ui.theme.ThemeModeTest"`. Expected: FAIL to compile (`resolveDarkTheme` unresolved).

- [ ] **Step 3: Implement.** Add to `Theme.kt` (top level, above `ChapterPlayerTheme`), importing `com.chapterplayer.data.settings.ThemeMode`:

```kotlin
/** Whether the app should draw in its dark colours. */
fun resolveDarkTheme(mode: ThemeMode, systemIsDark: Boolean): Boolean = when (mode) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> systemIsDark
}
```

- [ ] **Step 4: Run to see it pass.** Same command. Expected: PASS, 2 tests.
- [ ] **Step 5: Commit** `git checkout -b stage-2/theme && git add shared/src && git commit -m "Stage 2: add theme resolution"`

### Task 2.2: The light palette, checked for contrast

**Files:**
- Create: `shared/src/commonMain/kotlin/com/chapterplayer/ui/theme/Palette.kt`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/ui/theme/PaletteContrastTest.kt`

**Interfaces:**
- Produces: `data class ChapterPlayerPalette(background, surfaceElevated, surfaceSecondary, navBar, amber, amberDim, textPrimary, textSecondary, textTertiary, divider, error, onAmber: Color)`, `val DarkPalette`, `val LightPalette`, `val LocalPalette: ProvidableCompositionLocal<ChapterPlayerPalette>` (default `DarkPalette`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.chapterplayer.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

class PaletteContrastTest {

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun check(name: String, palette: ChapterPlayerPalette) {
        val surfaces = listOf(palette.background, palette.surfaceElevated, palette.surfaceSecondary)
        surfaces.forEach { s ->
            assertTrue(contrast(palette.textPrimary, s) >= 7.0, "$name primary text on a surface")
            assertTrue(contrast(palette.textSecondary, s) >= 4.5, "$name secondary text on a surface")
            assertTrue(contrast(palette.amber, s) >= 4.5, "$name amber text on a surface")
            assertTrue(contrast(palette.textTertiary, s) >= 3.0, "$name hint text on a surface")
        }
        assertTrue(contrast(palette.onAmber, palette.amber) >= 4.5, "$name text on an amber button")
        assertTrue(contrast(palette.textSecondary, palette.navBar) >= 4.5, "$name nav bar labels")
        assertTrue(contrast(palette.amber, palette.navBar) >= 4.5, "$name selected nav item")
    }

    @Test fun `the dark palette is readable`() = check("dark", DarkPalette)
    @Test fun `the light palette is readable`() = check("light", LightPalette)
}
```

- [ ] **Step 2: Run to see it fail.** `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.ui.theme.PaletteContrastTest"`. Expected: FAIL to compile.

- [ ] **Step 3: Write `Palette.kt`.** The dark values are the existing constants from `Color.kt`.

```kotlin
package com.chapterplayer.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Every colour the app draws from, for one theme. */
@Immutable
data class ChapterPlayerPalette(
    val background: Color,
    val surfaceElevated: Color,
    val surfaceSecondary: Color,
    val navBar: Color,
    val amber: Color,
    val amberDim: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val error: Color,
    /** Text or icons drawn on an amber button. */
    val onAmber: Color
)

val DarkPalette = ChapterPlayerPalette(
    background = Color(0xFF0E0D0B),
    surfaceElevated = Color(0xFF1A1916),
    surfaceSecondary = Color(0xFF252320),
    navBar = Color(0xFF2B261A),
    amber = Color(0xFFE8B84B),
    amberDim = Color(0xFF8A6E2D),
    textPrimary = Color(0xFFF0EDE8),
    textSecondary = Color(0xFFABA89F),
    textTertiary = Color(0xFF6B6860),
    divider = Color(0xFF2A2926),
    error = Color(0xFFCF6679),
    onAmber = Color(0xFF0E0D0B)
)

/** The amber is darker than in the dark theme so amber text stays readable on light surfaces. */
val LightPalette = ChapterPlayerPalette(
    background = Color(0xFFF7F5F0),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceSecondary = Color(0xFFEBE8E0),
    navBar = Color(0xFFEFE7D2),
    amber = Color(0xFF9A6500),
    amberDim = Color(0xFFC9A55B),
    textPrimary = Color(0xFF1D1B17),
    textSecondary = Color(0xFF5F5A50),
    textTertiary = Color(0xFF7A7568),
    divider = Color(0xFFDDD8CC),
    error = Color(0xFFB3261E),
    onAmber = Color(0xFFFFFFFF)
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
```

- [ ] **Step 4: Run to see it pass.** Expected: PASS, 2 tests. If a check fails, darken the failing light colour a little (for example amber to `0xFF8F5D00`) and re-run until it passes. Do not lower a threshold.
- [ ] **Step 5: Commit** `git add shared/src && git commit -m "Stage 2: add the dark and light palettes with contrast checks"`

### Task 2.3: Make the existing colour names theme-aware

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/ui/theme/Color.kt`
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/ui/theme/Theme.kt`
- Modify: any screen file the compiler flags (expected: a few lines in `ui/components` and `ui/library`).

**Interfaces:**
- Consumes: `ChapterPlayerPalette`, `LocalPalette`, `resolveDarkTheme`, `ThemeMode`.
- Produces: `ChapterPlayerTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, content)`. The names `BackgroundDeep`, `SurfaceElevated`, `SurfaceSecondary`, `NavBarSurface`, `AmberGold`, `AmberDim`, `TextPrimary`, `TextSecondary`, `TextTertiary`, `Divider`, `ErrorRed` stay, but become read-only composable properties, so call sites inside composables do not change. `AbsGreen`, `AbsGreenDim`, `PlexPurple*` and `SuccessGreen` stay fixed colours (brand and status colours).

- [ ] **Step 1: Rewrite `Color.kt`.** Keep the section comments short. Replace the palette constants with:

```kotlin
package com.chapterplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

// These names were fixed dark colours. They now follow the current theme's palette, so every
// screen that uses them works in both themes. Read them inside composables; code that runs
// later (a draw lambda) must read the colour first and use the local value.

val BackgroundDeep: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.background
val SurfaceElevated: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceElevated
val SurfaceSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surfaceSecondary
val NavBarSurface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.navBar
val AmberGold: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amber
val AmberDim: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.amberDim
val TextPrimary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textPrimary
val TextSecondary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textSecondary
val TextTertiary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.textTertiary
val Divider: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.divider
val ErrorRed: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.error

// ─── Source badge and status colours: the same in both themes ──────────────
val AbsGreen = Color(0xFF2ECC71)
val AbsGreenDim = Color(0xFF1A5E38)
val PlexPurple = Color(0xFFE5A00D)
val PlexPurpleActual = Color(0xFF9B59B6)
val PlexPurpleDim = Color(0xFF4A2C6E)
val SuccessGreen = Color(0xFF4CAF50)
```

Remove the `NavBarSurface` explanation comment's claim about elevation only if it no longer applies; keep a one-line note that the nav bar colour is set explicitly.

- [ ] **Step 2: Rewrite `Theme.kt`.** Keep `resolveDarkTheme` from Task 2.1. Replace the colour scheme and `ChapterPlayerTheme`:

```kotlin
// The two schemes take the same values; Material needs the dark or light builder so that
// components it styles itself (ripples, text fields) pick the right defaults.
private fun colorSchemeFor(p: ChapterPlayerPalette, dark: Boolean): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = p.amber, onPrimary = p.onAmber, primaryContainer = p.amberDim, onPrimaryContainer = p.amber,
            secondary = p.textSecondary, onSecondary = p.onAmber,
            secondaryContainer = p.surfaceSecondary, onSecondaryContainer = p.textPrimary,
            background = p.background, onBackground = p.textPrimary,
            surface = p.surfaceElevated, onSurface = p.textPrimary,
            surfaceVariant = p.surfaceSecondary, onSurfaceVariant = p.textSecondary,
            error = p.error, onError = Color.White,
            outline = p.divider, outlineVariant = p.textTertiary
        )
    } else {
        lightColorScheme(
            primary = p.amber, onPrimary = p.onAmber, primaryContainer = p.amberDim, onPrimaryContainer = p.amber,
            secondary = p.textSecondary, onSecondary = p.onAmber,
            secondaryContainer = p.surfaceSecondary, onSecondaryContainer = p.textPrimary,
            background = p.background, onBackground = p.textPrimary,
            surface = p.surfaceElevated, onSurface = p.textPrimary,
            surfaceVariant = p.surfaceSecondary, onSurfaceVariant = p.textSecondary,
            error = p.error, onError = Color.White,
            outline = p.divider, outlineVariant = p.textTertiary
        )
    }
```

Imports: `androidx.compose.material3.ColorScheme`, `darkColorScheme`, `lightColorScheme`, `androidx.compose.ui.graphics.Color`, `androidx.compose.runtime.CompositionLocalProvider`. Then:

```kotlin
@Composable
fun ChapterPlayerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = colorSchemeFor(palette, dark),
            typography = chapterPlayerTypography(),
            content = content
        )
    }
}
```

Update the file's header comment: the theme now follows the user's choice.

- [ ] **Step 3: Compile and fix what the compiler finds.** Run `./gradlew :shared:compileAndroidMain` (or `:androidApp:assembleDebug`). Each error is a place where a colour name is read outside a composable (for example inside a `drawBehind { }` or a non-composable lambda). Fix each by reading the colour into a local `val` in the composable scope first, then using the local. Do not change colours or layout. Repeat until it builds.
- [ ] **Step 4: Run all host tests.** `./gradlew :shared:testAndroidHostTest`. Expected: PASS.
- [ ] **Step 5: Commit** `git add shared/src && git commit -m "Stage 2: make the theme colours follow the dark or light palette"`

### Task 2.4: The Settings choice and the activity

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/ui/settings/SettingsViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/chapterplayer/ui/settings/SettingsScreen.kt`
- Modify: `androidApp/src/main/kotlin/com/chapterplayer/MainActivity.kt`
- Test: `shared/src/androidHostTest/kotlin/com/chapterplayer/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `AppSettings`, `ThemeMode`.
- Produces: `SettingsViewModel(serverRepository: ServerRepository, appSettings: AppSettings)` with `val themeMode: StateFlow<ThemeMode>` (initial `SYSTEM`) and `fun setThemeMode(mode: ThemeMode)`. An Appearance card at the top of the Settings screen with three radio rows (Dark, Light, Follow system). `MainActivity` applies the stored mode, with matching status and navigation bar icons.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.chapterplayer.ui.settings

import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.repository.ServerRepository
import com.chapterplayer.data.settings.AppSettings
import com.chapterplayer.data.settings.LibraryLayout
import com.chapterplayer.data.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private class FakeSettings : AppSettings {
        val theme = MutableStateFlow(ThemeMode.SYSTEM)
        override val themeMode: Flow<ThemeMode> = theme
        override suspend fun setThemeMode(mode: ThemeMode) { theme.value = mode }
        override val libraryLayout: Flow<LibraryLayout> = flowOf(LibraryLayout.LARGE_GRID)
        override suspend fun setLibraryLayout(layout: LibraryLayout) {}
        override val resumeOnCarConnect: Flow<Boolean> = flowOf(true)
        override suspend fun setResumeOnCarConnect(enabled: Boolean) {}
    }

    private class FakeServers : ServerRepository {
        override fun observeAll(): Flow<List<ServerConfig>> = flowOf(emptyList())
        override suspend fun getById(id: String): ServerConfig? = null
        override suspend fun save(config: ServerConfig) {}
        override suspend fun delete(id: String) {}
    }

    @BeforeTest fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `the theme starts as follow system`() = runTest {
        val vm = SettingsViewModel(FakeServers(), FakeSettings())
        assertEquals(ThemeMode.SYSTEM, vm.themeMode.value)
    }

    @Test
    fun `choosing a theme saves it and updates the screen`() = runTest {
        val settings = FakeSettings()
        val vm = SettingsViewModel(FakeServers(), settings)

        vm.setThemeMode(ThemeMode.LIGHT)

        assertEquals(ThemeMode.LIGHT, settings.theme.value)
        assertEquals(ThemeMode.LIGHT, vm.themeMode.value)
    }
}
```

- [ ] **Step 2: Run to see it fail.** `./gradlew :shared:testAndroidHostTest --tests "com.chapterplayer.ui.settings.SettingsViewModelTest"`. Expected: FAIL to compile (constructor takes one argument; no `themeMode`).

- [ ] **Step 3: Change `SettingsViewModel`.** Add the import of `AppSettings` and `ThemeMode`, change the class to:

```kotlin
class SettingsViewModel(
    private val serverRepository: ServerRepository,
    private val appSettings: AppSettings
) : ViewModel() {

    // (settingsUiState and deleteServer stay as they are)

    val themeMode: StateFlow<ThemeMode> =
        appSettings.themeMode.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ThemeMode.SYSTEM
        )

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { appSettings.setThemeMode(mode) }
    }
}
```

- [ ] **Step 4: Run to see it pass.** Same command. Expected: PASS, 2 tests.
- [ ] **Step 5: Add the Appearance card to `SettingsScreen`.** Collect `viewModel.themeMode` with `collectAsStateWithLifecycle()`. At the top of the screen's scrolling content (above "Connected Servers"), add a section titled "Appearance" with a `Card` (colours `SurfaceElevated`) holding a `Column(Modifier.selectableGroup())` of three rows. Each row is a `Row` with `Modifier.selectable(selected = ..., role = Role.RadioButton, onClick = { viewModel.setThemeMode(mode) })` containing a `RadioButton(selected = ..., onClick = null, colors = RadioButtonDefaults.colors(selectedColor = AmberGold))` and a `Text` of "Dark", "Light" or "Follow system", in `TextSecondary` for the unselected rows. Use the same padding and heading style as the existing "Connected Servers" heading. Add `import androidx.compose.ui.semantics.Role` and `androidx.compose.foundation.selection.selectable`.
- [ ] **Step 6: Change `MainActivity`.**

```kotlin
class MainActivity : ComponentActivity() {

    private val settings: AppSettings by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Read the saved choice before the first frame so a dark-theme user never sees a light flash.
        val saved = runBlocking { settings.themeMode.first() }
        setContent {
            val mode by settings.themeMode.collectAsState(initial = saved)
            val dark = resolveDarkTheme(mode, isSystemInDarkTheme())
            DisposableEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                onDispose { }
            }
            ChapterPlayerTheme(mode) {
                AppNavigation()
            }
        }
    }
}
```

Imports: `android.graphics.Color`, `androidx.activity.SystemBarStyle`, `androidx.compose.foundation.isSystemInDarkTheme`, `androidx.compose.runtime.DisposableEffect`, `androidx.compose.runtime.collectAsState`, `androidx.compose.runtime.getValue`, `com.chapterplayer.data.settings.AppSettings`, `com.chapterplayer.ui.theme.resolveDarkTheme`, `kotlinx.coroutines.flow.first`, `kotlinx.coroutines.runBlocking`, `org.koin.android.ext.android.inject`. Remove the old `enableEdgeToEdge()` call.
- [ ] **Step 7: Build and run all tests.** `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug`. Expected: PASS and BUILD SUCCESSFUL. If a Koin `verify()` test fails because `SettingsViewModel` now needs `AppSettings`, the test modules must include `securityModule` (done in Task 1.6).
- [ ] **Step 8: Commit** `git add shared/src androidApp/src && git commit -m "Stage 2: choose Dark, Light or Follow system in Settings"`

### Task 2.5: Emulator and release check, then the pull request

- [ ] **Step 1: Dark.** Install the debug build. Settings, choose Dark. Screenshot Library, Settings and the server form. Expected: identical to the previous dark look (compare with an earlier screenshot).
- [ ] **Step 2: Light.** Choose Light. Screenshot the same three screens plus the bottom bar. Expected: light backgrounds, dark text, amber accent readable, status bar icons dark, nothing white-on-white or black-on-black. Fix any hard-coded colour that does not follow (search the screen files for `Color(0x`, `Color.White` and `Color.Black`).
- [ ] **Step 3: Follow system.** Choose Follow system. Run `adb shell cmd uimode night yes` and `adb shell cmd uimode night no`. Expected: the app switches both ways while open.
- [ ] **Step 4: Persistence.** Choose Light, force-stop the app, reopen. Expected: it opens light with no dark flash.
- [ ] **Step 5: Release build.** Assemble, sign, install the release build, repeat Step 2. Expected: no crash and the same look.
- [ ] **Step 6: Docs.** Tick `Light theme and a Dark / Light / Follow system setting` in `docs/ROADMAP.md`. In `CLAUDE.md` replace "dark theme only" wording if present and add that the theme follows `AppSettings.themeMode`.
- [ ] **Step 7: Push and open the pull request** titled `Stage 2: light theme and the theme choice (step 2)` with screenshots described (dark, light and system), checked and not-checked lists. Wait for CI. Do not merge without the owner's go-ahead.

---

# Steps 3 to 9: outline

Each step gets its own detailed plan (same format) right before it starts, because it depends on what the earlier steps settle and, for steps 3 and 6, on checking real server behaviour first. Branches are `stage-2/<feature>`.

3. **Sign-in and the library picker** (`stage-2/sign-in`). Username and password sign-in, API token under Advanced, library picker, the stored "turned off" libraries per server (database migration 1 to 2, which also drops the empty `token` column). Verify first, against the owner's server (2.37.1): the login and token-refresh calls and what they return. Show a clear "sign in again" message when a token is missing (the lost-key case from step 1). Fixtures stay made up.
4. **Navigation shell and Library** (`stage-2/library`). Four tabs, five Library views, three layouts with the switch, the combined view with the Libraries check-list, the filter chips and Filter sheet, the downloaded-state badges and offline dimming, the Downloads empty state, the per-book download state (always "not downloaded"). The pure logic (filtering, sorting, grouping, letter ranges) is unit tested first.
5. **Book details** (`stage-2/book-details`). Compact header with About, Chapters and Series tabs opening on Chapters; chapters loaded per book from `/api/items/{id}`; scroll to the current chapter.
6. **The playback service** (`stage-2/playback`). Media3 `MediaLibraryService` with one ExoPlayer; the playback session instead of the `/play` address; chapters as positions in the whole book (book position to file and offset is pure logic, unit tested first); speed, sleep timer, skip back 15 / forward 30, lock-screen controls, rewind on resume, pause on disconnect. Verify first: what the playback-session response contains on the owner's server.
7. **Player, mini player and Home Resume card** (`stage-2/player-ui`).
8. **Progress sync and bookmarks** (`stage-2/progress-sync`). Local first, every ~10 seconds, offline queue with backoff, last-writer rule by timestamp, bookmarks synced with Audiobookshelf.
9. **Android Auto** (`stage-2/android-auto`). Browse tree (Continue, Recently added, Library with letter ranges, Downloaded placeholder), chapters as the queue, voice search, resume on connect with the Settings switch (on by default). Test on the Desktop Head Unit, then in a real car. Tag `v0.2.0` after this step.

## Self-review

- **Spec coverage for steps 1 and 2:** secure storage and the preferences store (Tasks 1.1 to 1.7), light palette, the three-way choice, default Follow system, accent readable in both themes (Tasks 2.1 to 2.5). The settings for layout and car resume are created now (Task 1.1) and used by steps 4 and 9. Steps 3 to 9 are outlined, not yet task-level, by design.
- **Placeholders:** none in steps 1 and 2. The one place that says "the compiler finds them" (Task 2.3, Step 3) is a mechanical fix with a stated rule; it cannot be listed ahead because it depends on what the build reports.
- **Type consistency:** `CredentialStore.get/put/remove`, `TokenCipher.encrypt/decrypt`, `AppSettings` members, `ServerConfigEntity.toDomain(token)`, `ServerRepositoryImpl(dao, credentials)` and `SettingsViewModel(serverRepository, appSettings)` are used with the same signatures in every task that mentions them.

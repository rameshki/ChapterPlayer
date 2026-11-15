package com.chapterplayer.data.local

import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real migrations: a database file is created at an exported schema version (the tables and the identity hash exactly as
 * `shared/schemas/<version>.json` records them, which is what Room's MigrationTestHelper does), rows are written into it,
 * and then the app's ChapterPlayerDatabase opens it at the current version through Room's own migration path (the
 * generated auto-migrations plus the hand-written 8 to 9 one). Room validates the migrated schema on open, and the rows
 * are read back with plain SQL to show they kept their values.
 *
 * How it differs from the app:
 * - Room's MigrationTestHelper is not used. Its Android variant (the one a host test resolves) needs an Instrumentation
 *   and reads the schemas from the test APK's assets, so it only runs on a device. The few lines it would do are below
 *   (`createAt`).
 * - The database runs on the bundled SQLite driver (`sqlite-bundled-jvm`, test only), like iOS. Android's app database
 *   has no driver: it runs the same auto-migrations through the framework SQLite, and Room calls
 *   `Migration8To9.migrate(SupportSQLiteDatabase)` there; that overload runs the same statements (MigrationsTest).
 *   The framework SQLite cannot run on the host.
 *
 * Every version from 1 to 14 is covered by the generic round trip; 1, 8, 9, 12 and 14 also get rows with meaningful values.
 */
class DatabaseMigrationTest {

    private val dir: File = Files.createTempDirectory("chapterplayer-migration").toFile()
    private val file = File(dir, "migration.db")
    private var opened: ChapterPlayerDatabase? = null

    @AfterTest
    fun cleanUp() {
        opened?.close()
        dir.deleteRecursively()
    }

    // ---- Schema files ----

    // Host tests run with the module directory as the working directory (as RoomSchemaTest reads them).
    private fun schema(version: Int): JsonObject {
        val json = File("schemas/com.chapterplayer.data.local.ChapterPlayerDatabase/$version.json")
        assertTrue(json.exists(), "Exported Room schema is missing: ${json.path}")
        return Json.parseToJsonElement(json.readText()).jsonObject.getValue("database").jsonObject
    }

    private fun entities(schema: JsonObject): List<JsonObject> = schema.getValue("entities").jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private data class Column(val name: String, val affinity: String, val notNull: Boolean, val defaultValue: String?)

    private fun columns(entity: JsonObject): List<Column> = entity.getValue("fields").jsonArray.map { it.jsonObject }.map {
        Column(
            name = it.text("columnName"),
            affinity = it.text("affinity"),
            notNull = it["notNull"]?.jsonPrimitive?.boolean ?: false,
            defaultValue = it["defaultValue"]?.jsonPrimitive?.content,
        )
    }

    // ---- Creating the old database and opening it as the app does ----

    /** The database file exactly as an install at [version] holds it, then [rows] written into it. */
    private fun createAt(version: Int, rows: SQLiteConnection.() -> Unit = {}) {
        val schema = schema(version)
        assertEquals(version, schema.getValue("version").jsonPrimitive.int)
        val connection = BundledSQLiteDriver().open(file.path)
        try {
            for (entity in entities(schema)) {
                val table = entity.text("tableName")
                connection.execSQL(entity.text("createSql").replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(index.jsonObject.text("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            schema["views"]?.jsonArray?.forEach { view ->
                val name = view.jsonObject.text("viewName")
                connection.execSQL("CREATE VIEW `$name` AS ${view.jsonObject.text("createSql").replace("\${VIEW_NAME}", name)}")
            }
            schema.getValue("setupQueries").jsonArray.forEach { connection.execSQL(it.jsonPrimitive.content) }
            connection.execSQL("PRAGMA user_version = $version")
            connection.rows()
        } finally {
            connection.close()
        }
    }

    /** Only getDatabasePath is used by Room with a driver; it gets the absolute path of the test file back. */
    private val context = object : ContextWrapper(null) {
        override fun getDatabasePath(name: String): File = File(name)
    }

    /** Opens the file at the current version through Room's migration path (Android's migration list). */
    private fun openCurrent(plexCleanUp: Migration = Migration8To9): ChapterPlayerDatabase {
        val database = Room.databaseBuilder<ChapterPlayerDatabase>(context, file.absolutePath) {
            ChapterPlayerDatabaseConstructor.initialize()
        }
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .addMigrations(plexCleanUp)
            .build()
        opened = database
        return database
    }

    /** Every row of [sql] as column name to its value read as text (null stays null). */
    private fun ChapterPlayerDatabase.rows(sql: String): List<Map<String, String?>> = runBlocking {
        useReaderConnection { transactor ->
            transactor.usePrepared(sql) { statement ->
                val result = mutableListOf<Map<String, String?>>()
                while (statement.step()) {
                    result += (0 until statement.getColumnCount()).associate { index ->
                        statement.getColumnName(index) to if (statement.isNull(index)) null else statement.getText(index)
                    }
                }
                result
            }
        }
    }

    private fun ChapterPlayerDatabase.ids(sql: String): List<String> = rows(sql).map { it.values.single()!! }

    private fun ChapterPlayerDatabase.userVersion(): Int = rows("PRAGMA user_version").single().values.single()!!.toInt()

    /** INSERT with bound values (String, Int, Long, Boolean as 0/1, or null). */
    private fun SQLiteConnection.insert(table: String, vararg values: Pair<String, Any?>) {
        val names = values.joinToString(", ") { "`${it.first}`" }
        val marks = values.joinToString(", ") { "?" }
        prepare("INSERT INTO `$table` ($names) VALUES ($marks)").use { statement ->
            values.forEachIndexed { i, (_, value) ->
                val index = i + 1
                when (value) {
                    null -> statement.bindNull(index)
                    is String -> statement.bindText(index, value)
                    is Int -> statement.bindLong(index, value.toLong())
                    is Long -> statement.bindLong(index, value)
                    is Boolean -> statement.bindLong(index, if (value) 1 else 0)
                    else -> error("Unsupported value type: $value")
                }
            }
            statement.step()
        }
    }

    // ---- Version 1 to 15 ----

    private fun SQLiteConnection.bookV1(id: String, source: String, title: String) = insert(
        "books",
        "id" to id, "title" to title, "author" to "Author $title", "narrator" to "Narrator $title",
        "durationMs" to 3_600_000L, "chaptersJson" to "[{\"title\":\"Chapter 1\",\"startMs\":0}]",
        "streamUrl" to "https://abs.example.test/stream/$id", "coverUrl" to null, "source" to source,
        "serverId" to "server-$source", "cachedAt" to 1_700_000_000_000L,
    )

    @Test
    fun `version 1 opens at the current version with its books, progress and servers`() {
        createAt(1) {
            bookV1("abs_one", "ABS", "One")
            insert(
                "progress",
                "bookId" to "abs_one", "positionMs" to 125_000L, "durationMs" to 3_600_000L,
                "lastPlayedAt" to 1_700_000_100_000L, "isFinished" to false,
            )
            insert(
                "server_configs",
                "id" to "server-ABS", "source" to "ABS", "name" to "Home", "baseUrl" to "https://abs.example.test",
                "token" to "", "libraryId" to "lib-1",
            )
        }

        val db = openCurrent()

        assertEquals(15, db.userVersion())
        assertEquals(
            listOf(
                mapOf(
                    "id" to "abs_one", "title" to "One", "author" to "Author One", "narrator" to "Narrator One",
                    "durationMs" to "3600000", "chaptersJson" to "[{\"title\":\"Chapter 1\",\"startMs\":0}]",
                    "streamUrl" to "https://abs.example.test/stream/abs_one", "coverUrl" to null, "source" to "ABS",
                    "serverId" to "server-ABS", "cachedAt" to "1700000000000",
                    // Columns added later take their declared defaults.
                    "libraryId" to "", "addedAt" to "0", "seriesName" to null, "seriesSequence" to null,
                ),
            ),
            db.rows("SELECT * FROM books"),
        )
        assertEquals(
            listOf(
                mapOf(
                    "bookId" to "abs_one", "positionMs" to "125000", "durationMs" to "3600000",
                    "lastPlayedAt" to "1700000100000", "isFinished" to "0", "mirrored" to "0",
                ),
            ),
            db.rows("SELECT * FROM progress"),
        )
        assertEquals(
            listOf(
                mapOf(
                    "id" to "server-ABS", "source" to "ABS", "name" to "Home", "baseUrl" to "https://abs.example.test",
                    "token" to "", "libraryId" to "lib-1",
                ),
            ),
            db.rows("SELECT * FROM server_configs"),
        )
        // Tables added after version 1 exist and are empty.
        for (table in listOf("favourites", "up_next", "downloads", "bookmarks", "pending_progress", "book_merges")) {
            assertEquals(emptyList(), db.rows("SELECT * FROM $table"), table)
        }
    }

    @Test
    fun `version 1 goes through the version 9 clean-up on its way, so its Plex artist rows are dropped`() {
        createAt(1) {
            bookV1("abs_one", "ABS", "One")
            bookV1("plex_100", "PLEX", "An artist")
            insert(
                "progress",
                "bookId" to "plex_100", "positionMs" to 5_000L, "durationMs" to 0L, "lastPlayedAt" to 1L, "isFinished" to false,
            )
            insert(
                "server_configs",
                "id" to "server-PLEX", "source" to "PLEX", "name" to "Plex", "baseUrl" to "https://plex.example.test",
                "token" to "", "libraryId" to null,
            )
        }

        val db = openCurrent()

        assertEquals(listOf("abs_one"), db.ids("SELECT id FROM books"))
        assertEquals(emptyList(), db.rows("SELECT * FROM progress"))
        // The server itself stays: the app syncs it again as albums.
        assertEquals(listOf("server-PLEX"), db.ids("SELECT id FROM server_configs"))
    }

    // ---- Version 8 to 15: the hand-written Plex clean-up ----

    private fun SQLiteConnection.bookV8(id: String, source: String) = insert(
        "books",
        "id" to id, "title" to "Title $id", "author" to "Author", "narrator" to "", "durationMs" to 1_000_000L,
        "chaptersJson" to "[]", "streamUrl" to "", "coverUrl" to "https://example.test/cover/$id", "source" to source,
        "serverId" to "server-$source", "cachedAt" to 10L, "libraryId" to "lib-$source", "addedAt" to 20L,
        "seriesName" to "Series", "seriesSequence" to "2",
    )

    private fun SQLiteConnection.bookRowsV8(bookId: String, serverId: String) {
        insert(
            "progress",
            "bookId" to bookId, "positionMs" to 60_000L, "durationMs" to 1_000_000L, "lastPlayedAt" to 30L, "isFinished" to false,
        )
        insert(
            "pending_progress",
            "bookId" to bookId, "serverId" to serverId, "positionMs" to 61_000L, "durationMs" to 1_000_000L,
            "listenedMs" to 1_000L, "updatedAtMs" to 31L, "attempts" to 2, "nextAttemptAtMs" to 40L, "markedFinished" to null,
        )
        insert("position_history", "bookId" to bookId, "positionMs" to 59_000L, "recordedAt" to 29L)
        insert(
            "bookmarks",
            "bookId" to bookId, "timeSec" to 42, "serverId" to serverId, "title" to "Mark", "createdAt" to 28L, "state" to "SYNCED",
        )
        insert(
            "downloads",
            "bookId" to bookId, "serverId" to serverId, "status" to "DONE", "totalBytes" to 2_048L, "error" to null, "updatedAt" to 27L,
        )
        insert(
            "download_files",
            "bookId" to bookId, "ino" to "ino-1", "fileName" to "part1.mp3", "path" to "/downloads/$serverId/$bookId/ino-1.mp3",
            "totalBytes" to 2_048L, "doneBytes" to 2_048L, "sortOrder" to 0, "durationMs" to 1_000_000L, "mimeType" to "audio/mpeg",
        )
    }

    private val cleanedTables = listOf("progress", "pending_progress", "position_history", "bookmarks")

    private fun createV8WithEveryKind() = createAt(8) {
        // ABS, Plex and (written by hand: version 8 had no Jellyfin yet) a Jellyfin-shaped row, plus ids that a looser
        // pattern would catch: "plexa_4" (an unescaped underscore is a wildcard) and "abs_plex_5" ("plex_" not at the
        // start). SQLite's LIKE ignores ASCII case, so an id starting "PLEX_" would go too; the app makes every id with
        // a lower-case prefix (abs_, plex_, jf_), so none does.
        for ((id, source) in listOf(
            "abs_1" to "ABS", "plex_2" to "PLEX", "jf_3" to "JELLYFIN", "plexa_4" to "ABS", "abs_plex_5" to "ABS",
        )) {
            bookV8(id, source)
            bookRowsV8(id, "server-$source")
        }
        for (source in listOf("ABS", "PLEX")) {
            insert(
                "server_configs",
                "id" to "server-$source", "source" to source, "name" to source, "baseUrl" to "https://$source.example.test",
                "token" to "", "libraryId" to null,
            )
            insert("libraries", "serverId" to "server-$source", "id" to "lib-$source", "name" to "Books")
            insert("excluded_libraries", "serverId" to "server-$source", "libraryId" to "lib-off")
            insert("collections", "serverId" to "server-$source", "id" to "col-1", "name" to "Favourites")
        }
        insert("collection_books", "serverId" to "server-PLEX", "collectionId" to "col-1", "bookId" to "plex_2", "position" to 0)
    }

    private fun assertOnlyPlexRowsDropped(db: ChapterPlayerDatabase) {
        assertEquals(15, db.userVersion())
        val kept = listOf("abs_1", "abs_plex_5", "jf_3", "plexa_4")
        assertEquals(kept, db.ids("SELECT id FROM books ORDER BY id"))
        for (table in cleanedTables) {
            assertEquals(kept, db.ids("SELECT bookId FROM $table ORDER BY bookId"), table)
        }
        // The clean-up touches no other table: downloads, servers, libraries and collections of Plex stay.
        val all = listOf("abs_1", "abs_plex_5", "jf_3", "plex_2", "plexa_4")
        assertEquals(all, db.ids("SELECT bookId FROM downloads ORDER BY bookId"))
        assertEquals(all, db.ids("SELECT bookId FROM download_files ORDER BY bookId"))
        assertEquals(listOf("server-ABS", "server-PLEX"), db.ids("SELECT id FROM server_configs ORDER BY id"))
        assertEquals(listOf("server-ABS", "server-PLEX"), db.ids("SELECT serverId FROM libraries ORDER BY serverId"))
        assertEquals(listOf("server-ABS", "server-PLEX"), db.ids("SELECT serverId FROM excluded_libraries ORDER BY serverId"))
        assertEquals(listOf("server-ABS", "server-PLEX"), db.ids("SELECT serverId FROM collections ORDER BY serverId"))
        assertEquals(listOf("plex_2"), db.ids("SELECT bookId FROM collection_books"))

        // The rows that stay keep their values, and the columns added after version 8 take their defaults.
        assertEquals(
            mapOf(
                "id" to "jf_3", "title" to "Title jf_3", "author" to "Author", "narrator" to "", "durationMs" to "1000000",
                "chaptersJson" to "[]", "streamUrl" to "", "coverUrl" to "https://example.test/cover/jf_3", "source" to "JELLYFIN",
                "serverId" to "server-JELLYFIN", "cachedAt" to "10", "libraryId" to "lib-JELLYFIN", "addedAt" to "20",
                "seriesName" to "Series", "seriesSequence" to "2",
            ),
            db.rows("SELECT * FROM books WHERE id = 'jf_3'").single(),
        )
        assertEquals(
            mapOf(
                "bookId" to "abs_1", "positionMs" to "60000", "durationMs" to "1000000", "lastPlayedAt" to "30",
                "isFinished" to "0", "mirrored" to "0",
            ),
            db.rows("SELECT * FROM progress WHERE bookId = 'abs_1'").single(),
        )
        assertEquals(
            mapOf(
                "bookId" to "abs_1", "serverId" to "server-ABS", "positionMs" to "61000", "durationMs" to "1000000",
                "listenedMs" to "1000", "updatedAtMs" to "31", "attempts" to "2", "nextAttemptAtMs" to "40",
                "markedFinished" to null, "mirrorOf" to null,
            ),
            db.rows("SELECT * FROM pending_progress WHERE bookId = 'abs_1'").single(),
        )
        assertEquals(
            mapOf(
                "bookId" to "abs_1", "timeSec" to "42", "serverId" to "server-ABS", "title" to "Mark", "createdAt" to "28",
                "state" to "SYNCED",
            ),
            db.rows("SELECT * FROM bookmarks WHERE bookId = 'abs_1'").single(),
        )
        assertEquals(
            mapOf(
                "bookId" to "abs_1", "ino" to "ino-1", "fileName" to "part1.mp3", "path" to "/downloads/server-ABS/abs_1/ino-1.mp3",
                "totalBytes" to "2048", "doneBytes" to "2048", "sortOrder" to "0", "durationMs" to "1000000", "mimeType" to "audio/mpeg",
            ),
            db.rows("SELECT * FROM download_files WHERE bookId = 'abs_1'").single(),
        )
    }

    @Test
    fun `version 8 drops only the Plex rows on Android's migration list`() {
        createV8WithEveryKind()

        assertOnlyPlexRowsDropped(openCurrent(Migration8To9))
    }

    @Test
    fun `version 8 drops only the Plex rows on iOS's migration list`() {
        createV8WithEveryKind()

        assertOnlyPlexRowsDropped(openCurrent(DropPlexRowsMigration()))
    }

    // ---- Version 9 to 15: Plex album rows are kept ----

    @Test
    fun `version 9 keeps its Plex rows, since the clean-up runs only from version 8`() {
        createAt(9) {
            for ((id, source) in listOf("abs_1" to "ABS", "plex_2" to "PLEX")) {
                bookV8(id, source)
                bookRowsV8(id, "server-$source")
            }
        }

        val db = openCurrent()

        assertEquals(15, db.userVersion())
        assertEquals(listOf("abs_1", "plex_2"), db.ids("SELECT id FROM books ORDER BY id"))
        for (table in cleanedTables + listOf("downloads", "download_files")) {
            assertEquals(listOf("abs_1", "plex_2"), db.ids("SELECT bookId FROM $table ORDER BY bookId"), table)
        }
        assertEquals(
            mapOf(
                "bookId" to "plex_2", "positionMs" to "60000", "durationMs" to "1000000", "lastPlayedAt" to "30",
                "isFinished" to "0", "mirrored" to "0",
            ),
            db.rows("SELECT * FROM progress WHERE bookId = 'plex_2'").single(),
        )
    }

    // ---- Version 12 to 15: mirrored progress, merges, statistics ----

    @Test
    fun `version 12 keeps mirrored progress, merges and listening statistics`() {
        createAt(12) {
            bookV8("abs_1", "ABS")
            bookV8("plex_2", "PLEX")
            insert(
                "progress",
                "bookId" to "plex_2", "positionMs" to 70_000L, "durationMs" to 1_000_000L, "lastPlayedAt" to 50L,
                "isFinished" to true, "mirrored" to true,
            )
            insert(
                "pending_progress",
                "bookId" to "plex_2", "serverId" to "server-PLEX", "positionMs" to 70_000L, "durationMs" to 1_000_000L,
                "listenedMs" to 0L, "updatedAtMs" to 51L, "attempts" to 0, "nextAttemptAtMs" to 0L, "markedFinished" to true,
            )
            insert("book_merges", "bookIdA" to "abs_1", "bookIdB" to "plex_2", "kind" to "SPLIT")
            insert(
                "listening_log",
                "day" to 19_700, "bookId" to "abs_1", "serverId" to "server-ABS", "source" to "ABS",
                "wallSeconds" to 600, "bookSeconds" to 720,
            )
            insert("imported_listening_days", "serverId" to "server-ABS", "day" to 19_699, "seconds" to 300)
            insert("imported_listening_items", "serverId" to "server-ABS", "bookId" to "abs_1", "seconds" to 300, "loggedAtImport" to 120)
            insert("listening_imports", "serverId" to "server-ABS", "importedAt" to 60L)
        }

        val db = openCurrent()

        assertEquals(15, db.userVersion())
        assertEquals(
            mapOf(
                "bookId" to "plex_2", "positionMs" to "70000", "durationMs" to "1000000", "lastPlayedAt" to "50",
                "isFinished" to "1", "mirrored" to "1",
            ),
            db.rows("SELECT * FROM progress").single(),
        )
        assertEquals(
            mapOf(
                "bookId" to "plex_2", "serverId" to "server-PLEX", "positionMs" to "70000", "durationMs" to "1000000",
                "listenedMs" to "0", "updatedAtMs" to "51", "attempts" to "0", "nextAttemptAtMs" to "0",
                "markedFinished" to "1", "mirrorOf" to null,
            ),
            db.rows("SELECT * FROM pending_progress").single(),
        )
        assertEquals(listOf(mapOf("bookIdA" to "abs_1", "bookIdB" to "plex_2", "kind" to "SPLIT")), db.rows("SELECT * FROM book_merges"))
        assertEquals(
            listOf(
                mapOf(
                    "day" to "19700", "bookId" to "abs_1", "serverId" to "server-ABS", "source" to "ABS",
                    "wallSeconds" to "600", "bookSeconds" to "720",
                ),
            ),
            db.rows("SELECT * FROM listening_log"),
        )
        assertEquals(listOf(mapOf("serverId" to "server-ABS", "day" to "19699", "seconds" to "300")), db.rows("SELECT * FROM imported_listening_days"))
        assertEquals(
            listOf(mapOf("serverId" to "server-ABS", "bookId" to "abs_1", "seconds" to "300", "loggedAtImport" to "120")),
            db.rows("SELECT * FROM imported_listening_items"),
        )
        assertEquals(listOf(mapOf("serverId" to "server-ABS", "importedAt" to "60")), db.rows("SELECT * FROM listening_imports"))
        assertEquals(emptyList(), db.rows("SELECT * FROM favourites"))
        assertEquals(emptyList(), db.rows("SELECT * FROM up_next"))
    }

    // ---- Version 14 to 15: favourites, then the new Up next queue ----

    @Test
    fun `version 14 keeps favourites and a mirrored queued position, and gets an empty Up next queue`() {
        createAt(14) {
            bookV8("abs_1", "ABS")
            bookV8("jf_3", "JELLYFIN")
            insert("favourites", "bookId" to "abs_1", "addedAt" to 80L)
            insert("favourites", "bookId" to "jf_3", "addedAt" to 81L)
            insert(
                "pending_progress",
                "bookId" to "jf_3", "serverId" to "server-JELLYFIN", "positionMs" to 1_000L, "durationMs" to 1_000_000L,
                "listenedMs" to 0L, "updatedAtMs" to 82L, "attempts" to 1, "nextAttemptAtMs" to 90L, "markedFinished" to null,
                "mirrorOf" to "abs_1",
            )
        }

        val db = openCurrent()

        assertEquals(15, db.userVersion())
        assertEquals(
            listOf(mapOf("bookId" to "abs_1", "addedAt" to "80"), mapOf("bookId" to "jf_3", "addedAt" to "81")),
            db.rows("SELECT * FROM favourites ORDER BY bookId"),
        )
        assertEquals("abs_1", db.rows("SELECT mirrorOf FROM pending_progress WHERE bookId = 'jf_3'").single()["mirrorOf"])
        assertEquals(listOf("abs_1", "jf_3"), db.ids("SELECT id FROM books ORDER BY id"))
        assertEquals(emptyList(), db.rows("SELECT * FROM up_next"))
        // The app's own DAOs read the migrated tables.
        assertEquals(emptyList(), runBlocking { db.upNextDao().getAll() })
        assertEquals(listOf("abs_1", "jf_3"), runBlocking { db.favouriteDao().existing(listOf("abs_1", "jf_3", "plex_2")) }.sorted())
    }

    // ---- Every version: one row in every table survives with every value ----

    private fun syntheticValue(table: String, column: Column, slot: Int): Any = when {
        column.affinity == "INTEGER" -> 1_000L + slot
        column.affinity == "REAL" -> error("No REAL columns expected; extend the test for $table.${column.name}")
        // A source that is not PLEX, so the version 9 clean-up leaves the row alone.
        column.name == "source" -> "ABS"
        else -> "$table.${column.name}"
    }

    /** The value read back for a column added after [from], from its declared default. */
    private fun defaultAsRead(column: Column): String? = when (val value = column.defaultValue) {
        null, "NULL" -> null
        else -> value.removeSurrounding("'")
    }

    @Test
    fun `every exported version opens at the current version and keeps a row in every table`() {
        val current = schema(15)
        val currentTables = entities(current).associate { it.text("tableName") to columns(it) }
        for (version in 1..14) {
            file.delete()
            val inserted = mutableMapOf<String, Map<String, String>>()
            createAt(version) {
                for (entity in entities(schema(version))) {
                    val table = entity.text("tableName")
                    val values = columns(entity).mapIndexed { slot, column -> column.name to syntheticValue(table, column, slot) }
                    insert(table, *values.toTypedArray())
                    inserted[table] = values.associate { (name, value) -> name to value.toString() }
                }
            }

            val db = openCurrent()
            try {
                assertEquals(15, db.userVersion(), "from version $version")
                for ((table, columnsNow) in currentTables) {
                    val old = inserted[table]
                    val read = db.rows("SELECT * FROM $table")
                    if (old == null) {
                        assertEquals(emptyList(), read, "from version $version: new table $table")
                        continue
                    }
                    val expected = columnsNow.associate { column ->
                        column.name to if (column.name in old) old.getValue(column.name) else defaultAsRead(column)
                    }
                    assertEquals(listOf(expected), read, "from version $version: table $table")
                }
                assertEquals(inserted.keys, inserted.keys intersect currentTables.keys, "from version $version: a table was dropped")
            } finally {
                db.close()
                opened = null
            }
        }
    }
}

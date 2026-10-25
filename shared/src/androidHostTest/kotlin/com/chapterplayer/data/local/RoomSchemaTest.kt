package com.chapterplayer.data.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Installed apps already hold a database in this exact shape, and Room refuses to open one
 * whose schema hash differs. The exported schema file is the record of what shipped, so this
 * test pins it. If it fails after a change to an entity or the database, the change needs a
 * migration, not an edited expectation.
 */
class RoomSchemaTest {

    // Host tests run with the module directory as the working directory.
    private fun load(version: Int): JsonObject {
        val file = File("schemas/com.chapterplayer.data.local.ChapterPlayerDatabase/$version.json")
        assertTrue(file.exists(), "Exported Room schema is missing: ${file.path}")
        return Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject
    }

    // 1.json is the record of what v0.1.0 shipped; it must never change.
    private val database: JsonObject by lazy { load(1) }
    private val database2: JsonObject by lazy { load(2) }
    private val database3: JsonObject by lazy { load(3) }
    private val database4: JsonObject by lazy { load(4) }
    private val database5: JsonObject by lazy { load(5) }
    private val database6: JsonObject by lazy { load(6) }

    private fun entity(table: String, db: JsonObject = database): JsonObject =
        db.getValue("entities").jsonArray.map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == table }

    /** column name to "AFFINITY" or "AFFINITY?" for a nullable column. */
    private fun columns(table: String, db: JsonObject = database): Map<String, String> =
        entity(table, db).getValue("fields").jsonArray.map { it.jsonObject }.associate { field ->
            // Room leaves notNull out of the file for a nullable column.
            val notNull = field["notNull"]?.jsonPrimitive?.boolean ?: false
            val nullable = if (notNull) "" else "?"
            field.getValue("columnName").jsonPrimitive.content to field.getValue("affinity").jsonPrimitive.content + nullable
        }

    private fun primaryKey(table: String, db: JsonObject = database): List<String> =
        entity(table, db).getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `the shipped database is version 1 with the three shipped tables`() {
        assertEquals(1, database.getValue("version").jsonPrimitive.int)
        assertEquals(
            setOf("books", "progress", "server_configs"),
            database.getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }.toSet()
        )
    }

    @Test
    fun `the schema hash is the one stored in installed databases`() {
        // Read from room_master_table in a database created by the app before the move to Room KMP.
        assertEquals("49ea15f9656abe203e794ffb26dc5da5", database.getValue("identityHash").jsonPrimitive.content)
    }

    @Test
    fun `books keeps its columns`() {
        assertEquals(
            mapOf(
                "id" to "TEXT", "title" to "TEXT", "author" to "TEXT", "narrator" to "TEXT",
                "durationMs" to "INTEGER", "chaptersJson" to "TEXT", "streamUrl" to "TEXT",
                "coverUrl" to "TEXT?", "source" to "TEXT", "serverId" to "TEXT", "cachedAt" to "INTEGER",
            ),
            columns("books")
        )
        assertEquals(listOf("id"), primaryKey("books"))
    }

    @Test
    fun `progress keeps its columns`() {
        assertEquals(
            mapOf(
                "bookId" to "TEXT", "positionMs" to "INTEGER", "durationMs" to "INTEGER",
                "lastPlayedAt" to "INTEGER", "isFinished" to "INTEGER",
            ),
            columns("progress")
        )
        assertEquals(listOf("bookId"), primaryKey("progress"))
    }

    @Test
    fun `server configs keeps its columns`() {
        assertEquals(
            mapOf(
                "id" to "TEXT", "source" to "TEXT", "name" to "TEXT", "baseUrl" to "TEXT",
                "token" to "TEXT", "libraryId" to "TEXT?",
            ),
            columns("server_configs")
        )
        assertEquals(listOf("id"), primaryKey("server_configs"))
    }

    @Test
    fun `there are no indices or foreign keys to migrate`() {
        for (table in listOf("books", "progress", "server_configs")) {
            // Room leaves an empty list out of the file.
            assertEquals(JsonArray(emptyList()), entity(table)["indices"] ?: JsonArray(emptyList()), "$table indices")
            assertEquals(JsonArray(emptyList()), entity(table)["foreignKeys"] ?: JsonArray(emptyList()), "$table foreign keys")
        }
    }

    private fun tables(db: JsonObject) =
        db.getValue("entities").jsonArray.map { it.jsonObject.getValue("tableName").jsonPrimitive.content }.toSet()

    @Test
    fun `version 2 adds the excluded libraries table`() {
        assertEquals(2, database2.getValue("version").jsonPrimitive.int)
        assertEquals(setOf("books", "progress", "server_configs", "excluded_libraries"), tables(database2))
    }

    @Test
    fun `version 2 keeps the token column because an upgrade from v0_1_0 may still hold a token in it`() {
        assertEquals("TEXT", columns("server_configs", database2)["token"])
    }

    @Test
    fun `excluded libraries has two required text columns and a composite key`() {
        assertEquals(mapOf("serverId" to "TEXT", "libraryId" to "TEXT"), columns("excluded_libraries", database2))
        assertEquals(listOf("serverId", "libraryId"), primaryKey("excluded_libraries", database2))
    }

    @Test
    fun `version 2 leaves the other tables as they were`() {
        for (table in listOf("books", "progress", "server_configs")) {
            assertEquals(columns(table), columns(table, database2), "$table columns")
            assertEquals(primaryKey(table), primaryKey(table, database2), "$table primary key")
        }
    }

    @Test
    fun `version 3 adds the library and collection tables`() {
        assertEquals(3, database3.getValue("version").jsonPrimitive.int)
        assertEquals(
            setOf("books", "progress", "server_configs", "excluded_libraries", "libraries", "collections", "collection_books"),
            tables(database3)
        )
        assertEquals(mapOf("serverId" to "TEXT", "id" to "TEXT", "name" to "TEXT"), columns("libraries", database3))
        assertEquals(listOf("serverId", "id"), primaryKey("libraries", database3))
        assertEquals(mapOf("serverId" to "TEXT", "id" to "TEXT", "name" to "TEXT"), columns("collections", database3))
        assertEquals(listOf("serverId", "id"), primaryKey("collections", database3))
        assertEquals(
            mapOf("serverId" to "TEXT", "collectionId" to "TEXT", "bookId" to "TEXT", "position" to "INTEGER"),
            columns("collection_books", database3)
        )
        assertEquals(listOf("serverId", "collectionId", "bookId"), primaryKey("collection_books", database3))
    }

    @Test
    fun `version 3 adds four book columns with defaults so the migration can fill old rows`() {
        assertEquals(
            columns("books", database2) + mapOf(
                "libraryId" to "TEXT", "addedAt" to "INTEGER", "seriesName" to "TEXT?", "seriesSequence" to "TEXT?",
            ),
            columns("books", database3)
        )
        val defaults = entity("books", database3).getValue("fields").jsonArray.map { it.jsonObject }
            .associate { it.getValue("columnName").jsonPrimitive.content to it["defaultValue"]?.jsonPrimitive?.content }
        assertEquals("''", defaults["libraryId"])
        assertEquals("0", defaults["addedAt"])
    }

    @Test
    fun `version 3 leaves the older tables as they were`() {
        for (table in listOf("progress", "server_configs", "excluded_libraries")) {
            assertEquals(columns(table, database2), columns(table, database3), "$table columns")
        }
        assertEquals(listOf("id"), primaryKey("books", database3))
        assertEquals(listOf("bookId"), primaryKey("progress", database3))
        assertEquals(listOf("id"), primaryKey("server_configs", database3))
        assertEquals(listOf("serverId", "libraryId"), primaryKey("excluded_libraries", database3))
    }

    @Test
    fun `version 4 adds the pending progress queue`() {
        assertEquals(4, database4.getValue("version").jsonPrimitive.int)
        assertEquals(
            setOf(
                "books", "progress", "server_configs", "excluded_libraries", "libraries", "collections",
                "collection_books", "pending_progress"
            ),
            tables(database4)
        )
        assertEquals(
            mapOf(
                "bookId" to "TEXT", "serverId" to "TEXT", "positionMs" to "INTEGER", "durationMs" to "INTEGER",
                "listenedMs" to "INTEGER", "updatedAtMs" to "INTEGER", "attempts" to "INTEGER", "nextAttemptAtMs" to "INTEGER",
            ),
            columns("pending_progress", database4)
        )
        assertEquals(listOf("bookId"), primaryKey("pending_progress", database4))
        val defaults = entity("pending_progress", database4).getValue("fields").jsonArray.map { it.jsonObject }
            .associate { it.getValue("columnName").jsonPrimitive.content to it["defaultValue"]?.jsonPrimitive?.content }
        assertEquals("0", defaults["nextAttemptAtMs"])
    }

    @Test
    fun `version 4 leaves the older tables as they were`() {
        for (table in listOf("books", "progress", "server_configs", "excluded_libraries", "libraries", "collections", "collection_books")) {
            assertEquals(columns(table, database3), columns(table, database4), "$table columns")
            assertEquals(primaryKey(table, database3), primaryKey(table, database4), "$table primary key")
        }
    }

    @Test
    fun `version 5 adds bookmarks and position history`() {
        assertEquals(5, database5.getValue("version").jsonPrimitive.int)
        assertEquals(tables(database4) + setOf("bookmarks", "position_history"), tables(database5))
        assertEquals(
            mapOf(
                "bookId" to "TEXT", "timeSec" to "INTEGER", "serverId" to "TEXT", "title" to "TEXT",
                "createdAt" to "INTEGER", "state" to "TEXT",
            ),
            columns("bookmarks", database5)
        )
        assertEquals(listOf("bookId", "timeSec"), primaryKey("bookmarks", database5))
        assertEquals(
            mapOf("id" to "INTEGER", "bookId" to "TEXT", "positionMs" to "INTEGER", "recordedAt" to "INTEGER"),
            columns("position_history", database5)
        )
        assertEquals(listOf("id"), primaryKey("position_history", database5))
        val autoGenerate = entity("position_history", database5).getValue("primaryKey").jsonObject.getValue("autoGenerate").jsonPrimitive.boolean
        assertTrue(autoGenerate)
    }

    @Test
    fun `version 5 leaves the older tables as they were`() {
        for (table in tables(database4)) {
            assertEquals(columns(table, database4), columns(table, database5), "$table columns")
            assertEquals(primaryKey(table, database4), primaryKey(table, database5), "$table primary key")
        }
    }

    @Test
    fun `version 6 adds downloads and download files`() {
        assertEquals(6, database6.getValue("version").jsonPrimitive.int)
        assertEquals(tables(database5) + setOf("downloads", "download_files"), tables(database6))
        assertEquals(
            mapOf(
                "bookId" to "TEXT", "serverId" to "TEXT", "status" to "TEXT", "totalBytes" to "INTEGER",
                "error" to "TEXT?", "updatedAt" to "INTEGER",
            ),
            columns("downloads", database6)
        )
        assertEquals(listOf("bookId"), primaryKey("downloads", database6))
        assertEquals(
            mapOf(
                "bookId" to "TEXT", "ino" to "TEXT", "fileName" to "TEXT", "path" to "TEXT",
                "totalBytes" to "INTEGER", "doneBytes" to "INTEGER", "sortOrder" to "INTEGER",
            ),
            columns("download_files", database6)
        )
        assertEquals(listOf("bookId", "ino"), primaryKey("download_files", database6))
    }

    @Test
    fun `version 6 leaves the older tables as they were`() {
        for (table in tables(database5)) {
            assertEquals(columns(table, database5), columns(table, database6), "$table columns")
            assertEquals(primaryKey(table, database5), primaryKey(table, database6), "$table primary key")
        }
    }

    private val database7: JsonObject by lazy { load(7) }

    @Test
    fun `version 7 adds each downloaded file's length and type with defaults for old rows`() {
        assertEquals(7, database7.getValue("version").jsonPrimitive.int)
        assertEquals(tables(database6), tables(database7))
        assertEquals(
            columns("download_files", database6) + mapOf("durationMs" to "INTEGER", "mimeType" to "TEXT"),
            columns("download_files", database7)
        )
        val defaults = entity("download_files", database7).getValue("fields").jsonArray.map { it.jsonObject }
            .associate { it.getValue("columnName").jsonPrimitive.content to it["defaultValue"]?.jsonPrimitive?.content }
        assertEquals("0", defaults["durationMs"])
        assertEquals("''", defaults["mimeType"])
        assertEquals(listOf("bookId", "ino"), primaryKey("download_files", database7))
    }

    @Test
    fun `version 7 leaves the other tables as they were`() {
        for (table in tables(database6) - "download_files") {
            assertEquals(columns(table, database6), columns(table, database7), "$table columns")
            assertEquals(primaryKey(table, database6), primaryKey(table, database7), "$table primary key")
        }
    }

    private val database8: JsonObject by lazy { load(8) }
    private val database9: JsonObject by lazy { load(9) }

    @Test
    fun `version 9 changes no table (its migration only drops the old Plex rows)`() {
        assertEquals(9, database9.getValue("version").jsonPrimitive.int)
        assertEquals(tables(database8), tables(database9))
        for (table in tables(database8)) {
            assertEquals(columns(table, database8), columns(table, database9), "$table columns")
            assertEquals(primaryKey(table, database8), primaryKey(table, database9), "$table primary key")
        }
        assertEquals(database8.getValue("identityHash"), database9.getValue("identityHash"))
    }
}

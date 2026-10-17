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
}

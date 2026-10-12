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
    private val schemaFile = File("schemas/com.chapterplayer.data.local.ChapterPlayerDatabase/1.json")

    private val database: JsonObject by lazy {
        assertTrue(schemaFile.exists(), "Exported Room schema is missing: ${schemaFile.path}")
        Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
    }

    private fun entity(table: String): JsonObject =
        database.getValue("entities").jsonArray.map { it.jsonObject }
            .single { it.getValue("tableName").jsonPrimitive.content == table }

    /** column name to "AFFINITY" or "AFFINITY?" for a nullable column. */
    private fun columns(table: String): Map<String, String> =
        entity(table).getValue("fields").jsonArray.map { it.jsonObject }.associate { field ->
            // Room leaves notNull out of the file for a nullable column.
            val notNull = field["notNull"]?.jsonPrimitive?.boolean ?: false
            val nullable = if (notNull) "" else "?"
            field.getValue("columnName").jsonPrimitive.content to field.getValue("affinity").jsonPrimitive.content + nullable
        }

    private fun primaryKey(table: String): List<String> =
        entity(table).getValue("primaryKey").jsonObject.getValue("columnNames").jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `the database is version 1 with the three shipped tables`() {
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
}

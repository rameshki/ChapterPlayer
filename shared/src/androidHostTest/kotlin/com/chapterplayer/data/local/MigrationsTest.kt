package com.chapterplayer.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The version 9 migration runs the same statements whichever way Room calls it, and they touch only Plex rows. */
class MigrationsTest {

    private val executed = mutableListOf<String>()

    private inline fun <reified T> recorder(crossinline onCall: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> onCall(method.name, args) } as T

    private val statement: SQLiteStatement = recorder { name, _ -> if (name == "step") false else null }

    private val connection: SQLiteConnection = recorder { name, args ->
        if (name == "prepare") statement.also { executed += args!![0] as String } else null
    }

    private val database: SupportSQLiteDatabase = recorder { name, args ->
        if (name == "execSQL") executed += args!![0] as String
        null
    }

    private val expected = listOf(
        "DELETE FROM books WHERE source = 'PLEX'",
        "DELETE FROM progress WHERE bookId LIKE 'plex\\_%' ESCAPE '\\'",
        "DELETE FROM pending_progress WHERE bookId LIKE 'plex\\_%' ESCAPE '\\'",
        "DELETE FROM position_history WHERE bookId LIKE 'plex\\_%' ESCAPE '\\'",
        "DELETE FROM bookmarks WHERE bookId LIKE 'plex\\_%' ESCAPE '\\'",
    )

    @Test
    fun `the id pattern is escaped, so an underscore is not a wildcard`() {
        // In SQL the statements read: LIKE 'plex\_%' ESCAPE '\'
        val backslash = '\\'
        assertTrue(DROP_PLEX_ROWS_SQL.drop(1).all { it.endsWith("LIKE 'plex${backslash}_%' ESCAPE '$backslash'") })
        assertTrue(DROP_PLEX_ROWS_SQL.none { "${backslash}${backslash}" in it })
    }

    @Test
    fun `migrates from 8 to 9`() {
        assertEquals(8, Migration8To9.startVersion)
        assertEquals(9, Migration8To9.endVersion)
    }

    @Test
    fun `a driver connection runs the clean-up`() {
        Migration8To9.migrate(connection)

        assertEquals(expected, executed)
    }

    @Test
    fun `a support database runs the same clean-up`() {
        Migration8To9.migrate(database)

        assertEquals(expected, executed)
    }
}

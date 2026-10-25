package com.chapterplayer.data.local

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/** The version 9 migration runs one statement, whichever way Room calls it, and that statement touches only Plex books. */
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

    @Test
    fun `migrates from 8 to 9`() {
        assertEquals(8, Migration8To9.startVersion)
        assertEquals(9, Migration8To9.endVersion)
    }

    @Test
    fun `a driver connection runs the clean-up`() {
        Migration8To9.migrate(connection)

        assertEquals(listOf("DELETE FROM books WHERE source = 'PLEX'"), executed)
    }

    @Test
    fun `a support database runs the same clean-up`() {
        Migration8To9.migrate(database)

        assertEquals(listOf("DELETE FROM books WHERE source = 'PLEX'"), executed)
    }
}

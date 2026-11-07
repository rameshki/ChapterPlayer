package com.chapterplayer.ui.details

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CopyDescriptionsTest {

    @Test
    fun `descriptions come back in the order asked for`() = runTest {
        val cache = CopyDescriptions()
        val texts = mapOf("a" to "A text", "b" to null, "c" to "C text")
        assertEquals(listOf("A text", null, "C text"), cache.descriptionsOf(listOf("a", "b", "c")) { Result.success(texts[it]) })
    }

    @Test
    fun `an answer is kept for the session and not asked for again`() = runTest {
        val cache = CopyDescriptions()
        val asked = mutableListOf<String>()
        val load: suspend (String) -> Result<String?> = { asked += it; Result.success(if (it == "a") "A text" else null) }

        cache.descriptionsOf(listOf("a", "b"), load)
        assertEquals(listOf("A text", null), cache.descriptionsOf(listOf("a", "b"), load))

        assertEquals(listOf("a", "b"), asked)
    }

    @Test
    fun `a failed answer is not kept so the next open asks again`() = runTest {
        val cache = CopyDescriptions()
        var fail = true
        val load: suspend (String) -> Result<String?> = { if (fail) Result.failure(IllegalStateException("offline")) else Result.success("A text") }

        assertEquals(listOf<String?>(null), cache.descriptionsOf(listOf("a"), load))
        fail = false
        assertEquals(listOf<String?>("A text"), cache.descriptionsOf(listOf("a"), load))
    }

    @Test
    fun `a thrown error counts as no answer`() = runTest {
        val cache = CopyDescriptions()
        assertEquals(listOf<String?>(null, "B"), cache.descriptionsOf(listOf("a", "b")) { if (it == "a") error("boom") else Result.success("B") })
    }

    @Test
    fun `copies are asked for at the same time up to the cap`() = runTest {
        val cache = CopyDescriptions(maxParallel = 2)
        val gates = (1..4).associate { "c$it" to CompletableDeferred<Unit>() }
        var running = 0
        var most = 0
        val result = async {
            cache.descriptionsOf(gates.keys.toList()) { id ->
                running++; most = maxOf(most, running)
                gates.getValue(id).await()
                running--
                Result.success(id)
            }
        }
        runCurrent()
        assertEquals(2, running)
        gates.values.forEach { it.complete(Unit) }
        assertEquals(listOf("c1", "c2", "c3", "c4"), result.await())
        assertEquals(2, most)
    }
}

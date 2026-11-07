package com.chapterplayer.ui.details

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * The descriptions the other copies' servers gave for a book several servers have (by book id), kept for the app
 * session so opening the page again asks nothing. Only answers are kept: a failed or thrown read is asked again next
 * time. Missing copies are read at the same time, at most [maxParallel] at once.
 */
class CopyDescriptions(private val maxParallel: Int = 3) {
    private val lock = Mutex()
    private val known = HashMap<String, String?>()

    suspend fun descriptionsOf(ids: List<String>, load: suspend (String) -> Result<String?>): List<String?> {
        val cached = lock.withLock { ids.filter { it in known }.associateWith { known[it] } }
        val missing = ids.distinct().filter { it !in cached }
        val gate = Semaphore(maxParallel.coerceAtLeast(1))
        val loaded = coroutineScope {
            missing.map { id ->
                async {
                    val result = try {
                        gate.withPermit { load(id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    id to result
                }
            }.awaitAll()
        }
        val answered = loaded.mapNotNull { (id, result) -> result.getOrNull().let { text -> if (result.isSuccess) id to text else null } }
        if (answered.isNotEmpty()) lock.withLock { answered.forEach { (id, text) -> known[id] = text } }
        val fresh = answered.toMap()
        return ids.map { id -> if (id in cached) cached[id] else fresh[id] }
    }
}

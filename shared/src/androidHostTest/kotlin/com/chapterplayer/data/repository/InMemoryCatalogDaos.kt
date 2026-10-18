package com.chapterplayer.data.repository

import com.chapterplayer.data.local.dao.CollectionDao
import com.chapterplayer.data.local.dao.LibraryDao
import com.chapterplayer.data.local.entity.CollectionBookEntity
import com.chapterplayer.data.local.entity.CollectionEntity
import com.chapterplayer.data.local.entity.LibraryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Library and collection fakes for repository tests; REPLACE on the primary key like the real tables. */
class InMemoryLibraryDao(initial: List<LibraryEntity> = emptyList()) : LibraryDao {
    val rows = MutableStateFlow(initial)
    override fun observeAll(): Flow<List<LibraryEntity>> = rows.map { it.sortedWith(compareBy({ r -> r.serverId }, { r -> r.name })) }
    override suspend fun deleteForServer(serverId: String) { rows.value = rows.value.filter { it.serverId != serverId } }
    override suspend fun insertAll(rows: List<LibraryEntity>) {
        this.rows.value = this.rows.value.filter { old -> rows.none { it.serverId == old.serverId && it.id == old.id } } + rows
    }
}

class InMemoryCollectionDao(
    initial: List<CollectionEntity> = emptyList(),
    initialBooks: List<CollectionBookEntity> = emptyList(),
) : CollectionDao {
    val collections = MutableStateFlow(initial)
    val books = MutableStateFlow(initialBooks)
    override fun observeCollections(): Flow<List<CollectionEntity>> = collections.map { it.sortedBy { c -> c.name.lowercase() } }
    override fun observeCollectionBooks(): Flow<List<CollectionBookEntity>> = books
    override suspend fun deleteCollectionsForServer(serverId: String) { collections.value = collections.value.filter { it.serverId != serverId } }
    override suspend fun deleteCollectionBooksForServer(serverId: String) { books.value = books.value.filter { it.serverId != serverId } }
    override suspend fun insertCollections(rows: List<CollectionEntity>) {
        collections.value = collections.value.filter { old -> rows.none { it.serverId == old.serverId && it.id == old.id } } + rows
    }
    override suspend fun insertCollectionBooks(rows: List<CollectionBookEntity>) {
        books.value = books.value.filter { old ->
            rows.none { it.serverId == old.serverId && it.collectionId == old.collectionId && it.bookId == old.bookId }
        } + rows
    }
}

package com.chapterplayer.data.source

import com.chapterplayer.data.model.AudioBook
import com.chapterplayer.data.model.BookCollection
import com.chapterplayer.data.model.LibraryInfo
import com.chapterplayer.data.model.ServerConfig
import com.chapterplayer.data.model.ServerSource
import com.chapterplayer.data.remote.plex.PlexApiService
import com.chapterplayer.data.remote.plex.dto.PlexSectionDto
import com.chapterplayer.data.remote.plex.fetchAllPlexItems
import com.chapterplayer.data.remote.plex.dto.PlexMetadataDto
import com.chapterplayer.domain.plex.PlexEntryKind
import com.chapterplayer.domain.plex.plexAlbumToBook
import com.chapterplayer.domain.plex.plexEntryKind
import kotlinx.coroutines.CancellationException

/**
 * Lists the books on a Plex Media Server. In a Plex music library each audiobook is an album (the author is the
 * album's artist); its audio is the album's tracks and its chapters come from each track's own metadata.
 * The token only ever travels in the `X-Plex-Token` header, never in an address the app stores.
 */
class PlexLibrarySource(private val api: PlexApiService) : LibrarySource {

    override val type: ServerSource = ServerSource.PLEX

    override suspend fun fetchBooks(server: ServerConfig): List<AudioBook> =
        sectionIds(server, if (server.libraryId.isNullOrBlank()) musicSections(server) else null)
            .flatMap { sectionId -> booksIn(server, sectionId) }

    /**
     * One sync: the section list is read once and shared by the books and the libraries. A section that fails is
     * reported in [SourceSnapshot.failedLibraries] while the others are stored; only when every section fails does
     * the sync fail as a whole (with that error), so the cache stays as it was.
     */
    override suspend fun fetchSnapshot(server: ServerConfig): SourceSnapshot {
        // A configured section needs no list for its books; the list only names it, so its failure is not fatal then.
        val sections = if (server.libraryId.isNullOrBlank()) musicSections(server) else fetchOrNull { musicSections(server) }
        val ids = sectionIds(server, sections)
        val books = mutableListOf<AudioBook>()
        val failed = LinkedHashMap<String, String>()
        var firstError: Exception? = null
        for (sectionId in ids) {
            try {
                books += booksIn(server, sectionId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val name = sections?.firstOrNull { it.key == sectionId }?.title ?: sectionId
                failed[sectionId] = "$name: ${e.message ?: "Unknown error"}"
                if (firstError == null) firstError = e
            }
        }
        if (firstError != null && failed.size == ids.size) throw firstError
        return SourceSnapshot(
            books = books,
            libraries = sections?.let { librariesOf(server, it) },
            collections = emptyList(),
            failedLibraries = failed
        )
    }

    /**
     * A configured section is read unless the user turned it off; otherwise every music section in [listed]
     * (audiobooks live in music libraries in Plex) except the ones the user turned off.
     */
    private fun sectionIds(server: ServerConfig, listed: List<PlexSectionDto>?): List<String> =
        if (!server.libraryId.isNullOrBlank()) {
            listOf(server.libraryId).filter { it !in server.excludedLibraryIds }
        } else {
            requireNotNull(listed) { "The server's section list is missing" }.map { it.key }.filter { it !in server.excludedLibraryIds }
        }

    /** The section's albums as books; artists (a server that ignored the album filter) are opened for their albums. */
    private suspend fun booksIn(server: ServerConfig, sectionId: String): List<AudioBook> {
        val entries = fetchAllPlexItems { start, size -> api.getSectionAlbums(server.baseUrl, server.token, sectionId, start, size) }
        val albums = LinkedHashMap<String, PlexMetadataDto>()
        for (entry in entries) {
            when (plexEntryKind(entry)) {
                PlexEntryKind.ALBUM -> albums.putIfAbsent(entry.ratingKey, entry)
                PlexEntryKind.ARTIST -> api.getChildren(server.baseUrl, server.token, entry.ratingKey)
                    .mediaContainer.metadata.orEmpty()
                    .filter { plexEntryKind(it) == PlexEntryKind.ALBUM }
                    // An album listed under its artist may leave out the artist's name; the artist's title is the author.
                    .forEach { albums.putIfAbsent(it.ratingKey, if (it.author.isNullOrBlank()) it.copy(author = entry.title) else it) }
                PlexEntryKind.OTHER -> Unit
            }
        }
        return albums.values.map { plexAlbumToBook(it, server.baseUrl, server.id, sectionId) }
    }

    override suspend fun fetchLibraries(server: ServerConfig): List<LibraryInfo> = librariesOf(server, musicSections(server))

    private fun librariesOf(server: ServerConfig, sections: List<PlexSectionDto>): List<LibraryInfo> {
        if (!server.libraryId.isNullOrBlank()) {
            val name = sections.firstOrNull { it.key == server.libraryId }?.title ?: server.libraryId
            return listOf(LibraryInfo(server.id, server.libraryId, name))
        }
        return sections.map { LibraryInfo(server.id, it.key, it.title) }
    }

    /** Plex has no collections in the app yet. */
    override suspend fun fetchCollections(server: ServerConfig): List<BookCollection> = emptyList()

    /** "artist" == music/audiobook section in Plex. */
    private suspend fun musicSections(server: ServerConfig): List<PlexSectionDto> =
        api.getLibrarySections(baseUrl = server.baseUrl, token = server.token)
            .mediaContainer
            .directories
            ?.filter { it.type == "artist" }
            ?: emptyList()
}

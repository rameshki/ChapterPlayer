package com.chapterplayer.domain.browse

/*
 * Favourites are kept on this phone only (Audiobookshelf has no favourites to sync with). They are stored per book id,
 * but a favourite belongs to the list entry: a book several servers have is one entry, and it is a favourite when any of
 * its copies is.
 */

/** Whether [item] (one entry, merged or not) is a favourite: any of its copies is in [favouriteIds]. */
fun isFavourite(item: BrowseItem, favouriteIds: Set<String>): Boolean =
    favouriteIds.isNotEmpty() && item.allSources.any { it.book.id in favouriteIds }

/**
 * The book ids a favourite mark on [item] writes, and the ones unmarking removes: every copy, so marking covers the
 * whole entry, a later "Not the same book" leaves each copy a favourite, and unmarking leaves no copy behind that would
 * keep the entry a favourite.
 */
fun favouriteRowIds(item: BrowseItem): List<String> = item.allSources.map { it.book.id }.distinct()

/** The Favourites shelf of Home: the favourite entries of [items] by title, at most [limit]; empty hides the shelf. */
fun favouritesShelf(items: List<BrowseItem>, favouriteIds: Set<String>, limit: Int = FAVOURITES_SHELF_LIMIT): List<BrowseItem> =
    if (favouriteIds.isEmpty()) emptyList()
    else sortItems(items.filter { isFavourite(it, favouriteIds) }, BrowseSort.TITLE).take(limit)

const val FAVOURITES_SHELF_LIMIT = 20

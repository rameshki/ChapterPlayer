package com.chapterplayer.domain.jellyfin

/** The app's book ids for Jellyfin items start with this; the server only knows the part after it (the item id). */
const val JELLYFIN_BOOK_PREFIX = "jf_"

/** The app's book id for the Jellyfin item [itemId] (the 32-hex id exactly as the server sends it). */
fun jellyfinBookId(itemId: String): String = JELLYFIN_BOOK_PREFIX + itemId

/** The Jellyfin item id of the app book id [bookId], or null when it is not a Jellyfin book. */
fun jellyfinItemId(bookId: String): String? =
    if (bookId.startsWith(JELLYFIN_BOOK_PREFIX) && bookId.length > JELLYFIN_BOOK_PREFIX.length) {
        bookId.substring(JELLYFIN_BOOK_PREFIX.length)
    } else {
        null
    }

/** Whether [bookId] names a Jellyfin book. */
fun isJellyfinBook(bookId: String): Boolean = jellyfinItemId(bookId) != null

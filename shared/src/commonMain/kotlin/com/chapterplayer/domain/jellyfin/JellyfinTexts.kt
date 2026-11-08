package com.chapterplayer.domain.jellyfin

const val JELLYFIN_NOT_JELLYFIN_TEXT = "This is not a Jellyfin server"
const val JELLYFIN_TOO_OLD_TEXT = "This Jellyfin version is too old (10.10 or newer is needed)"
const val JELLYFIN_WRONG_PASSWORD_TEXT = "Wrong username or password"
const val JELLYFIN_NO_BOOKS_TEXT = "The server has no Books library. Create one with the type Books."
const val JELLYFIN_OTHER_SERVER_TEXT =
    "This address now belongs to another Jellyfin server. Remove this server and add it again."
const val JELLYFIN_INVALID_ADDRESS_TEXT = "Enter a valid server address."
const val JELLYFIN_REDIRECT_TEXT =
    "This address sends the app somewhere else. Check it, for example https:// instead of http://, or a path such as /jellyfin."
const val JELLYFIN_TLS_TEXT =
    "Couldn't make a secure connection to the server. Check its certificate, or use its http:// address at home."
const val JELLYFIN_ID_MISSING_TEXT = "This server can't be checked any more. Remove it and add it again."
const val JELLYFIN_ID_UNREADABLE_TEXT = "Couldn't check which server this is. Please try again."
/** A failing view, after its name ("Audiobooks: ..."), when the answer was not Jellyfin's JSON. */
const val JELLYFIN_UNREADABLE_VIEW_TEXT = "the server's answer could not be read"
/** A whole sync whose every view answered something unreadable. */
const val JELLYFIN_UNREADABLE_SYNC_TEXT = "The server's answer could not be read"
/** The most the public-info answer may be: a real one is well under 1 KB. */
const val JELLYFIN_PUBLIC_INFO_MAX_BYTES = 64 * 1024
/** A sync whose address did not answer as this Jellyfin server (a web page, an error, another app): nothing was sent. */
const val JELLYFIN_NOT_THIS_SERVER_TEXT =
    "This address no longer answers as your Jellyfin server, so nothing was sent. Check the address in Settings."

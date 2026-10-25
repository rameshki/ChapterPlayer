package com.chapterplayer.domain.downloads

/** One audio file as the server lists it; any field may be missing. */
data class AudioFileEntry(
    val ino: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    val index: Int?,
    /** The server marks a file as left out of the book. */
    val excluded: Boolean = false,
    /** The file's length. */
    val durationMs: Long? = null,
    val mimeType: String? = null,
)

/**
 * A file to download: the server's file id, a safe display name, its size, its place in the book (0 first), and
 * its length (0 when unknown) and media type ("" when unknown), so the book can play without the server.
 */
data class PlannedFile(
    val ino: String,
    val fileName: String,
    val totalBytes: Long,
    val sortOrder: Int,
    val durationMs: Long = 0L,
    val mimeType: String = "",
)

/**
 * The files of a book to download, in book order (by the server's index; entries without one keep
 * their order after the indexed ones). Entries with no ino, no size, a size of 0 or less, marked as
 * excluded, or repeating an ino already planned are dropped.
 */
fun planFiles(entries: List<AudioFileEntry>): List<PlannedFile> {
    val seen = HashSet<String>()
    return entries
        .withIndex()
        .sortedWith(compareBy({ it.value.index == null }, { it.value.index ?: 0 }, { it.index }))
        .map { it.value }
        .filter { entry ->
            val ino = entry.ino?.trim().orEmpty()
            ino.isNotEmpty() && (entry.sizeBytes ?: 0L) > 0L && !entry.excluded && seen.add(ino)
        }
        .mapIndexed { order, entry ->
            val ino = entry.ino!!.trim()
            PlannedFile(
                ino = ino,
                fileName = safeFileName(entry.fileName ?: ino),
                totalBytes = entry.sizeBytes!!,
                sortOrder = order,
                durationMs = entry.durationMs?.coerceAtLeast(0L) ?: 0L,
                mimeType = entry.mimeType?.trim().orEmpty()
            )
        }
}

/**
 * A name that stays inside the folder it is put in: only the last path segment is kept, and control
 * characters, colons, parent steps ("..") and leading dots are removed. Never empty ("file" instead).
 */
fun safeFileName(name: String): String {
    val noControls = name.filter { it.code >= 32 && it != '\u007F' }
    val lastSegment = noControls.split('/', '\\').lastOrNull { it.isNotBlank() } ?: ""
    var cleaned = lastSegment.replace(":", "")
    while (".." in cleaned) cleaned = cleaned.replace("..", ".")
    cleaned = cleaned.trim().trimStart('.').trim()
    return cleaned.ifEmpty { "file" }
}

private val EXTENSION = Regex("[a-z0-9]{1,8}")

/** The lower case extension of a server file name ("mp3", "m4b"), or "bin" when there is no usable one. */
fun fileExtension(fileName: String?): String {
    val segment = fileName?.split('/', '\\')?.lastOrNull { it.isNotBlank() } ?: return DEFAULT_EXTENSION
    val dot = segment.lastIndexOf('.')
    if (dot < 0) return DEFAULT_EXTENSION
    val ext = segment.substring(dot + 1).lowercase()
    return if (EXTENSION.matches(ext)) ext else DEFAULT_EXTENSION
}

/** The name a finished file gets in the book folder: `<ino>.<ext>`, made safe. */
fun finalFileName(ino: String, ext: String): String = safeFileName("$ino.$ext")

/** The name a file has while it downloads: the final name plus `.part`. */
fun partFileName(ino: String, ext: String): String = finalFileName(ino, ext) + PART_SUFFIX

const val PART_SUFFIX = ".part"
private const val DEFAULT_EXTENSION = "bin"

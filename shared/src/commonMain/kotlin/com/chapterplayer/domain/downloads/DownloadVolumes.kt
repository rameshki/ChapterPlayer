package com.chapterplayer.domain.downloads

/** The id of the app's own folder in internal storage, the default download location. */
const val INTERNAL_VOLUME_ID = "internal"

/** A place downloads can go: internal storage, or an app folder on another volume (an SD card). */
data class DownloadVolume(val id: String, val label: String, val freeBytes: Long)

/** The volume new downloads go to: the chosen one while it is there, else the first (internal storage). */
fun chooseVolume(selectedId: String?, available: List<DownloadVolume>): DownloadVolume? =
    available.firstOrNull { it.id == (selectedId ?: INTERNAL_VOLUME_ID) } ?: available.firstOrNull()

/** Names for the app folders on external volumes, in order; [removable] says which are SD cards. */
fun externalVolumeLabels(removable: List<Boolean>): List<String> {
    val cards = removable.count { it }
    var card = 0
    return removable.map { isCard ->
        when {
            !isCard -> "Phone storage (app folder)"
            cards == 1 -> "SD card"
            else -> "SD card ${++card}"
        }
    }
}

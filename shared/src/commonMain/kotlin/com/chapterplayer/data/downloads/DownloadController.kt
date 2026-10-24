package com.chapterplayer.data.downloads

/**
 * Starts and stops book downloads. The work runs in the background (on Android, WorkManager);
 * the `downloads` table holds the state the UI shows. Deleting a download never touches the server.
 */
interface DownloadController {
    /** Queues the book; a book already on the phone is left as it is. */
    suspend fun enqueue(bookId: String)

    /** Stops the download and keeps what was fetched, so [resume] continues at the same byte. */
    suspend fun pause(bookId: String)

    /** Continues a paused or failed download. */
    suspend fun resume(bookId: String)

    /** Stops the download and deletes its files and rows. */
    suspend fun cancel(bookId: String)

    /** Deletes a download (finished or not): its files and rows. */
    suspend fun remove(bookId: String)

    /** The Wi-Fi only setting changed: waiting and running downloads are scheduled again under the new rule. */
    suspend fun reapplyWifiSetting()
}

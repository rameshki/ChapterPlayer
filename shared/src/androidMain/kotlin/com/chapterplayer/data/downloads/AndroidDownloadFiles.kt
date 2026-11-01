package com.chapterplayer.data.downloads

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The phone's file system on java.io and nio: what the downloader did before it was common code. */
object AndroidDownloadFiles : DownloadFiles {
    override fun length(path: String): Long? = File(path).takeIf { it.isFile }?.length()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun makeDirectories(path: String): Boolean = File(path).let { it.isDirectory || it.mkdirs() }

    override fun openAppend(path: String, at: Long): DownloadSink {
        val file = File(path)
        RandomAccessFile(file, "rw").use { it.setLength(at) }
        return StreamSink(FileOutputStream(file, true))
    }

    override fun sync(path: String) {
        FileOutputStream(File(path), true).use { it.fd.sync() }
    }

    override fun moveReplacing(from: String, to: String) {
        val source = File(from).toPath()
        val target = File(to).toPath()
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override fun delete(path: String): Boolean = File(path).delete()

    override fun deleteRecursively(path: String) {
        File(path).deleteRecursively()
    }

    override fun list(path: String): List<String> = File(path).list()?.toList().orEmpty()

    override fun freeBytes(path: String): Long = File(path).usableSpace

    override fun child(parent: String, name: String): String = File(parent, name).path

    override fun parent(path: String): String? = File(path).absoluteFile.parent

    override fun name(path: String): String = File(path).name

    /** A buffered append stream; [sync] reaches the disk through the file descriptor. */
    private class StreamSink(private val stream: FileOutputStream) : DownloadSink {
        private val out = BufferedOutputStream(stream, BUFFER_BYTES)

        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)

        override fun flush() = out.flush()

        override fun sync() {
            out.flush()
            stream.fd.sync()
        }

        override fun close() = out.close()
    }

    private const val BUFFER_BYTES = 64 * 1024
}

internal actual fun platformDownloadFiles(): DownloadFiles = AndroidDownloadFiles

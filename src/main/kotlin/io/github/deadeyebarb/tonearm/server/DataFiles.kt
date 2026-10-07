package io.github.deadeyebarb.tonearm.server

import java.io.File
import java.io.IOException

/**
 * Writing in DATA_DIR. Folders that are gone are made again, and a write that can't happen says what to
 * fix instead of only naming a missing file.
 */
object DataFiles {
    /** Replaces [file] with [text] all at once, through a temporary file next to it. */
    fun write(file: File, text: String) = attempt(file) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("couldn't replace ${file.name}")
        }
    }

    fun append(file: File, text: String) = attempt(file) { file.appendText(text) }

    /** Why [dir] can't be written in, or null when it can. */
    fun unwritable(dir: File): String? {
        val probe = File(dir, ".write-check")
        return try {
            dir.mkdirs()
            probe.writeText("ok")
            probe.delete()
            null
        } catch (e: Exception) {
            problem(dir, e)
        }
    }

    private fun attempt(file: File, block: () -> Unit) {
        try {
            file.parentFile.mkdirs()
            block()
        } catch (e: Exception) {
            throw IOException(problem(file.parentFile, e), e)
        }
    }

    private fun problem(dir: File, e: Exception) =
        "Can't save in $dir (${e.message ?: e.javaClass.simpleName}). DATA_DIR has to be writable for the server " +
            "(user ${System.getProperty("user.name")}, uid 1000 in the image); for a folder mounted from the host: chown -R 1000:1000 it."
}

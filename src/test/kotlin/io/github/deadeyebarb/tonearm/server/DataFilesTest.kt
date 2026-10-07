package io.github.deadeyebarb.tonearm.server

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataFilesTest {
    private val dir = Files.createTempDirectory("tonearm-data").toFile()

    @Test
    fun aFolderThatWentAwayIsMadeAgain() {
        val store = Store(File(dir, "store"))
        File(dir, "store").deleteRecursively()
        assertTrue(store.put("brab", "weekly-picks", "{}", 0).first)
        assertEquals("{}", Store(File(dir, "store")).get("brab", "weekly-picks").value)
    }

    @Test
    fun aDataDirThatCantBeWrittenSaysWhatToFix() {
        assertNull(DataFiles.unwritable(dir))
        val locked = File(dir, "locked").apply { mkdirs(); setWritable(false) }
        val problem = DataFiles.unwritable(File(locked, "data"))
        assertNotNull(problem)
        assertTrue("chown" in problem)
        assertFailsWith<IOException> { DataFiles.write(File(locked, "data/store/brab.json"), "{}") }
        locked.setWritable(true)
    }

    @Test
    fun picksThatCantBeSavedStillShowWithWhy() {
        val discovery = Discovery("http://127.0.0.1:9", dir)
        File(dir, "discovery").apply { deleteRecursively(); writeText("not a folder") }
        var picks = discovery.picks("brab", mapOf("u" to "brab", "p" to "x"), refresh = true)
        repeat(100) { if (picks.running) { Thread.sleep(50); picks = discovery.picks("brab", emptyMap(), refresh = false) } }
        assertTrue(picks.problem!!.startsWith("Can't save in"), picks.problem)
    }
}

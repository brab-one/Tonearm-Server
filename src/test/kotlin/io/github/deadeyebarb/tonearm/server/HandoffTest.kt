package io.github.deadeyebarb.tonearm.server

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HandoffTest {
    private val dir = Files.createTempDirectory("tonearm-handoffs").toFile()
    private var now = 1_760_000_000_000L
    private var writes = 0
    private val counting: (File, String) -> Unit = { file, text -> writes++; DataFiles.write(file, text) }

    private fun handoffs() = Handoffs(dir, { now }, counting)

    private fun state(id: String, playing: Boolean, index: Int = 1, songs: List<String> = listOf("al1-1", "al1-2", "al1-3"), position: Long = 20_000) =
        """{"id":"$id","name":"Tonearm on $id","kind":"desktop","server":"https://music.example","playback":{"playing":$playing,"positionMs":$position,""" +
            """"at":$now,"index":$index,"queue":[${songs.joinToString(",") { """{"id":"$it","title":"Song $it"}""" }}]}}"""

    private val always: (String) -> Boolean = { true }
    private val never: (String) -> Boolean = { false }

    @Test
    fun aStateWithASongIsKeptAndAnEmptyOneDoesntReplaceIt() {
        val handoffs = handoffs()
        handoffs.record("alice", "desk", state("desk", playing = true))
        now += 5_000
        handoffs.record("alice", "desk", """{"id":"desk","name":"Tonearm on desk","kind":"desktop","playback":{"playing":false,"index":-1,"queue":[]}}""")
        val latest = assertNotNull(handoffs.latest("alice", "phone", always))
        assertTrue("al1-2" in latest.state)
        assertEquals("desk", latest.device)
        assertEquals("Tonearm on desk", latest.name)
        assertEquals(now - 5_000, latest.playedAt)
        // The empty publish still says it was there.
        assertEquals(now, latest.seenAt)
        assertTrue(latest.online)
        assertTrue(latest.playing)
    }

    @Test
    fun playedAtMovesOnlyWhilePlaying() {
        val handoffs = handoffs()
        handoffs.record("alice", "desk", state("desk", playing = false))
        assertNull(handoffs.latest("alice", null, always))
        handoffs.record("alice", "desk", state("desk", playing = true))
        val played = now
        now += 60_000
        handoffs.record("alice", "desk", state("desk", playing = false))
        val latest = assertNotNull(handoffs.latest("alice", null, always))
        assertEquals(played, latest.playedAt)
        assertFalse(latest.playing)
    }

    @Test
    fun aGoodbyeKeepsItAndARestartReadsItBack() {
        handoffs().apply {
            record("alice", "desk", state("desk", playing = true))
            gone("alice", "desk")
            val latest = assertNotNull(latest("alice", "phone", always))
            assertFalse(latest.online)
            assertFalse(latest.playing)
        }
        val again = assertNotNull(handoffs().latest("alice", "phone", always))
        assertEquals("desk", again.device)
        assertFalse(again.online)
        assertTrue("\"index\":1" in again.state)
    }

    @Test
    fun theAskerIsLeftOutAndOldOnesDontCount() {
        val handoffs = handoffs()
        handoffs.record("alice", "desk", state("desk", playing = true))
        assertNull(handoffs.latest("alice", "desk", always))
        assertNull(handoffs.latest("bob", "phone", always))
        now += 15L * 24 * 3_600_000
        assertNull(handoffs.latest("alice", "phone", always))
    }

    @Test
    fun theNewestOfTwoAndOnlyTwoAreKept() {
        val handoffs = handoffs()
        handoffs.record("alice", "laptop", state("laptop", playing = true))
        now += 1_000
        handoffs.record("alice", "desk", state("desk", playing = true))
        now += 1_000
        handoffs.record("alice", "phone", state("phone", playing = true))
        assertEquals("desk", handoffs.latest("alice", "phone", never)?.device)
        handoffs.flush()
        val read = handoffs()
        assertEquals("phone", read.latest("alice", null, never)?.device)
        assertEquals("desk", read.latest("alice", "phone", never)?.device)
        // The laptop went: it played longest ago.
        assertTrue("laptop" !in File(dir, "connect/alice.json").readText())
    }

    @Test
    fun aDevicePlayingAlongIsWrittenAtMostOnceAMinute() {
        val handoffs = handoffs()
        handoffs.record("alice", "desk", state("desk", playing = true, position = 0))
        assertEquals(1, writes)
        // Every 5 seconds the position moves on: nothing to write each time.
        repeat(11) {
            now += 5_000
            handoffs.record("alice", "desk", state("desk", playing = true, position = 5_000L * (it + 1)))
        }
        assertEquals(1, writes)
        now += 5_000
        handoffs.record("alice", "desk", state("desk", playing = true, position = 60_000))
        assertEquals(2, writes)
        // The next song is written at once, and so is a pause.
        handoffs.record("alice", "desk", state("desk", playing = true, index = 2))
        handoffs.record("alice", "desk", state("desk", playing = false, index = 2))
        assertEquals(4, writes)
        // What's left is written on the way out.
        now += 1_000
        handoffs.record("alice", "desk", state("desk", playing = false, index = 2, position = 1_000))
        assertEquals(4, writes)
        handoffs.flush()
        assertEquals(5, writes)
        handoffs.flush()
        assertEquals(5, writes)
    }

    @Test
    fun aBrokenStateIsIgnored() {
        val handoffs = handoffs()
        handoffs.record("alice", "desk", "not json")
        handoffs.record("alice", "desk", """{"id":"desk","playback":{"index":7,"queue":[{"id":"x"}]}}""")
        assertNull(handoffs.latest("alice", null, always))
        handoffs.record("alice", "desk", state("desk", playing = true))
        now += 1_000
        handoffs.record("alice", "desk", "{broken")
        val latest = assertNotNull(handoffs.latest("alice", null, always))
        assertTrue("al1-2" in latest.state)
        assertEquals(now, latest.seenAt)
    }

    @Test
    fun aFolderThatCantBeWrittenKeepsItInMemory() {
        File(dir, "connect").writeText("not a folder")
        val handoffs = handoffs()
        handoffs.record("alice", "desk", state("desk", playing = true))
        assertEquals("desk", handoffs.latest("alice", null, always)?.device)
    }
}

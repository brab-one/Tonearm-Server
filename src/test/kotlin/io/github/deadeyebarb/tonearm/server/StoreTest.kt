package io.github.deadeyebarb.tonearm.server

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreTest {
    private val dir = Files.createTempDirectory("tonearm-store").toFile()

    @Test
    fun writesNeedTheCurrentVersion() {
        val store = Store(dir)
        assertEquals(Store.Entry(0, null), store.get("alice", "pending-likes"))
        val (ok, first) = store.put("alice", "pending-likes", "one", 0)
        assertTrue(ok)
        assertEquals(1, first.version)
        val (stale, current) = store.put("alice", "pending-likes", "two", 0)
        assertFalse(stale)
        assertEquals("one", current.value)
        assertTrue(store.put("alice", "pending-likes", "two", 1).first)
        assertEquals(Store.Entry(2, "two"), store.get("alice", "pending-likes"))
    }

    @Test
    fun eachUserHasTheirOwnDocuments() {
        val store = Store(dir)
        store.put("alice", "pending-likes", "hers", 0)
        assertNull(store.get("bob", "pending-likes").value)
    }

    @Test
    fun documentsSurviveARestart() {
        Store(dir).put("alice", "pending-likes", "kept", 0)
        assertEquals(Store.Entry(1, "kept"), Store(dir).get("alice", "pending-likes"))
    }

    @Test
    fun aBrokenFileIsSetAsideInsteadOfFailing() {
        Store(dir).put("alice", "pending-likes", "x", 0)
        dir.listFiles()!!.single { it.name.endsWith(".json") }.writeText("{nope")
        val store = Store(dir)
        assertNull(store.get("alice", "pending-likes").value)
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".broken") })
    }

    @Test
    fun keysAreShortAndPlain() {
        assertTrue(Store.validKey("pending-likes"))
        assertFalse(Store.validKey("../etc"))
        assertFalse(Store.validKey("Pending"))
        assertFalse(Store.validKey(""))
        assertFalse(Store.validKey(null))
    }
}

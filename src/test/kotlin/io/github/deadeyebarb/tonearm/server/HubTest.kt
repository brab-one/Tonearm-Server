package io.github.deadeyebarb.tonearm.server

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HubTest {
    @Test
    fun publishedDevicesShowUpOnline() {
        val hub = UserHub()
        hub.publish("desk", "{\"a\":1}")
        val devices = hub.list()
        assertEquals(listOf("desk"), devices.map { it.id })
        assertTrue(devices[0].online)
        assertEquals("{\"a\":1}", devices[0].state)
    }

    @Test
    fun commandsReachOnlyTheirTarget() {
        val hub = UserHub()
        hub.publish("desk", "{}")
        val seq = hub.send("phone", "desk", "play")!!
        val (commands, last) = hub.poll("desk", 0, 0)
        assertEquals(listOf("play"), commands.map { it.payload })
        assertEquals("phone", commands[0].from)
        assertEquals(seq, last)
        assertTrue(hub.poll("desk", last, 0).first.isEmpty())
        assertTrue(hub.poll("other", 0, 0).first.isEmpty())
    }

    @Test
    fun numbersKeepGrowingAcrossRestarts() {
        val before = UserHub().apply { publish("desk", "{}") }.send("phone", "desk", "play")!!
        Thread.sleep(2)
        val (commands, _) = UserHub().apply { publish("desk", "{}"); send("phone", "desk", "pause") }.poll("desk", before, 0)
        assertEquals(listOf("pause"), commands.map { it.payload })
    }

    @Test
    fun aWaitingPollWakesUpForANewCommand() {
        val hub = UserHub()
        var got = emptyList<UserHub.Command>()
        val started = System.currentTimeMillis()
        val poller = thread { got = hub.poll("desk", 0, 10_000).first }
        Thread.sleep(200)
        hub.send("phone", "desk", "pause")
        poller.join()
        assertEquals(listOf("pause"), got.map { it.payload })
        assertTrue(System.currentTimeMillis() - started < 5_000)
    }

    @Test
    fun inboxesKeepTheNewestCommands() {
        val hub = UserHub()
        hub.publish("desk", "{}")
        repeat(UserHub.MAX_QUEUED + 10) { hub.send("phone", "desk", "c$it") }
        val commands = hub.poll("desk", 0, 0).first
        assertEquals(UserHub.MAX_QUEUED, commands.size)
        assertEquals("c${UserHub.MAX_QUEUED + 9}", commands.last().payload)
    }

    @Test
    fun forgottenAndLongGoneDevicesDisappear() {
        val hub = UserHub()
        hub.publish("desk", "{}")
        hub.publish("phone", "{}")
        hub.forget("desk")
        assertEquals(listOf("phone"), hub.list().map { it.id })
        assertTrue(hub.list(System.currentTimeMillis() + UserHub.FORGET_AFTER_MS + 1).isEmpty())
    }

    @Test
    fun commandsOnlyGoToDevicesThereAre() {
        val hub = UserHub()
        assertNull(hub.send("phone", "desk", "play"))
        hub.publish("desk", "{}")
        hub.forget("desk")
        assertNull(hub.send("phone", "desk", "play"))
        assertTrue(hub.poll("desk", 0, 0).first.isEmpty())
    }

    @Test
    fun aNewDevicePushesOutTheOneGoneLongest() {
        val hub = UserHub()
        repeat(UserHub.MAX_DEVICES) { hub.publish("old-$it", "{}"); Thread.sleep(2) }
        hub.send("phone", "old-0", "play")
        hub.publish("new", "{}")
        assertEquals(UserHub.MAX_DEVICES, hub.list().size)
        assertFalse(hub.list().any { it.id == "old-0" })
        assertNull(hub.send("phone", "old-0", "play"))
    }

    @Test
    fun aUsersDevicesShareTheirRoomAndTheOldestCommandsGo() {
        val hub = UserHub()
        hub.publish("desk", "{}")
        hub.publish("laptop", "{}")
        val big = "x".repeat(UserHub.MAX_CHARS / 5)
        repeat(3) { hub.send("phone", "desk", big + it) }
        repeat(3) { hub.send("phone", "laptop", big + it) }
        val desk = hub.poll("desk", 0, 0).first
        val laptop = hub.poll("laptop", 0, 0).first
        assertEquals(listOf(big + 2), desk.map { it.payload })
        assertEquals(listOf(big + 0, big + 1, big + 2), laptop.map { it.payload })
    }

    @Test
    fun usersDontShareDevices() {
        val hub = Hub()
        hub.of("alice").publish("desk", "{}")
        assertTrue(hub.of("bob").list().isEmpty())
        assertEquals(1, hub.of("alice").list().size)
    }
}

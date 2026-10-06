package io.github.deadeyebarb.tonearm.server

import java.util.concurrent.ConcurrentHashMap

/** One user's devices: their published states and the commands waiting for them. */
class UserHub {
    private val lock = Object()
    private val devices = HashMap<String, Device>()
    private val inboxes = HashMap<String, MutableList<Command>>()
    /** Starts at the clock, so numbers keep growing across restarts and devices can resume from the last one they saw. */
    private var seq = System.currentTimeMillis()

    private class Device(var state: String? = null, var lastSeen: Long = 0)

    data class Command(val seq: Long, val from: String, val payload: String)

    data class Entry(val id: String, val online: Boolean, val secondsSinceSeen: Long, val state: String?)

    fun publish(device: String, state: String): Long = synchronized(lock) {
        touch(device).state = state
        lock.notifyAll()
        seq
    }

    fun list(now: Long = System.currentTimeMillis()): List<Entry> = synchronized(lock) {
        devices.entries.removeIf { (id, d) -> (now - d.lastSeen > FORGET_AFTER_MS).also { if (it) inboxes.remove(id) } }
        devices.map { (id, d) -> Entry(id, now - d.lastSeen < ONLINE_WINDOW_MS, (now - d.lastSeen) / 1000, d.state) }.sortedBy { it.id }
    }

    fun send(from: String, target: String, payload: String): Long = synchronized(lock) {
        val inbox = inboxes.getOrPut(target) { mutableListOf() }
        val next = ++seq
        inbox += Command(next, from, payload)
        if (inbox.size > MAX_QUEUED) inbox.subList(0, inbox.size - MAX_QUEUED).clear()
        lock.notifyAll()
        next
    }

    /** Commands for [device] newer than [after], waiting up to [waitMs] for one. Polling counts as being online. */
    fun poll(device: String, after: Long, waitMs: Long): Pair<List<Command>, Long> = synchronized(lock) {
        val deadline = System.currentTimeMillis() + waitMs
        touch(device)
        var pending = waiting(device, after)
        var left = waitMs
        while (pending.isEmpty() && left > 0) {
            lock.wait(left)
            pending = waiting(device, after)
            left = deadline - System.currentTimeMillis()
        }
        touch(device)
        pending to maxOf(after, pending.maxOfOrNull { it.seq } ?: after)
    }

    private fun waiting(device: String, after: Long) = inboxes[device].orEmpty().filter { it.seq > after }

    fun forget(device: String): Unit = synchronized(lock) {
        devices.remove(device)
        inboxes.remove(device)
        lock.notifyAll()
    }

    private fun touch(device: String) = devices.getOrPut(device) { Device() }.also { it.lastSeen = System.currentTimeMillis() }

    companion object {
        const val ONLINE_WINDOW_MS = 45_000L
        const val FORGET_AFTER_MS = 7L * 24 * 3_600_000
        const val MAX_QUEUED = 50
    }
}

/** Every user's hub; users never see each other's devices. */
class Hub {
    private val users = ConcurrentHashMap<String, UserHub>()
    fun of(user: String): UserHub = users.computeIfAbsent(user) { UserHub() }
}

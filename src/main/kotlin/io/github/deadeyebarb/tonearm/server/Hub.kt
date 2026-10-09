package io.github.deadeyebarb.tonearm.server

import java.util.concurrent.ConcurrentHashMap

/**
 * One user's devices: their published states and the commands waiting for them. All in memory, so a user has at
 * most [MAX_DEVICES] devices, and [MAX_CHARS] of states and commands between them.
 */
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
        val d = touch(device)
        makeRoom(state.length - (d.state?.length ?: 0))
        d.state = state
        lock.notifyAll()
        seq
    }

    fun list(now: Long = System.currentTimeMillis()): List<Entry> = synchronized(lock) {
        devices.entries.removeIf { (id, d) -> (now - d.lastSeen > FORGET_AFTER_MS).also { if (it) inboxes.remove(id) } }
        inboxes.keys.retainAll(devices.keys)
        devices.map { (id, d) -> Entry(id, now - d.lastSeen < ONLINE_WINDOW_MS, (now - d.lastSeen) / 1000, d.state) }.sortedBy { it.id }
    }

    /**
     * Queues [payload] for [target]; null when that isn't one of the user's devices (one that comes back skips what
     * was sent while it was gone anyway).
     */
    fun send(from: String, target: String, payload: String): Long? = synchronized(lock) {
        if (target !in devices) return null
        makeRoom(payload.length)
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

    /** Whether [device] published or polled in the last [ONLINE_WINDOW_MS]. */
    fun isOnline(device: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        devices[device]?.let { now - it.lastSeen < ONLINE_WINDOW_MS } == true
    }

    fun forget(device: String): Unit = synchronized(lock) {
        devices.remove(device)
        inboxes.remove(device)
        lock.notifyAll()
    }

    private fun touch(device: String): Device {
        if (device !in devices && devices.size >= MAX_DEVICES) {
            // A new one pushes out the one gone longest, with what was waiting for it.
            devices.minBy { it.value.lastSeen }.key.let { gone -> devices.remove(gone); inboxes.remove(gone) }
        }
        return devices.getOrPut(device) { Device() }.also { it.lastSeen = System.currentTimeMillis() }
    }

    /** Drops the oldest commands until [more] characters more fit in [MAX_CHARS] (states are kept small by the server). */
    private fun makeRoom(more: Int) {
        var over = devices.values.sumOf { it.state?.length ?: 0 } + inboxes.values.sumOf { box -> box.sumOf { it.payload.length } } + more - MAX_CHARS
        while (over > 0) {
            val oldest = inboxes.values.filter { it.isNotEmpty() }.minByOrNull { it.first().seq } ?: return
            over -= oldest.removeAt(0).payload.length
        }
    }

    companion object {
        const val ONLINE_WINDOW_MS = 45_000L
        const val FORGET_AFTER_MS = 7L * 24 * 3_600_000
        const val MAX_QUEUED = 50
        const val MAX_DEVICES = 10
        /** Everything a user's devices published and were sent, together (characters). */
        const val MAX_CHARS = 6_000_000
    }
}

/** Every user's hub; users never see each other's devices. */
class Hub {
    private val users = ConcurrentHashMap<String, UserHub>()
    fun of(user: String): UserHub = users.computeIfAbsent(user) { UserHub() }
}

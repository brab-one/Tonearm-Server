package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URLEncoder

/** Where another device left off, for "Continue from the desktop": its last state with a song in it, as it published it. */
@Serializable
data class Handoff(
    val device: String,
    val name: String = "",
    /** The hub saw it lately and it didn't say goodbye. */
    val online: Boolean = false,
    /** Online, and its state says it's playing. */
    val playing: Boolean = false,
    /** The last time it published that it was playing. */
    val playedAt: Long = 0,
    /** The last time it published anything. */
    val seenAt: Long = 0,
    /** The DeviceState JSON as published. */
    val state: String = "",
)

/**
 * Each user's devices' last states with a song in them, kept through their goodbye ([gone]) and through restarts, in
 * DATA_DIR/connect/<user>.json: the hub only holds what's online. The two devices that played last are kept. A device
 * publishes every few seconds while it plays, so the file is written when the song, its place or playing changes, else
 * at most once a minute; [flush] writes the rest (on shutdown).
 */
class Handoffs(
    dataDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val write: (File, String) -> Unit = DataFiles::write,
) {
    private val dir = File(dataDir, "connect")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val users = HashMap<String, MutableList<Kept>>()
    /** Users with changes not written yet, and when each was last written. */
    private val dirty = HashSet<String>()
    private val written = HashMap<String, Long>()

    @Serializable
    private data class Kept(
        val device: String,
        val name: String = "",
        val state: String = "",
        val playedAt: Long = 0,
        val seenAt: Long = 0,
        val playing: Boolean = false,
        /** Said goodbye (forget). */
        val gone: Boolean = false,
        /** What it plays: the song's id, its place in the queue. */
        val song: String = "",
        val index: Int = -1,
    )

    /** A device published [state]: kept when it has a song; otherwise (or when it isn't a state) only [Kept.seenAt] moves. */
    @Synchronized
    fun record(user: String, device: String, state: String) {
        val now = clock()
        val list = load(user)
        val before = list.firstOrNull { it.device == device }
        val parsed = runCatching { json.parseToJsonElement(state).jsonObject }.getOrNull()
        val playback = parsed?.get("playback") as? JsonObject
        val index = (playback?.get("index") as? JsonPrimitive)?.intOrNull ?: -1
        val song = ((playback?.get("queue") as? JsonArray)?.getOrNull(index) as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
        val next = if (song == null) {
            before?.copy(seenAt = now, gone = false) ?: return
        } else {
            val playing = (playback["playing"] as? JsonPrimitive)?.booleanOrNull == true
            Kept(
                device = device,
                name = (parsed["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                state = state,
                playedAt = if (playing) now else before?.playedAt ?: 0,
                seenAt = now,
                playing = playing,
                song = song,
                index = index,
            )
        }
        list.removeAll { it.device == device }
        list += next
        // The two that played last; one that never played goes first.
        list.sortWith(compareByDescending<Kept> { it.playedAt }.thenByDescending { it.seenAt })
        while (list.size > KEEP) list.removeAt(list.lastIndex)
        if (before == null && list.none { it.device == device }) return
        dirty += user
        val changed = before == null || before.song != next.song || before.index != next.index || before.playing != next.playing || before.gone
        if (changed || now - (written[user] ?: 0) >= WRITE_EVERY_MS) save(user)
    }

    /** The device said goodbye: kept, but not online anymore. */
    @Synchronized
    fun gone(user: String, device: String) {
        val list = load(user)
        val i = list.indexOfFirst { it.device == device }
        if (i < 0 || list[i].gone) return
        list[i] = list[i].copy(gone = true)
        dirty += user
        save(user)
    }

    /**
     * The device that played last, other than [except], if that was within [MAX_AGE_MS]; [online] says whether the hub
     * sees a device now.
     */
    @Synchronized
    fun latest(user: String, except: String?, online: (String) -> Boolean): Handoff? {
        val now = clock()
        val kept = load(user).filter { it.device != except && it.playedAt > 0 && now - it.playedAt <= MAX_AGE_MS }.maxByOrNull { it.playedAt } ?: return null
        val here = !kept.gone && online(kept.device)
        return Handoff(kept.device, kept.name, here, here && kept.playing, kept.playedAt, kept.seenAt, kept.state)
    }

    /** Writes what hasn't been written yet. */
    @Synchronized
    fun flush() {
        dirty.toList().forEach(::save)
    }

    private fun save(user: String) {
        try {
            write(file(user), json.encodeToString(ListSerializer(Kept.serializer()), load(user)))
            dirty -= user
            written[user] = clock()
        } catch (e: Exception) {
            // Kept in memory and tried again with the next change; Connect itself works on.
            System.err.println("Couldn't save where $user's devices left off: ${e.message}")
            written[user] = clock()
        }
    }

    private fun load(user: String): MutableList<Kept> = users.getOrPut(user) {
        runCatching { json.decodeFromString(ListSerializer(Kept.serializer()), file(user).readText()) }.getOrDefault(emptyList()).toMutableList()
    }

    private fun file(user: String) = File(dir, URLEncoder.encode(user, Charsets.UTF_8) + ".json")

    /** The `resume` answer: `{"handoff": …}`, null when there's nothing to continue. */
    fun toJson(handoff: Handoff?): JsonObject =
        JsonObject(mapOf("handoff" to (handoff?.let { answer.encodeToJsonElement(Handoff.serializer(), it) } ?: JsonNull)))

    private val answer = Json { encodeDefaults = true }

    companion object {
        /** Devices kept per user. */
        const val KEEP = 2
        const val MAX_AGE_MS = 14 * 24 * 3_600_000L
        const val WRITE_EVERY_MS = 60_000L
    }
}

package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URLEncoder

/**
 * Small JSON documents the apps share, per user (e.g. likes of songs that aren't in the library yet),
 * saved in one file per user. A write only goes through when it names the current version, so two
 * devices never overwrite each other.
 */
class Store(private val dir: File) {
    @Serializable
    data class Entry(val version: Long = 0, val value: String? = null)

    private val serializer = MapSerializer(String.serializer(), Entry.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val users = HashMap<String, MutableMap<String, Entry>>()

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun get(user: String, key: String): Entry = load(user)[key] ?: Entry()

    /** Saves [value] if the stored version is still [ifVersion]; returns whether it did and what's stored now. */
    @Synchronized
    fun put(user: String, key: String, value: String, ifVersion: Long): Pair<Boolean, Entry> {
        val entries = load(user)
        val current = entries[key] ?: Entry()
        if (current.version != ifVersion) return false to current
        val next = Entry(current.version + 1, value)
        entries[key] = next
        save(user, entries)
        return true to next
    }

    private fun file(user: String) = File(dir, URLEncoder.encode(user, Charsets.UTF_8) + ".json")

    private fun load(user: String): MutableMap<String, Entry> = users.getOrPut(user) {
        val f = file(user)
        if (!f.exists()) return@getOrPut mutableMapOf()
        runCatching { json.decodeFromString(serializer, f.readText()).toMutableMap() }.getOrElse {
            f.copyTo(File(dir, f.name + ".broken"), overwrite = true)
            mutableMapOf()
        }
    }

    private fun save(user: String, entries: Map<String, Entry>) = DataFiles.write(file(user), json.encodeToString(serializer, entries))

    companion object {
        private val KEY = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
        fun validKey(key: String?) = key != null && KEY.matches(key)
    }
}

package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Plain API paths only: no "." or ".." segments that the upstream would resolve elsewhere. */
fun validPath(pattern: Regex, path: String) = pattern.matches(path) && path.split('/').none { segment -> segment.all { it == '.' } }

/** A call the apps make to Lidarr or Maloja through this server, and what comes back. */
data class ProxyCall(val method: String, val path: String, val rawQuery: String?, val contentType: String?, val body: ByteArray)

data class ProxyAnswer(val status: Int, val contentType: String?, val body: ByteArray, val cacheControl: String? = null)

/**
 * Lidarr or Maloja as this server reaches them, with their API key, which never leaves the server.
 * The apps' own login parameters are taken off before calls go on.
 */
class Upstream(
    val name: String,
    baseUrl: String,
    private val keyHeader: String?,
    private val key: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) {
    private val base = baseUrl.trimEnd('/')

    fun send(call: ProxyCall, extraQuery: List<Pair<String, String>> = emptyList(), body: ByteArray = call.body): ProxyAnswer {
        val query = (keptQuery(call.rawQuery) + extraQuery.map { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, Charsets.UTF_8) })
            .joinToString("&")
        val request = HttpRequest.newBuilder(URI.create("$base/${call.path}" + if (query.isEmpty()) "" else "?$query"))
            .timeout(Duration.ofSeconds(90))
            .method(call.method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .apply {
                keyHeader?.let { header(it, key) }
                header("Accept", "application/json, image/*;q=0.9, */*;q=0.5")
                if (body.isNotEmpty()) header("Content-Type", call.contentType ?: "application/json")
            }
            .build()
        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            ProxyAnswer(
                response.statusCode(),
                response.headers().firstValue("Content-Type").orElse(null),
                response.body(),
                // Lidarr marks missing covers as cacheable for a year; only pass that on for real answers.
                response.headers().firstValue("Cache-Control").orElse(null).takeIf { response.statusCode() in 200..299 },
            )
        } catch (e: Exception) {
            error(502, "Can't reach $name: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** JSON from a GET, for the server's own checks. */
    fun getJson(path: String): String? = get(path).let { (status, body) -> body.takeIf { status in 200..299 } }

    /** A GET's status and body (502 when it couldn't be asked). */
    fun get(path: String, query: String? = null): Pair<Int, String> =
        send(ProxyCall("GET", path, query, null, ByteArray(0))).let { it.status to it.body.decodeToString() }

    private fun keptQuery(raw: String?): List<String> = raw.orEmpty().split('&').filter { part ->
        part.isNotEmpty() && java.net.URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8).lowercase() !in DROPPED
    }

    companion object {
        /** The apps' Subsonic login and anything that could pass another key. */
        private val DROPPED = setOf("u", "t", "s", "p", "apikey", "v", "c", "f", "key")

        fun error(status: Int, message: String) =
            ProxyAnswer(status, "application/json", JsonObject(mapOf("error" to JsonPrimitive(message))).toString().encodeToByteArray())
    }
}

/** A user's own folder for their weekly picks, as Lidarr sees it, and whether Lidarr has it as a root folder yet. */
data class PicksFolder(val path: String, val ready: Boolean, val problem: String? = null)

/**
 * Lidarr for everyone on Navidrome. Admins get all of it (Brainarr, weekly picks, removing music).
 * Everyone else can look things up, see what's downloading or wanted, and request artists and albums,
 * which land in one of Lidarr's own root folders with no tags; nothing gets deleted or reconfigured.
 * With [picksRoot] (PICKS_FOLDER), each user has a folder of their own in it for their weekly picks, which
 * only they can add to and delete from (and which has its own Navidrome library, so only they see it).
 */
class LidarrProxy(private val lidarr: Upstream, private val requestsForEveryone: Boolean, private val picksRoot: String? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var roots: Pair<Set<String>, Long>? = null

    fun available(admin: Boolean) = admin || requestsForEveryone

    fun handle(call: ProxyCall, admin: Boolean, user: String = ""): ProxyAnswer {
        if (!validPath(PATH, call.path)) return Upstream.error(404, "Not a Lidarr API path")
        if (!available(admin)) return Upstream.error(403, "Requests through the Tonearm server are for Navidrome admins here")
        val endpoint = call.path.removePrefix("api/v1/")
        // Bodies that pick what to download are read the way Lidarr reads them (keys in any case), and sent on as read.
        val monitor = if (call.method == "PUT" && endpoint == "album/monitor") parseMonitor(call.body) ?: return badBody() else null
        val command = if (call.method == "POST" && endpoint == "command") parseCommand(call.body) ?: return badBody() else null
        // Someone's weekly picks are theirs alone, also for admins: nothing anyone asks for goes into their folder.
        intoOthersPicks(call, endpoint, user, monitor, command)?.let { return it }
        if (admin) return lidarr.send(call, body = monitor?.body() ?: call.body)
        val body = when (call.method) {
            "GET" -> if (READ.any { it.matches(endpoint) }) call.body else return denied()
            "POST" -> when (endpoint) {
                "artist" -> artistRequest(call.body, user) ?: return badRoot()
                "album" -> albumRequest(call.body, user) ?: return badRoot()
                "command" -> if (command!!.name in COMMANDS) command.body() else return denied()
                else -> return denied()
            }
            "PUT" -> if (monitor?.monitored == true) monitor.body() else return denied()
            // Their own weekly picks go again a week later: deleting is theirs to do, in their own folder only, and
            // without adding import list exclusions, which are everyone's.
            "DELETE" -> return if (inOwnPicks(endpoint, user)) lidarr.send(call.copy(rawQuery = deleteQuery(call.rawQuery)), body = ByteArray(0)) else denied()
            else -> return denied()
        }
        return lidarr.send(call, body = body)
    }

    /** [user]'s own picks folder, when there are picks folders. */
    fun picksFolder(user: String): String? = picksRoot?.let { root -> root.trimEnd('/') + "/" + folderName(user) }

    /**
     * Makes [user]'s picks folder one of Lidarr's root folders unless it is already, with the profiles of the first
     * one. Lidarr only takes folders that exist (and that it can write in): when it can't, says what to set up.
     * Throws when Lidarr can't be asked.
     */
    fun ensurePicksFolder(user: String): PicksFolder? {
        val path = picksFolder(user) ?: return null
        val existing = rootFolderList() ?: throw IllegalStateException("it didn't answer about its root folders")
        if (existing.any { it.string("path")?.trimEnd('/') == path }) {
            // An admin may have added it in Lidarr since the last look: requests into it have to know.
            roots = null
            return PicksFolder(path, ready = true)
        }
        val first = existing.firstOrNull()
        val body = buildJsonObject {
            put("name", "Tonearm picks: $user")
            put("path", path)
            put("defaultMetadataProfileId", first?.string("defaultMetadataProfileId")?.toIntOrNull() ?: 1)
            put("defaultQualityProfileId", first?.string("defaultQualityProfileId")?.toIntOrNull() ?: 1)
            put("defaultMonitorOption", "none")
            put("defaultNewItemMonitorOption", "none")
            putJsonArray("defaultTags") {}
        }
        val answer = lidarr.send(ProxyCall("POST", "api/v1/rootfolder", null, "application/json", body.toString().encodeToByteArray()))
        roots = null
        if (answer.status in 200..299) return PicksFolder(path, ready = true)
        // Their other device may have added it just now.
        if (rootFolderList().orEmpty().any { it.string("path")?.trimEnd('/') == path }) return PicksFolder(path, ready = true)
        // Lidarr's validation answers with a list of what's wrong; anything else is Lidarr not answering properly.
        val errors = runCatching {
            json.parseToJsonElement(answer.body.decodeToString()).jsonArray.mapNotNull { (it as? JsonObject)?.string("errorMessage") }
        }.getOrNull().orEmpty()
        if (answer.status !in 400..499 || errors.isEmpty()) throw IllegalStateException("it answered HTTP ${answer.status}")
        return PicksFolder(path, ready = false, problem = "Lidarr can't use $path yet (${errors.joinToString("; ")}). Make that folder, writable for Lidarr, and a Navidrome library for it that only $user can see.")
    }

    private fun artistRequest(body: ByteArray, user: String): ByteArray? = jsonObject(body)?.let { safeArtist(it, user) }?.toString()?.encodeToByteArray()

    private fun albumRequest(body: ByteArray, user: String): ByteArray? {
        val album = jsonObject(body)?.takeUnless(::hasTwins) ?: return null
        val (key, value) = album.entries.firstOrNull { it.key.equals("artist", ignoreCase = true) } ?: return null
        val artist = (value as? JsonObject)?.let { safeArtist(it, user) } ?: return null
        return JsonObject(album - key + ("artist" to artist)).toString().encodeToByteArray()
    }

    /**
     * An artist to add: into one of Lidarr's root folders (no path or folder of its own), without tags; never into
     * someone else's picks. Lidarr reads keys whatever their case, so they're checked that way too, each only once.
     */
    private fun safeArtist(artist: JsonObject, user: String): JsonObject? {
        if (hasTwins(artist)) return null
        val root = artist.entries.firstOrNull { it.key.equals("rootFolderPath", ignoreCase = true) }
            ?.let { (it.value as? JsonPrimitive)?.contentOrNull }?.trimEnd('/') ?: return null
        if (root !in rootFolders() && root !in rootFolders(fresh = true)) return null
        if (picksRoot != null && isUnder(root, picksRoot) && root != picksFolder(user)) return null
        return JsonObject(artist.filterKeys { it.lowercase() !in ARTIST_KEYS_SET_HERE } + ("rootFolderPath" to JsonPrimitive(root)) + ("tags" to JsonArray(emptyList())))
    }

    /** Two keys that are one for Lidarr ("path" and "Path"). */
    private fun hasTwins(o: JsonObject) = o.keys.groupBy { it.lowercase() }.any { it.value.size > 1 }

    /**
     * Whether the album or artist that `album/{id}` or `artist/{id}` names lives in [user]'s own picks folder: anywhere in
     * it by a path without "." or ".." in it, and Lidarr counting it to that very root folder.
     */
    private fun inOwnPicks(endpoint: String, user: String): Boolean {
        val own = picksFolder(user) ?: return false
        val artist = artistOf(endpoint) ?: return false
        val path = artist.string("path") ?: return false
        val root = artist.entries.firstOrNull { it.key.equals("rootFolderPath", ignoreCase = true) }?.let { (it.value as? JsonPrimitive)?.contentOrNull } ?: return false
        return isUnder(path, own) && clean(root) != null && clean(root) == clean(own)
    }

    /** The artist (as Lidarr has it) that `album/{id}` or `artist/{id}` is about. */
    private fun artistOf(endpoint: String): JsonObject? {
        val (kind, id) = Regex("^(album|artist)/(\\d+)$").matchEntire(endpoint)?.destructured ?: return null
        val artistId = if (kind == "artist") id else lidarr.getJson("api/v1/album/$id")?.let { jsonObject(it.encodeToByteArray())?.string("artistId") } ?: return null
        return lidarr.getJson("api/v1/artist/$artistId")?.let { jsonObject(it.encodeToByteArray()) }
    }

    /** A `PUT album/monitor` body as Lidarr reads it. */
    private class Monitor(val albumIds: List<Int>, val monitored: Boolean) {
        fun body() = buildJsonObject { putJsonArray("albumIds") { albumIds.forEach { add(JsonPrimitive(it)) } }; put("monitored", monitored) }.toString().encodeToByteArray()
    }

    /** A command as Lidarr reads it, with the albums or artist a search is for. */
    private class Command(val name: String, val raw: JsonObject, val albumIds: List<Int>, val artistId: Int?) {
        /** What a user may send: the search alone. */
        fun body() = buildJsonObject {
            put("name", name)
            if (albumIds.isNotEmpty()) putJsonArray("albumIds") { albumIds.forEach { add(JsonPrimitive(it)) } }
            artistId?.let { put("artistId", it) }
        }.toString().encodeToByteArray()
    }

    private fun parseMonitor(body: ByteArray): Monitor? {
        val o = jsonObject(body)?.takeUnless(::hasTwins) ?: return null
        val ids = ints(o.field("albumIds")) ?: return null
        // Lidarr only takes a JSON true or false, and no value means false.
        val monitored = when (val m = o.field("monitored")) {
            null -> false
            is JsonPrimitive -> if (m.isString) return null else m.contentOrNull?.toBooleanStrictOrNull() ?: return null
            else -> return null
        }
        return Monitor(ids, monitored)
    }

    private fun parseCommand(body: ByteArray): Command? {
        val o = jsonObject(body)?.takeUnless(::hasTwins) ?: return null
        val name = (o.field("name") as? JsonPrimitive)?.contentOrNull ?: return null
        val albumIds = o.field("albumIds")?.let { ints(it) ?: return null }.orEmpty()
        val artistId = o.field("artistId")?.let { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: return null }
        return Command(name, o, albumIds, artistId)
    }

    private fun JsonObject.field(key: String) = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun ints(element: JsonElement?): List<Int>? =
        (element as? JsonArray)?.map { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.contentOrNull?.toIntOrNull() ?: return null }

    /**
     * A refusal when [call] would put music into someone else's picks folder: an album always goes where Lidarr keeps its
     * real artist (whatever the request says), and a search downloads into it too. Null when it wouldn't.
     */
    private fun intoOthersPicks(call: ProxyCall, endpoint: String, user: String, monitor: Monitor?, command: Command?): ProxyAnswer? {
        val root = picksRoot ?: return null
        val artists: List<JsonObject> = when {
            call.method == "POST" && endpoint == "album" -> {
                val album = jsonObject(call.body) ?: return badBody()
                val foreignId = album.field("foreignAlbumId")?.let { (it as? JsonPrimitive)?.contentOrNull } ?: return badBody()
                val claimed = (album.field("artist") as? JsonObject)?.field("foreignArtistId")?.let { (it as? JsonPrimitive)?.contentOrNull }
                listOf(realArtist(foreignId, claimed) ?: return unsure())
            }
            monitor != null -> if (monitor.monitored) owners(monitor.albumIds.map { "album/$it" }) ?: return unsure() else return null
            command != null -> when (command.name.lowercase()) {
                "albumsearch" -> owners(command.albumIds.map { "album/$it" }) ?: return unsure()
                "artistsearch" -> owners(listOfNotNull(command.artistId?.let { "artist/$it" })) ?: return unsure()
                else -> return null
            }
            else -> return null
        }
        val own = picksFolder(user)
        val theirs = artists.firstOrNull { a -> a.string("path")?.let { isUnder(it, root) && (own == null || !isUnder(it, own)) } ?: false } ?: return null
        val name = theirs.string("artistName") ?: "That artist"
        return Upstream.error(
            409,
            "$name is in someone else's private weekly picks (albums they keep stay there), so this would land in their library. " +
                "An admin can move $name to a shared root folder in Lidarr.",
        )
    }

    /**
     * The artist Lidarr will file the album [foreignAlbumId] under, as Lidarr has it (with its path) when it has them:
     * what Lidarr itself knows, not what a request claims. An empty object for an artist it doesn't have yet; null when
     * it can't tell.
     *
     * An album Lidarr has already comes from its own database. Otherwise, a MusicBrainz id is looked up in Lidarr's
     * metadata. Ids of other metadata sources (Tubifarry's Deezer or Discogs ones) can't be looked up that way, so for
     * those the artist the request names ([claimed]) decides.
     */
    private fun realArtist(foreignAlbumId: String, claimed: String?): JsonObject? {
        val (status, known) = lidarr.get("api/v1/album", "foreignAlbumId=" + java.net.URLEncoder.encode(foreignAlbumId, Charsets.UTF_8))
        if (status != 200) return null
        val inLidarr = runCatching { json.parseToJsonElement(known).jsonArray.firstOrNull() as? JsonObject }.getOrNull()
        inLidarr?.string("artistId")?.let { id -> return owners(listOf("artist/$id"))?.firstOrNull() }
        val mbid = if (runCatching { java.util.UUID.fromString(foreignAlbumId) }.isSuccess) {
            val (found, body) = lidarr.get("api/v1/album/lookup", "term=" + java.net.URLEncoder.encode("lidarr:$foreignAlbumId", Charsets.UTF_8))
            if (found != 200) return null
            val album = runCatching { json.parseToJsonElement(body).jsonArray.firstOrNull() as? JsonObject }.getOrNull() ?: return null
            val artist = album["artist"] as? JsonObject ?: return null
            if (artist.string("path") != null) return artist
            artist.string("foreignArtistId") ?: return null
        } else claimed ?: return JsonObject(emptyMap())
        // Lidarr's artist list says for sure whether it has them, and where.
        val all = lidarr.getJson("api/v1/artist")?.let { runCatching { json.parseToJsonElement(it).jsonArray.mapNotNull { a -> a as? JsonObject } }.getOrNull() } ?: return null
        return all.firstOrNull { it.string("foreignArtistId") == mbid } ?: JsonObject(emptyMap())
    }

    /**
     * The artists the `album/{id}` and `artist/{id}` [endpoints] are about, leaving out what Lidarr doesn't have (it
     * does nothing for those). Null when it can't say for one of them: then nothing is let through.
     */
    private fun owners(endpoints: List<String>): List<JsonObject>? = endpoints.mapNotNull { endpoint ->
        val (kind, id) = Regex("^(album|artist)/(\\d+)$").matchEntire(endpoint)?.destructured ?: return null
        val artistId = if (kind == "artist") id else {
            val (status, body) = lidarr.get("api/v1/album/$id")
            if (status == 404) return@mapNotNull null
            if (status != 200) return null
            jsonObject(body.encodeToByteArray())?.string("artistId") ?: return null
        }
        val (status, body) = lidarr.get("api/v1/artist/$artistId")
        if (status == 404 && kind == "artist") return@mapNotNull null
        if (status != 200) return null
        jsonObject(body.encodeToByteArray())?.takeIf { it.string("path") != null } ?: return null
    }

    private fun unsure() = Upstream.error(502, "Lidarr couldn't say whose music that is; try again")

    /** A DELETE's query as a user may send it: whether to delete the files, never an import list exclusion. */
    private fun deleteQuery(raw: String?): String {
        val files = raw.orEmpty().split('&').any { part ->
            java.net.URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8).equals("deleteFiles", ignoreCase = true) &&
                java.net.URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8).equals("true", ignoreCase = true)
        }
        return "deleteFiles=$files&addImportListExclusion=false"
    }

    /** Lidarr's root folders as it lists them now, or null when it can't be asked. */
    private fun rootFolderList(): List<JsonObject>? = lidarr.getJson("api/v1/rootfolder")?.let { text ->
        runCatching { json.parseToJsonElement(text).jsonArray.mapNotNull { it as? JsonObject } }.getOrNull()
    }

    private fun rootFolders(fresh: Boolean = false): Set<String> {
        val now = System.currentTimeMillis()
        if (!fresh) roots?.takeIf { now - it.second < 10 * 60_000L }?.let { return it.first }
        val paths = lidarr.getJson("api/v1/rootfolder")?.let { text ->
            runCatching { json.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonObject.string("path")?.trimEnd('/') }.toSet() }.getOrNull()
        }.orEmpty()
        if (paths.isNotEmpty()) roots = paths to now
        return paths
    }

    /**
     * The releases in Lidarr's metadata with this artist and title (any edition), each with its kind ("Album", "EP",
     * "Live"…), the likeliest first: the very title ("X" before "X (Remixes)"), then a plain studio album, then the
     * year closest to the pick's. None when it doesn't exist; throws when the search fails. "&", "+" and "and" are the
     * same, and so are "The Beatles" and "Beatles".
     */
    fun findAlbum(pick: AiPick): List<AlbumMatch> {
        val term = java.net.URLEncoder.encode("${pick.artist} ${pick.album}", Charsets.UTF_8)
        val answer = lidarr.send(ProxyCall("GET", "api/v1/album/lookup", "term=$term", null, ByteArray(0)))
        if (answer.status != 200) throw IllegalStateException("Lidarr's album search answered ${answer.status}")
        val title = Recommendations.loose(pick.album)
        return json.parseToJsonElement(answer.body.decodeToString()).jsonArray.mapNotNull { it as? JsonObject }
            .filter { album ->
                Recommendations.looseTitle(album.string("title").orEmpty()) == Recommendations.looseTitle(pick.album) &&
                    Recommendations.loose((album["artist"] as? JsonObject)?.string("artistName").orEmpty()) == Recommendations.loose(pick.artist)
            }
            .sortedWith(compareBy<JsonObject>(
                { if (Recommendations.loose(it.string("title").orEmpty()) == title) 0 else 1 },
                { if (kinds(it) - "Studio" == setOf("Album")) 0 else 1 },
                { album -> pick.year?.let { year -> album.string("releaseDate")?.take(4)?.toIntOrNull()?.let { kotlin.math.abs(it - year) } } ?: Int.MAX_VALUE },
            ))
            .map { hit ->
                AlbumMatch(
                    AiPick(
                        (hit["artist"] as JsonObject).string("artistName") ?: pick.artist,
                        hit.string("title") ?: pick.album,
                        hit.string("releaseDate")?.take(4)?.toIntOrNull() ?: pick.year,
                        pick.why,
                    ),
                    kinds(hit),
                )
            }
    }

    /** An album's type and secondary types as Lidarr names them ("Album", "Live"…); secondary types come as names or as objects with one. */
    private fun kinds(album: JsonObject): Set<String> = buildSet {
        album.string("albumType")?.let(::add)
        (album["secondaryTypes"] as? JsonArray).orEmpty().forEach { type ->
            ((type as? JsonPrimitive)?.contentOrNull ?: (type as? JsonObject)?.string("name"))?.let(::add)
        }
    }

    private fun jsonObject(body: ByteArray) = runCatching { json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()

    /** Whether [path] is inside [folder]; never for paths with "." or ".." in them, which needn't stay where they say. */
    private fun isUnder(path: String, folder: String): Boolean {
        val inner = clean(path) ?: return false
        val outer = clean(folder) ?: return false
        return inner.startsWith("$outer/")
    }

    /** [path] without doubled or trailing slashes, or null when it has "." or ".." in it. */
    private fun clean(path: String): String? {
        val parts = path.trim().split('/').filter { it.isNotEmpty() }
        if (parts.any { it == "." || it == ".." }) return null
        return parts.joinToString("/", prefix = "/")
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun denied() = Upstream.error(403, "Only Navidrome admins can do that through the Tonearm server")

    private fun badRoot() = Upstream.error(400, "Requests have to go into one of Lidarr's root folders")

    private fun badBody() = Upstream.error(400, "Lidarr couldn't read that the same way")

    companion object {
        /**
         * A user's picks folder name: their Navidrome name when a path can hold it as it is, otherwise made safe and
         * followed by a short hash of the name, so no two users ever share one.
         */
        fun folderName(user: String): String {
            val safe = user.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").trim('.').ifEmpty { "_" }
            if (safe == user) return safe
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(user.encodeToByteArray()).take(4).joinToString("") { "%02x".format(it) }
            return "$safe-$hash"
        }

        /** What a user's artist request may not set itself: where it goes and its tags. */
        private val ARTIST_KEYS_SET_HERE = setOf("path", "folder", "rootfolderpath", "tags")

        val PATH = Regex("^api/v1/[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+)*$")
        private val READ = listOf(
            "system/status", "rootfolder", "qualityprofile", "metadataprofile", "tag",
            "artist", "artist/\\d+", "artist/lookup", "album", "album/\\d+", "album/lookup", "search",
            "queue", "queue/details", "wanted/missing", "wanted/cutoff", "command/\\d+", "track",
            "mediacover/(artist|album)/\\d+/[A-Za-z0-9_.-]+",
        ).map { Regex("^$it$") }
        private val COMMANDS = setOf("AlbumSearch", "ArtistSearch")
    }
}

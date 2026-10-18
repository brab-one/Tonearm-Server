package io.github.deadeyebarb.tonearm.server

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.BitSet

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

    /** [call] as it is; [timeout] is how long its answer may take (a release search across all indexers takes a while). */
    fun send(call: ProxyCall, extraQuery: List<Pair<String, String>> = emptyList(), body: ByteArray = call.body, timeout: Duration = TIMEOUT): ProxyAnswer {
        val request = request(call, extraQuery, body, timeout)
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

    /** A GET's answer, read by [parse] as it comes in so a long one is never held whole; null unless it was a 200 that could be read. */
    fun <T> read(path: String, query: String? = null, parse: (InputStream) -> T): T? = try {
        val response = http.send(request(ProxyCall("GET", path, query, null, ByteArray(0))), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { if (response.statusCode() == 200) parse(it) else null }
    } catch (e: Exception) {
        null
    }

    /**
     * A GET's answer: what [parse] made of a 200 as it came in, so a long one is never held whole, or else the answer as
     * it was (an error, say). One that can't be read the way [parse] expects, or doesn't come at all, is a 502.
     */
    fun <T> fetch(call: ProxyCall, extraQuery: List<Pair<String, String>> = emptyList(), parse: (InputStream) -> T): Fetched<T> = try {
        val response = http.send(request(call, extraQuery, ByteArray(0)), HttpResponse.BodyHandlers.ofInputStream())
        val type = response.headers().firstValue("Content-Type").orElse(null)
        response.body().use { body ->
            if (response.statusCode() == 200) Fetched(parse(body), ProxyAnswer(200, type, ByteArray(0)))
            else Fetched(null, ProxyAnswer(response.statusCode(), type, body.readAllBytes()))
        }
    } catch (e: IllegalArgumentException) {
        Fetched(null, error(502, "$name's answer wasn't what was asked for"))
    } catch (e: Exception) {
        Fetched(null, error(502, "Can't reach $name: ${e.message ?: e.javaClass.simpleName}"))
    }

    class Fetched<T>(val value: T?, val answer: ProxyAnswer)

    private fun request(call: ProxyCall, extraQuery: List<Pair<String, String>> = emptyList(), body: ByteArray = call.body, timeout: Duration = TIMEOUT): HttpRequest {
        val query = (keptQuery(call.rawQuery) + extraQuery.map { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, Charsets.UTF_8) })
            .joinToString("&")
        return HttpRequest.newBuilder(URI.create("$base/${call.path}" + if (query.isEmpty()) "" else "?$query"))
            .timeout(timeout)
            .method(call.method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
            .apply {
                keyHeader?.let { header(it, key) }
                header("Accept", "application/json, image/*;q=0.9, */*;q=0.5")
                if (body.isNotEmpty()) header("Content-Type", call.contentType ?: "application/json")
            }
            .build()
    }

    private fun keptQuery(raw: String?): List<String> = raw.orEmpty().split('&').filter { part ->
        part.isNotEmpty() && java.net.URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8).lowercase() !in DROPPED
    }

    companion object {
        /** The apps' Subsonic login and anything that could pass another key. */
        private val DROPPED = setOf("u", "t", "s", "p", "apikey", "v", "c", "f", "key")
        private val TIMEOUT: Duration = Duration.ofSeconds(90)

        fun error(status: Int, message: String) =
            ProxyAnswer(status, "application/json", JsonObject(mapOf("error" to JsonPrimitive(message))).toString().encodeToByteArray())
    }
}

/** A user's own folder for their weekly picks, as Lidarr sees it, and whether Lidarr has it as a root folder yet. */
data class PicksFolder(val path: String, val ready: Boolean, val problem: String? = null)

/**
 * Lidarr for everyone on Navidrome. Admins get all of it (Brainarr, weekly picks, removing music).
 * Everyone else can look things up, see what they asked for downloading or wanted, and request artists and albums,
 * which land in one of Lidarr's own root folders with no tags; nothing gets deleted or reconfigured.
 * Who asked for what is kept in [requests]: admins see it on what's downloading and wanted.
 * With [picksRoot] (PICKS_FOLDER), each user has a folder of their own in it for their weekly picks, which
 * only they can add to and delete from, and only they (and admins) see in Lidarr (it has its own Navidrome library too).
 * [youtube] is the YouTube fallback, which reads Lidarr and grabs from it on its own.
 */
class LidarrProxy(
    private val lidarr: Upstream,
    private val requests: Requests,
    private val requestsForEveryone: Boolean,
    private val picksRoot: String? = null,
    val youtube: YouTubeFallback? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile private var roots: Pair<Set<String>, Long>? = null
    @Volatile private var homes: Homes? = null

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
        if (call.method == "GET") return read(call, endpoint, admin, user)
        val body = if (admin) monitor?.body() ?: call.body else when (call.method) {
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
        if (call.method == "POST" && endpoint == "album") wantedAgain(body, user)?.let { return it }
        val answer = lidarr.send(call, body = body)
        if (answer.status in 200..299) note(user, asked(call.method, endpoint, body, answer.body, monitor, command))
        return answer
    }

    /**
     * A look into Lidarr. Everyone but admins sees only what they asked for (or what's in their own picks folder) in the
     * queue and the wanted lists, and nothing that's in someone else's picks folder. Admins see everything, and on what's
     * downloading or wanted who asked for it ("requestedBy").
     */
    private fun read(call: ProxyCall, endpoint: String, admin: Boolean, user: String): ProxyAnswer {
        if (!admin && READ.none { it.matches(endpoint) }) return denied()
        if (endpoint in PAGED || endpoint == "queue/details") {
            val album = endpoint.startsWith("wanted/")
            val askers = Askers(if (admin) requests.everyone() else mapOf(user to requests.of(user)))
            fun who(record: JsonObject) = askers.of(record, album)
            if (admin) {
                val shown = { record: JsonObject ->
                    val by = who(record)
                    if (by.isEmpty()) record else JsonObject(record + ("requestedBy" to JsonArray(by.map(::JsonPrimitive))))
                }
                return if (endpoint == "queue/details") list(call, shown = shown) else paged(call, all = true, shown = shown)
            }
            val theirs = { record: JsonObject -> who(record).isNotEmpty() || inOwnFolder(record, user) }
            return if (endpoint == "queue/details") list(call, theirs) else paged(call, all = false, keep = theirs)
        }
        if (admin || picksRoot == null) return lidarr.send(call)
        return when (endpoint) {
            "artist" -> list(call, { !inOthersPicks(it, user, artist = true) })
            "album", "track" -> if (homes() == null) unsure() else list(call, { !inOthersPicks(it, user, artist = false) })
            "artist/lookup", "album/lookup", "search" -> list(call, { !foundInOthersPicks(it, user) })
            else -> Regex("^(artist|album)/\\d+$").matchEntire(endpoint)?.let { match ->
                val answer = lidarr.send(call)
                if (answer.status != 200) return answer
                val item = jsonObject(answer.body) ?: return unsure()
                if (inOthersPicks(item, user, artist = match.groupValues[1] == "artist")) Upstream.error(404, "Lidarr hasn't got that") else answer
            } ?: lidarr.send(call)
        }
    }

    /**
     * Who asked for a queue item's or ([album]) a wanted album's album or artist, by Lidarr's ids and the metadata's (when
     * its album and artist come with it).
     */
    private fun Askers.of(record: JsonObject, album: Boolean): List<String> {
        val albumOf = if (album) record else record["album"] as? JsonObject
        val artist = record["artist"] as? JsonObject
        return of(
            albumId = if (album) record.int("id") else record.int("albumId") ?: albumOf?.int("id"),
            foreignAlbumId = albumOf?.string("foreignAlbumId"),
            artistId = record.int("artistId") ?: artist?.int("id"),
            foreignArtistId = artist?.string("foreignArtistId"),
        )
    }

    /**
     * A page of Lidarr's queue or wanted list as the app asked for it (page, pageSize, sorting), with only the records
     * [keep] lets through, each as [shown] makes it, and totalRecords counting just those. Lidarr is read [CHUNK] records
     * at a time, a record at a time as they come in, and only the page asked for is kept, so a long list is never held
     * whole. With [all], nothing is left out: Lidarr's own count holds, and only the records up to the end of the page are read.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun paged(call: ProxyCall, all: Boolean, keep: (JsonObject) -> Boolean = { true }, shown: (JsonObject) -> JsonObject = { it }): ProxyAnswer {
        val query = call.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }
        fun name(part: String) = java.net.URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8).lowercase()
        fun number(key: String) = query.firstOrNull { name(it) == key }?.substringAfter('=', "")?.toIntOrNull()
        val page = (number("page") ?: 1).coerceAtLeast(1)
        val size = (number("pagesize") ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
        val from = (page - 1).toLong() * size
        val rest = call.copy(rawQuery = query.filterNot { name(it) == "page" || name(it) == "pagesize" }.joinToString("&"))
        // With everything kept, the page starts in the chunk that holds its first record.
        var chunk = if (all) (from / CHUNK).toInt() + 1 else 1
        var index = if (all) (chunk - 1).toLong() * CHUNK else 0L
        var first: LidarrPage? = null
        val kept = ArrayList<String>()
        while (true) {
            val reader = PageReader { record ->
                if (keep(record)) {
                    if (index >= from && index < from + size) kept += shown(record).toString()
                    index++
                }
            }
            val fetched = lidarr.fetch(rest, listOf("page" to "$chunk", "pageSize" to "$CHUNK")) { json.decodeFromStream(reader, it) }
            val part = fetched.value ?: return fetched.answer
            if (first == null) first = part
            // Fewer than asked for is the end; more is a Lidarr that sent everything at once (one that doesn't page).
            val end = part.records != CHUNK || chunk.toLong() * CHUNK >= part.totalRecords || chunk >= MAX_CHUNKS
            if (end || all && index >= from + size) break
            chunk++
        }
        val head = buildJsonObject {
            put("page", page)
            put("pageSize", size)
            first.sortKey?.let { put("sortKey", it) }
            first.sortDirection?.let { put("sortDirection", it) }
            put("totalRecords", if (all) first.totalRecords else index)
        }.toString()
        return ProxyAnswer(200, "application/json", (head.dropLast(1) + ",\"records\":[" + kept.joinToString(",") + "]}").encodeToByteArray())
    }

    /** A page of Lidarr's queue or wanted list as it answered, with how many [records] it had. */
    internal class LidarrPage(val sortKey: String?, val sortDirection: String?, val totalRecords: Long, val records: Int)

    /** Reads a page of Lidarr's queue or wanted list as it comes in, handing each record to [each] and letting go of it. */
    internal class PageReader(private val each: (JsonObject) -> Unit) : DeserializationStrategy<LidarrPage> {
        override val descriptor = buildClassSerialDescriptor("LidarrPage") {
            element<JsonElement>("sortKey")
            element<JsonElement>("sortDirection")
            element<Long>("totalRecords")
            element("records", ListSerializer(JsonObject.serializer()).descriptor)
        }

        override fun deserialize(decoder: Decoder): LidarrPage = decoder.decodeStructure(descriptor) {
            var sortKey: String? = null
            var sortDirection: String? = null
            var total = 0L
            var records = 0
            while (true) {
                when (val i = decodeElementIndex(descriptor)) {
                    0 -> sortKey = (decodeSerializableElement(descriptor, i, JsonElement.serializer()) as? JsonPrimitive)?.contentOrNull
                    1 -> sortDirection = (decodeSerializableElement(descriptor, i, JsonElement.serializer()) as? JsonPrimitive)?.contentOrNull
                    2 -> total = decodeLongElement(descriptor, i)
                    3 -> records = decodeSerializableElement(descriptor, i, EachRecord(each))
                    CompositeDecoder.DECODE_DONE -> break
                    else -> throw SerializationException("Unexpected element $i")
                }
            }
            LidarrPage(sortKey, sortDirection, total, records)
        }
    }

    /** A list of records, each handed to [each] as it's read; what comes of it is how many there were. */
    private class EachRecord(private val each: (JsonObject) -> Unit) : DeserializationStrategy<Int> {
        override val descriptor = ListSerializer(JsonObject.serializer()).descriptor

        override fun deserialize(decoder: Decoder): Int = decoder.decodeStructure(descriptor) {
            var count = 0
            while (true) {
                val i = decodeElementIndex(descriptor)
                if (i == CompositeDecoder.DECODE_DONE) break
                each(decodeSerializableElement(descriptor, i, JsonObject.serializer()))
                count++
            }
            count
        }
    }

    /** Lidarr's list answer with only what [keep] lets through, each as [shown] makes it, read and written an item at a time. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun list(call: ProxyCall, keep: (JsonObject) -> Boolean = { true }, shown: (JsonObject) -> JsonObject = { it }): ProxyAnswer {
        val fetched = lidarr.fetch(call) { input ->
            val out = java.io.ByteArrayOutputStream()
            out.write('['.code)
            var first = true
            for (item in json.decodeToSequence(input, JsonObject.serializer(), DecodeSequenceMode.ARRAY_WRAPPED)) {
                if (!keep(item)) continue
                if (!first) out.write(','.code)
                first = false
                out.write(shown(item).toString().encodeToByteArray())
            }
            out.write(']'.code)
            out.toByteArray()
        }
        return fetched.value?.let { ProxyAnswer(200, "application/json", it) } ?: fetched.answer
    }

    /** Where Lidarr keeps its artists, as far as picks folders go: every artist's id, and the path of those in one. */
    private class Homes(val at: Long, val known: BitSet, val picks: Map<Int, String>)

    /**
     * Where Lidarr's artists live as of the last minute, or ([fresh]) as of now unless that was a moment ago; read as
     * Lidarr's list of artists comes in, never whole. What was known before when Lidarr can't be asked, null when nothing.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun homes(fresh: Boolean = false): Homes? {
        val known = homes
        val now = System.currentTimeMillis()
        if (known != null && now - known.at < if (fresh) HOMES_AGAIN_MS else HOMES_MS) return known
        val root = picksRoot ?: return null
        val artists = lidarr.read("api/v1/artist") { json.decodeFromStream(ARTIST_HOMES, it) } ?: return known
        val ids = BitSet().apply { artists.forEach { if (it.id > 0) set(it.id) } }
        val picks = artists.filter { a -> a.path?.let { isUnder(it, root) } == true }.associate { it.id to it.path!! }
        return Homes(now, ids, picks).also { homes = it }
    }

    /**
     * The picks folder path artist [id] lives in, "" when they're elsewhere, or null when Lidarr doesn't list them (asked
     * again first, for one added a moment ago) or can't be asked.
     */
    private fun homeOf(id: Int): String? {
        val known = homes()?.takeIf { it.known[id] } ?: homes(fresh = true)?.takeIf { it.known[id] } ?: return null
        return known.picks[id] ?: ""
    }

    /**
     * Whether [item] (an [artist], or an album, track or queue item of one) lives in a picks folder other than [user]'s.
     * What can't be placed (an artist Lidarr doesn't list) counts as that: nothing is shown that might be someone's picks.
     */
    private fun inOthersPicks(item: JsonObject, user: String, artist: Boolean): Boolean {
        if (picksRoot == null) return false
        val path = (if (artist) item else item["artist"] as? JsonObject)?.string("path")
            ?: ((if (artist) item.int("id") else item.int("artistId"))?.let(::homeOf) ?: return true).ifEmpty { return false }
        return inOthersFolder(path, user)
    }

    /**
     * Whether a lookup or search result (an artist, an album, or one of either) is in a picks folder other than [user]'s,
     * by the path Lidarr gives the artists it has. What isn't in Lidarr yet has no path, and shows.
     */
    private fun foundInOthersPicks(item: JsonObject, user: String): Boolean {
        val artists = listOfNotNull(item, item["artist"] as? JsonObject, (item["album"] as? JsonObject)?.get("artist") as? JsonObject)
        return artists.mapNotNull { it.string("path") }.any { inOthersFolder(it, user) }
    }

    /** Whether [path] is in a picks folder other than [user]'s; one with "." or ".." in it might be, so it counts as that. */
    private fun inOthersFolder(path: String, user: String): Boolean {
        val root = picksRoot ?: return false
        if (clean(path) == null) return true
        val own = picksFolder(user)
        return isUnder(path, root) && (own == null || !isUnder(path, own))
    }

    /** Whether a queue item or wanted album is in [user]'s own picks folder. */
    private fun inOwnFolder(record: JsonObject, user: String): Boolean {
        val own = picksFolder(user) ?: return false
        val path = (record["artist"] as? JsonObject)?.string("path") ?: record.int("artistId")?.let { homes()?.picks?.get(it) } ?: return false
        return isUnder(path, own)
    }

    /**
     * What a request that went through asked for: an artist (unless it's added with nothing wanted) with the albums it
     * names, an album, or albums or an artist to monitor or search for.
     */
    private fun asked(method: String, endpoint: String, sent: ByteArray, answer: ByteArray, monitor: Monitor?, command: Command?): List<Requested> {
        val now = System.currentTimeMillis()
        return when {
            method == "POST" && endpoint == "artist" -> {
                val artist = jsonObject(sent) ?: return emptyList()
                val added = jsonObject(answer)
                val options = artist.field("addOptions") as? JsonObject
                val whole = options?.text("monitor")?.equals("none", ignoreCase = true) != true
                val albums = (options?.field("albumsToMonitor") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                listOfNotNull(Requested(now, artistId = added?.int("id"), foreignArtistId = added?.string("foreignArtistId") ?: artist.text("foreignArtistId")).takeIf { whole }) +
                    albums.map { Requested(now, foreignAlbumId = it) }
            }
            method == "POST" && endpoint == "album" -> {
                val added = jsonObject(answer)
                listOf(Requested(now, albumId = added?.int("id"), foreignAlbumId = added?.string("foreignAlbumId") ?: jsonObject(sent)?.text("foreignAlbumId")))
            }
            monitor != null -> if (monitor.monitored) monitor.albumIds.map { Requested(now, albumId = it) } else emptyList()
            command != null && command.name.equals("AlbumSearch", ignoreCase = true) -> command.albumIds.map { Requested(now, albumId = it) }
            command != null && command.name.equals("ArtistSearch", ignoreCase = true) -> listOfNotNull(command.artistId?.let { Requested(now, artistId = it) })
            else -> emptyList()
        }.filter { it.isAlbum || it.artistId != null || it.foreignArtistId != null }
    }

    /** Notes [asked] as [user]'s request. When that can't be saved the request went through all the same, and the log says so. */
    private fun note(user: String, asked: List<Requested>) {
        if (user.isEmpty() || asked.isEmpty()) return
        // What was added may be new to Lidarr, in someone's picks folder too.
        homes = null
        try {
            requests.add(user, asked)
        } catch (e: IOException) {
            System.err.println("Couldn't note what $user asked Lidarr for: ${e.message}")
        }
    }

    /**
     * An album request for an album Lidarr has already (someone else asked for it, say), which Lidarr itself would turn
     * down: it's wanted from now on (and looked for, when the request says so), and [user]'s request too. Null when
     * Lidarr hasn't got it or can't say.
     */
    private fun wantedAgain(body: ByteArray, user: String): ProxyAnswer? {
        val request = jsonObject(body) ?: return null
        val foreignId = request.text("foreignAlbumId") ?: return null
        val (status, text) = lidarr.get("api/v1/album", "foreignAlbumId=" + java.net.URLEncoder.encode(foreignId, Charsets.UTF_8))
        if (status != 200) return null
        val album = parsed { json.parseToJsonElement(text).jsonArray.firstOrNull() as? JsonObject } ?: return null
        val id = album.int("id") ?: return null
        if (album.string("monitored") != "true") {
            val monitored = lidarr.send(ProxyCall("PUT", "api/v1/album/monitor", null, "application/json", Monitor(listOf(id), true).body()))
            if (monitored.status !in 200..299) return monitored
            val search = ((request.field("addOptions") as? JsonObject)?.field("searchForNewAlbum") as? JsonPrimitive)?.booleanOrNull == true
            if (search) lidarr.send(ProxyCall("POST", "api/v1/command", null, "application/json", Command("AlbumSearch", JsonObject(emptyMap()), listOf(id), null).body()))
        }
        note(user, listOf(Requested(System.currentTimeMillis(), albumId = id, foreignAlbumId = album.string("foreignAlbumId") ?: foreignId)))
        return ProxyAnswer(200, "application/json", JsonObject(album + ("monitored" to JsonPrimitive(true))).toString().encodeToByteArray())
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
            // Lidarr turns down an artist it has already, which doesn't say why to someone who can't see them.
            call.method == "POST" && endpoint == "artist" -> listOf(jsonObject(call.body)?.text("foreignArtistId")?.let(::artistHome) ?: return null)
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
        // An album it hasn't got is [] (or a 404 from some); an error or no answer at all can't tell.
        if (status != 200 && status != 404) return null
        val inLidarr = if (status == 404) null else parsed { json.parseToJsonElement(known).jsonArray.firstOrNull() as? JsonObject }
        inLidarr?.string("artistId")?.let { id -> return owners(listOf("artist/$id"))?.firstOrNull() }
        val mbid = if (UUID.matches(foreignAlbumId)) {
            val (found, body) = lidarr.get("api/v1/album/lookup", "term=" + java.net.URLEncoder.encode("lidarr:$foreignAlbumId", Charsets.UTF_8))
            if (found != 200) return null
            val album = parsed { json.parseToJsonElement(body).jsonArray.firstOrNull() as? JsonObject } ?: return null
            val artist = album["artist"] as? JsonObject ?: return null
            if (artist.string("path") != null) return artist
            artist.string("foreignArtistId") ?: return null
        } else claimed ?: return JsonObject(emptyMap())
        return artistHome(mbid)
    }

    /**
     * The artist Lidarr has under the metadata id [foreignArtistId], with their path; an empty object when it hasn't got
     * them, null when it can't tell. Lidarr says for sure whether it has them, and where: asked by MusicBrainz id (old ids
     * of the artist count too), or else by going through its list of artists as it comes in, which is long and never read whole.
     */
    private fun artistHome(foreignArtistId: String): JsonObject? {
        val byId = UUID.matches(foreignArtistId)
        val homes = lidarr.read("api/v1/artist", if (byId) "mbId=$foreignArtistId" else null) { json.decodeFromStream(ARTIST_HOMES, it) } ?: return null
        val home = homes.firstOrNull { it.foreignArtistId == foreignArtistId } ?: homes.singleOrNull()?.takeIf { byId } ?: return JsonObject(emptyMap())
        return buildJsonObject {
            home.artistName?.let { put("artistName", it) }
            home.path?.let { put("path", it) }
        }
    }

    /** The little of a Lidarr artist that says who it is and where its music goes. */
    @Serializable
    private class ArtistHome(val id: Int = 0, val foreignArtistId: String? = null, val artistName: String? = null, val path: String? = null)

    /** What [parse] makes of an answer, or null when it isn't what was expected (running out of memory isn't that). */
    private fun <T> parsed(parse: () -> T): T? = try {
        parse()
    } catch (e: IllegalArgumentException) {
        null
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

    private fun jsonObject(body: ByteArray) = parsed { json.parseToJsonElement(body.decodeToString()).jsonObject }

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

    /** A Lidarr id (they start at 1). */
    private fun JsonObject.int(key: String) = string(key)?.toIntOrNull()?.takeIf { it > 0 }

    /** A text field of a request, read the way Lidarr reads keys (in any case). */
    private fun JsonObject.text(key: String) = (field(key) as? JsonPrimitive)?.contentOrNull

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

        private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val ARTIST_HOMES = ListSerializer(ArtistHome.serializer())

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

        /** The queue and the wanted lists, which come a page at a time. */
        private val PAGED = setOf("queue", "wanted/missing", "wanted/cutoff")
        /** Records asked of Lidarr at once when going through a paged list, and at most how many times. */
        private const val CHUNK = 1_000
        private const val MAX_CHUNKS = 100
        /** Lidarr's page size when none is asked for, and the most records a page has here. */
        private const val DEFAULT_PAGE = 10
        private const val MAX_PAGE = 2_000
        /** How long where the artists live is taken as known, and how soon it's asked again for an artist it didn't list. */
        private const val HOMES_MS = 60_000L
        private const val HOMES_AGAIN_MS = 2_000L
    }
}

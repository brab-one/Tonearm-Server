package io.github.deadeyebarb.tonearm.server

import java.io.File
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The Tonearm server: Tonearm Connect for every user of a Navidrome server, Lidarr without handing out its key,
 * listening history, and picks. Settings come from the environment: NAVIDROME_URL (required), BASE_PATH (default
 * /connect-tonearm), PORT (8790), DATA_DIR (/data); optional LIDARR_URL + LIDARR_API_KEY (+ LIDARR_REQUESTS =
 * all | admins), and an AI for album suggestions: AI_PROVIDER (ollama, openai or claude) with AI_URL, AI_API_KEY
 * and AI_MODEL, or just OLLAMA_URL (+ OLLAMA_MODEL). Discovery picks and similar artists come from Deezer's public
 * API (DISCOVERY=off turns that off). MALOJA_URL (+ MALOJA_API_KEY, MALOJA_USERS) only brings a Maloja's
 * history over, once.
 */
fun main() {
    val navidrome = env("NAVIDROME_URL") ?: run {
        System.err.println("Set NAVIDROME_URL to Navidrome's address as this container reaches it, e.g. http://192.168.1.11:4533")
        exitProcess(2)
    }
    val basePath = System.getenv("BASE_PATH")?.takeIf { it.isNotBlank() } ?: "/connect-tonearm"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8790
    val dataDir = File(System.getenv("DATA_DIR")?.takeIf { it.isNotBlank() } ?: "/data")
    val version = TonearmServer::class.java.`package`?.implementationVersion ?: "dev"
    val lidarr = env("LIDARR_URL")?.let { url ->
        val key = env("LIDARR_API_KEY") ?: run {
            System.err.println("LIDARR_URL is set but LIDARR_API_KEY isn't (Lidarr → Settings → General → API Key)")
            exitProcess(2)
        }
        LidarrProxy(Upstream("Lidarr", url, "X-Api-Key", key), requestsForEveryone = env("LIDARR_REQUESTS")?.lowercase() != "admins")
    }
    val ai = try {
        Ai.fromEnv(::env)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    val history = History(dataDir)
    val recommendations = ai?.let { Recommendations(it, navidrome, dataDir, lidarr?.let { proxy -> AlbumCheck(proxy::findAlbum) }, history) }
    val discovery = if (env("DISCOVERY")?.lowercase() == "off") null else Discovery(navidrome, dataDir, history = history)
    val malojaImport = env("MALOJA_URL")?.let { url -> malojaImport(url, env("MALOJA_API_KEY"), env("MALOJA_USERS"), history) }
    TonearmServer(port, basePath, NavidromeAuth(navidrome), dataDir, version, lidarr, recommendations, discovery, history, malojaImport).start()
    println("Tonearm server $version on port $port under ${"/" + basePath.trim('/')}, checking logins with Navidrome at $navidrome, data in $dataDir")
    println("Lidarr: " + (env("LIDARR_URL")?.let { "$it, " + if (lidarr!!.available(false)) "requests for everyone" else "admins only" } ?: "not set up"))
    println("AI picks and search: " + (ai?.let { "${it.name.takeUnless { n -> n == "The AI" } ?: "OpenAI-style API"} with ${it.model}" } ?: "not set up (AI_PROVIDER)"))
    println("Discovery and similar artists: " + if (discovery != null) "from Deezer" else "off")
    if (malojaImport != null) println("Maloja: its history comes over for the first of ${env("MALOJA_USERS") ?: "Navidrome's admins"} to use the apps, unless it did before")
}

/**
 * Brings a Maloja's scrobbles into the history of the first user who signs in that it's meant for (MALOJA_USERS,
 * or any Navidrome admin), once; in the background, so that request isn't held up.
 */
private fun malojaImport(url: String, key: String?, users: String?, history: History): (String, Boolean) -> Unit {
    val names = users?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    val started = AtomicBoolean(false)
    val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    return { user, admin ->
        if ((if (names.isEmpty()) admin else user.lowercase() in names) && started.compareAndSet(false, true)) {
            Thread({
                try {
                    history.importMaloja(user, url, key, http)?.let { println("Brought $it plays over from Maloja into $user's history; MALOJA_URL can go now") }
                } catch (e: Exception) {
                    System.err.println("Couldn't bring Maloja's history over (tries again on the next start): ${e.message}")
                }
            }, "maloja-import").apply { isDaemon = true }.start()
        }
    }
}

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

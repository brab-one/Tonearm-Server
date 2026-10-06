package io.github.deadeyebarb.tonearm.server

import java.io.File
import kotlin.system.exitProcess

/**
 * The Tonearm server: Tonearm Connect for every user of a Navidrome server, and Lidarr and Maloja without
 * handing out their keys. Settings come from the environment: NAVIDROME_URL (required), BASE_PATH (default
 * /connect-tonearm), PORT (8790), DATA_DIR (/data); optional LIDARR_URL + LIDARR_API_KEY (+ LIDARR_REQUESTS
 * = all | admins), MALOJA_URL + MALOJA_API_KEY (+ MALOJA_USERS, else Navidrome admins), and OLLAMA_URL
 * (+ OLLAMA_MODEL, default qwen2.5) for album suggestions.
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
    val maloja = env("MALOJA_URL")?.let { url ->
        val key = env("MALOJA_API_KEY") ?: run {
            System.err.println("MALOJA_URL is set but MALOJA_API_KEY isn't (Maloja → Settings → API Keys)")
            exitProcess(2)
        }
        val users = env("MALOJA_USERS")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        MalojaProxy(Upstream("Maloja", url, null, key), key, users)
    }
    val recommendations = env("OLLAMA_URL")?.let { url ->
        Recommendations(url, env("OLLAMA_MODEL") ?: "qwen2.5", navidrome, dataDir, lidarr?.let { proxy -> AlbumCheck(proxy::findAlbum) })
    }
    TonearmServer(port, basePath, NavidromeAuth(navidrome), dataDir, version, lidarr, maloja, recommendations).start()
    println("Tonearm server $version on port $port under ${"/" + basePath.trim('/')}, checking logins with Navidrome at $navidrome, data in $dataDir")
    println("Lidarr: " + (env("LIDARR_URL")?.let { "$it, " + if (lidarr!!.available(false)) "requests for everyone" else "admins only" } ?: "not set up"))
    println("Maloja: " + (env("MALOJA_URL")?.let { "$it for " + (env("MALOJA_USERS") ?: "Navidrome admins") } ?: "not set up"))
    println("Recommendations: " + (env("OLLAMA_URL")?.let { "Ollama at $it with " + (env("OLLAMA_MODEL") ?: "qwen2.5") } ?: "not set up"))
}

private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

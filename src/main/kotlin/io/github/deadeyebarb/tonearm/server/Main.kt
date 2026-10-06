package io.github.deadeyebarb.tonearm.server

import java.io.File
import kotlin.system.exitProcess

/**
 * The Tonearm server: Tonearm Connect for every user of a Navidrome server. Settings come from the
 * environment: NAVIDROME_URL (required), BASE_PATH (default /connect-tonearm), PORT (8790), DATA_DIR (/data).
 */
fun main() {
    val navidrome = System.getenv("NAVIDROME_URL")?.takeIf { it.isNotBlank() } ?: run {
        System.err.println("Set NAVIDROME_URL to Navidrome's address as this container reaches it, e.g. http://192.168.1.11:4533")
        exitProcess(2)
    }
    val basePath = System.getenv("BASE_PATH")?.takeIf { it.isNotBlank() } ?: "/connect-tonearm"
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8790
    val dataDir = File(System.getenv("DATA_DIR")?.takeIf { it.isNotBlank() } ?: "/data")
    val version = TonearmServer::class.java.`package`?.implementationVersion ?: "dev"
    TonearmServer(port, basePath, NavidromeAuth(navidrome), dataDir, version).start()
    println("Tonearm server $version on port $port under ${"/" + basePath.trim('/')}, checking logins with Navidrome at $navidrome, data in $dataDir")
}

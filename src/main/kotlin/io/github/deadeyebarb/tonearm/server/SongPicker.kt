package io.github.deadeyebarb.tonearm.server

import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/**
 * Where some of a list's songs come from: [candidates], best first, making up [share] of the list. The first [lead] it
 * gets go at the top of the list; [inOrder] takes them as they come instead of drawing them.
 */
class Part(val share: Double, val candidates: List<LibrarySong>, val lead: Int = 0, val inOrder: Boolean = false)

/** What a list leaves out and leans away from, for one user right now. */
data class Rules(
    /** Song ids: what's queued already, the seed, yesterday's mix. */
    val excludeIds: Set<String> = emptySet(),
    /** [SongKey]s: disliked songs, and what was played lately. */
    val avoidKeys: Set<String> = emptySet(),
    /** [Recommendations.loose] artists said no to. */
    val dismissedArtists: Set<String> = emptySet(),
    /** Albums said no to: loose artist + "|" + [Recommendations.looseTitle]. */
    val dismissedAlbums: Set<String> = emptySet(),
    /** Disliked songs per loose artist: each makes the artist's other songs a little less likely. */
    val dislikedArtistCount: Map<String, Int> = emptyMap(),
) {
    fun allows(song: LibrarySong): Boolean = song.id !in excludeIds && song.key !in avoidKeys && song.artistKey !in dismissedArtists &&
        song.album?.let { song.artistKey + "|" + Recommendations.looseTitle(it) } !in dismissedAlbums
}

/**
 * The one picker behind radio, mixes from a sentence and daily mixes. Pure: the same parts, rules and seed give the
 * same list.
 */
object SongPicker {
    data class Picked(val songs: List<LibrarySong>, val more: Boolean)

    /**
     * [count] songs from [parts]: what [rules] leave out and songs already in an earlier part go first, then each part
     * fills its share by draws that favour its first candidates; a part that runs short hands its places to the others.
     * At most [perArtist] songs per artist, and no artist twice within [gap] places (counting [before], the artists just
     * ahead of the list) when there's a choice. [Picked.more] says whether anything is left for another list.
     */
    fun pick(parts: List<Part>, count: Int, rules: Rules, random: Random, perArtist: Int = 2, gap: Int = 3, before: List<String> = emptyList()): Picked {
        val ids = HashSet<String>()
        val keys = HashSet<String>()
        val pools = parts.map { part ->
            part.candidates.filter { song -> rules.allows(song) && song.id !in ids && song.key !in keys && ids.add(song.id) && keys.add(song.key) }.toMutableList()
        }
        // Earlier candidates weigh more; each disliked song of an artist makes the artist's others less likely.
        val weights = pools.map { pool ->
            pool.mapIndexed { i, song -> (1.0 - i.toDouble() / (pool.size + 5)).pow(2) * 0.75.pow(rules.dislikedArtistCount[song.artistKey] ?: 0) }.toMutableList()
        }
        val perArtistCount = HashMap<String, Int>()
        val picked = parts.map { mutableListOf<LibrarySong>() }

        fun draw(p: Int): Boolean {
            val pool = pools[p]
            val fits = pool.indices.filter { (perArtistCount[pool[it].artistKey] ?: 0) < perArtist }
            if (fits.isEmpty()) return false
            val chosen = if (parts[p].inOrder) fits.first() else {
                var left = random.nextDouble() * fits.sumOf { weights[p][it] }
                fits.firstOrNull { i -> left -= weights[p][i]; left < 0 } ?: fits.last()
            }
            val song = pool.removeAt(chosen)
            weights[p].removeAt(chosen)
            perArtistCount.merge(song.artistKey, 1, Int::plus)
            picked[p] += song
            return true
        }

        var short = 0
        quotas(parts.map { it.share }, count).forEachIndexed { p, quota -> repeat(quota) { if (!draw(p)) short++ } }
        // What a part couldn't fill goes to the others, a song each in turn.
        while (short > 0) {
            var drew = false
            for (p in parts.indices) if (short > 0 && draw(p)) {
                short--
                drew = true
            }
            if (!drew) break
        }
        val lead = parts.indices.flatMap { p -> picked[p].take(parts[p].lead) }
        val rest = interleave(parts.indices.map { p -> picked[p].drop(parts[p].lead) })
        val first = spread(lead, gap, before)
        return Picked(first + spread(rest, gap, before + first.map { it.artistKey }), pools.any { it.isNotEmpty() })
    }

    /** [count] places shared out by [shares] (largest remainders get the places left over). */
    fun quotas(shares: List<Double>, count: Int): List<Int> {
        val total = shares.sum().takeIf { it > 0 } ?: return shares.map { 0 }
        val exact = shares.map { it / total * count }
        val quotas = exact.map { floor(it).toInt() }.toMutableList()
        exact.indices.sortedByDescending { exact[it] - quotas[it] }.take(count - quotas.sum()).forEach { quotas[it]++ }
        return quotas
    }

    /** The lists taking turns, a song each. */
    fun interleave(lists: List<List<LibrarySong>>): List<LibrarySong> = buildList {
        for (i in 0 until (lists.maxOfOrNull { it.size } ?: 0)) lists.forEach { list -> list.getOrNull(i)?.let(::add) }
    }

    /**
     * [songs] in their order, except that a song whose artist is among the last [gap] waits when another one fits, and
     * the artist with the most songs left goes first once they'd no longer fit apart otherwise.
     */
    fun spread(songs: List<LibrarySong>, gap: Int, before: List<String> = emptyList()): List<LibrarySong> {
        val left = songs.toMutableList()
        val placed = before.toMutableList()
        val out = ArrayList<LibrarySong>(songs.size)
        while (left.isNotEmpty()) {
            val recent = placed.takeLast(gap)
            val fits = left.indices.filter { left[it].artistKey !in recent }
            val counts = left.groupingBy { it.artistKey }.eachCount()
            val most = counts.values.max()
            // The fewest places the busiest artists' songs need from here on.
            val needed = (most - 1) * (gap + 1) + counts.values.count { it == most }
            val urgent = if (most > 1 && left.size <= needed) fits.firstOrNull { counts[left[it].artistKey] == most } else null
            val i = urgent ?: fits.firstOrNull() ?: 0
            val song = left.removeAt(i)
            out += song
            placed += song.artistKey
        }
        return out
    }
}

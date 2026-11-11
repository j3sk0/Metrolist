/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kidj

import kotlin.math.abs
import kotlin.math.min

data class Wish(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    /** Guests who asked for the song; their wish also counts as a like. */
    val guests: MutableSet<String> = linkedSetOf(),
    /** Likes minus dislikes of all guests. */
    val votes: Int = 0,
)

enum class WishResult { ADDED, VOTED, ALREADY_VOTED, LIMIT_REACHED }

/** Pure DJ decision logic: guest wishes, learned floor reaction per artist, rescue rule. */
class DjBrain(
    private val maxOpenPerGuest: Int = 3,
) {
    private val wishes = LinkedHashMap<String, Wish>()
    private val reaction = HashMap<String, Double>()

    /** Like (+1) or dislike (-1) per guest and song, for wishes and queue items alike. */
    private val ratings = HashMap<String, HashMap<String, Int>>()

    @Synchronized
    fun addWish(id: String, title: String, artist: String, thumbnail: String?, guest: String): WishResult {
        wishes[id]?.let {
            it.guests.add(guest)
            return if (rate(id, guest, 1)) WishResult.VOTED else WishResult.ALREADY_VOTED
        }
        if (wishes.values.count { guest in it.guests } >= maxOpenPerGuest) return WishResult.LIMIT_REACHED
        wishes[id] = Wish(id, title, artist, thumbnail, linkedSetOf(guest))
        rate(id, guest, 1)
        return WishResult.ADDED
    }

    /** Returns false if the guest had already given exactly this rating. */
    @Synchronized
    fun rate(id: String, guest: String, value: Int): Boolean =
        ratings.getOrPut(id) { HashMap() }.put(guest, if (value < 0) -1 else 1) != (if (value < 0) -1 else 1)

    @Synchronized
    fun scoreOf(id: String): Int = ratings[id]?.values?.sum() ?: 0

    @Synchronized
    fun ratingOf(id: String, guest: String): Int = ratings[id]?.get(guest) ?: 0

    @Synchronized
    fun remove(id: String) {
        wishes.remove(id)
    }

    @Synchronized
    fun wishes(floor: Double, currentArtist: String?): List<Wish> =
        wishes.values.map { it.copy(guests = it.guests.toMutableSet(), votes = scoreOf(it.id)) }
            .sortedByDescending { score(it, floor, currentArtist) }

    /** Takes the wish that fits best right now out of the list. */
    @Synchronized
    fun pickNext(floor: Double, currentArtist: String?): Wish? =
        wishes(floor, currentArtist).firstOrNull()?.also { wishes.remove(it.id) }

    fun score(w: Wish, floor: Double, currentArtist: String?): Double {
        val learned = reaction[w.artist.lowercase()] ?: 0.5
        // An empty floor makes proven crowd pleasers count more than raw votes.
        val trust = if (floor < LOW_FLOOR * 2) 4.0 else 1.5
        val sameArtist = currentArtist != null && w.artist.equals(currentArtist, ignoreCase = true)
        return w.votes + (learned - 0.5) * trust - (if (sameArtist) 0.5 else 0.0)
    }

    /** Stores how full the floor was while an artist played (moving average, 0..1). */
    @Synchronized
    fun learn(artist: String, floorEnergy: Double) {
        val key = artist.lowercase()
        reaction[key] = (reaction[key] ?: 0.5) * 0.7 + floorEnergy.coerceIn(0.0, 1.0) * 0.3
    }

    @Synchronized
    fun reactions(): Map<String, Double> = HashMap(reaction)

    @Synchronized
    fun restoreReactions(map: Map<String, Double>) {
        reaction.putAll(map)
    }

    companion object {
        const val LOW_FLOOR = 0.12
    }
}

/** Decides when an empty floor should trigger an early switch to the next song. */
class RescueWatch(
    private val lowForMs: Long = 90_000,
    private val cooldownMs: Long = 180_000,
) {
    private var lowSince = -1L
    private var lastRescue = -cooldownMs

    fun feed(floor: Double, nowMs: Long): Boolean {
        if (floor >= DjBrain.LOW_FLOOR) {
            lowSince = -1
            return false
        }
        if (lowSince < 0) lowSince = nowMs
        if (nowMs - lowSince < lowForMs || nowMs - lastRescue < cooldownMs) return false
        lastRescue = nowMs
        lowSince = -1
        return true
    }

    fun reset() {
        lowSince = -1
    }
}

/**
 * Movement on the dance floor from two grayscale frames: mean absolute pixel change,
 * scaled by [sensitivity] so a lively floor lands near 1.
 */
fun motionEnergy(prev: ByteArray, cur: ByteArray, sensitivity: Double, step: Int = 8): Double {
    val n = min(prev.size, cur.size)
    if (n == 0) return 0.0
    var sum = 0L
    var count = 0
    var i = 0
    while (i < n) {
        sum += abs((prev[i].toInt() and 0xFF) - (cur[i].toInt() and 0xFF))
        count++
        i += step
    }
    return min(1.0, sum.toDouble() / count / 255.0 * sensitivity)
}

private val YOUTUBE_ID = Regex("""(?:youtube\.com/(?:watch\?(?:[^\s#]*&)?v=|shorts/|live/|embed/)|youtu\.be/)([\w-]{11})""")

/** Video id if [text] contains a YouTube or YouTube Music link. */
fun youtubeId(text: String): String? = YOUTUBE_ID.find(text)?.groupValues?.get(1)

/** Music sites whose page title names the song; only these are ever fetched for a guest. */
val LINK_HOSTS = setOf("open.spotify.com", "music.apple.com", "www.deezer.com", "deezer.com", "soundcloud.com", "tidal.com", "listen.tidal.com")

private val TITLE_NOISE = Regex(
    """\|.*$|song and lyrics by|song und lyrics von|\bsong by\b|\bsong von\b|\bon apple music\b|\bbei apple music\b|apple music|spotify|deezer|soundcloud|tidal|stream .* (?:by|von) |listen (?:online )?for free.*$|- single\b|- ep\b""",
    RegexOption.IGNORE_CASE,
)

/** Turns a streaming page title like "Song - song and lyrics by Artist | Spotify" into a search query. */
fun cleanPageTitle(title: String): String =
    title.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace(TITLE_NOISE, " ")
        .replace(Regex("""[‎‏–—\-|·•]+"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

/**
 * Search query from OCR lines (text to pixel height): the two tallest plausible lines,
 * which on player screenshots are the song title and the artist.
 */
fun ocrQuery(lines: List<Pair<String, Int>>): String =
    lines.asSequence()
        .map { it.first.trim() to it.second }
        .filter { (t, _) -> t.count(Char::isLetter) >= 2 && t.length <= 60 && !t.matches(Regex("""[\d:.\s%-]+""")) }
        .sortedByDescending { it.second }
        .take(2)
        .joinToString(" ") { it.first }

/**
 * Play order of the upcoming queue by guest rating: indices sorted by score, highest first.
 * Stable, so songs nobody rated keep their order between liked and disliked ones.
 */
fun voteOrder(scores: List<Int>): List<Int> = scores.indices.sortedByDescending { scores[it] }

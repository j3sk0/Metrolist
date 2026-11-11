/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kidj

import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.extensions.metadata
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Party mode: guests send wishes over the LAN, the camera measures the dance floor,
 * and the DJ queues the wish that fits the floor best right after the current song.
 */
object KiDj {
    private data class Entry(val id: String, val title: String, val artist: String, val thumbnail: String?, val trend: String? = null)

    private val brain = DjBrain()
    private val rescue = RescueWatch()

    // shortcut: every suggested song stays cached until the app closes, bound it if parties run for days
    private val songs = ConcurrentHashMap<String, SongItem>()

    /** Chart hint per song id and per "title|artist", loaded once from the YouTube Music charts. */
    private val trends = ConcurrentHashMap<String, String>()
    private val now = MutableStateFlow<Entry?>(null)
    private val upcoming = MutableStateFlow<List<Entry>>(emptyList())
    private var scope: CoroutineScope? = null
    private var server: WishServer? = null
    private var player: PlayerConnection? = null
    private var prefs: SharedPreferences? = null

    /** Wish inserted after the current song that has not started yet. */
    @Volatile private var pendingId: String? = null
    private var currentArtist: String? = null
    private var floorSum = 0.0
    private var floorCount = 0

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _url = MutableStateFlow<String?>(null)
    val url = _url.asStateFlow()
    private val _floor = MutableStateFlow(0.0)
    val floor = _floor.asStateFlow()
    private val _wishes = MutableStateFlow<List<Wish>>(emptyList())
    val wishes = _wishes.asStateFlow()
    private val _log = MutableStateFlow("")
    val log = _log.asStateFlow()
    val rescueEnabled = MutableStateFlow(true)
    val sensitivity = MutableStateFlow(12f)

    fun start(context: Context, connection: PlayerConnection) {
        if (_running.value) return
        player = connection
        prefs = context.getSharedPreferences("ki_dj", Context.MODE_PRIVATE).also { p ->
            brain.restoreReactions(
                p.all.mapNotNull { (k, v) -> if (k.startsWith("r:") && v is Float) k.removePrefix("r:") to v.toDouble() else null }.toMap(),
            )
            sensitivity.value = p.getFloat("sensitivity", 12f)
            rescueEnabled.value = p.getBoolean("rescue", true)
        }
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        s.launch {
            connection.mediaMetadata.collect { meta -> onSongChanged(meta?.id, meta?.artists?.firstOrNull()?.name) }
        }
        s.launch {
            combine(connection.queueWindows, connection.currentWindowIndex) { windows, index ->
                windows.drop(index.coerceAtLeast(0)).take(QUEUE_SHOWN + 1).mapNotNull { w ->
                    w.mediaItem.metadata?.let { m -> Entry(m.id, m.title, m.artists.joinToString { it.name }, m.thumbnailUrl) }
                }
            }.collect { list ->
                now.value = list.firstOrNull()
                upcoming.value = list.drop(1)
            }
        }
        s.launch(Dispatchers.IO) { loadTrends() }
        server = WishServer(WishServer.DEFAULT_PORT, handler).also {
            runCatching { it.start() }.onFailure { e ->
                Timber.e(e, "KI-DJ wish server failed")
                _log.value = "Server konnte nicht starten: ${e.message}"
            }
        }
        _url.value = localIp()?.let { "http://$it:${WishServer.DEFAULT_PORT}/" }
        _running.value = true
    }

    fun stop() {
        server?.stop()
        server = null
        scope?.cancel()
        scope = null
        player = null
        _running.value = false
        prefs?.edit()?.apply {
            brain.reactions().forEach { (k, v) -> putFloat("r:$k", v.toFloat()) }
            putFloat("sensitivity", sensitivity.value)
            putBoolean("rescue", rescueEnabled.value)
        }?.apply()
    }

    /** Called on the main thread by the camera analyzer with the current floor movement (0..1). */
    fun onFloor(energy: Double) {
        val smooth = _floor.value * 0.8 + energy * 0.2
        _floor.value = smooth
        floorSum += smooth
        floorCount++
        if (rescueEnabled.value && rescue.feed(smooth, System.currentTimeMillis())) {
            scope?.launch { rescueFloor() }
        }
    }

    /** Puts the wish next and blends into it right away. */
    fun playNow(id: String) {
        val song = songs[id] ?: return
        brain.remove(id)
        scope?.launch {
            player?.playNext(song.toMediaItem())
            pendingId = id
            player?.service?.crossfadeToNext()
            publish()
        }
    }

    fun remove(id: String) {
        brain.remove(id)
        publish()
    }

    private fun onSongChanged(id: String?, artist: String?) {
        // Remember how the floor reacted to the song that just ended.
        currentArtist?.let { if (floorCount > 0) brain.learn(it, floorSum / floorCount) }
        floorSum = 0.0
        floorCount = 0
        rescue.reset()
        currentArtist = artist
        if (id != null && id == pendingId) pendingId = null
        // The host may have removed the queued wish by hand.
        val p = player?.player
        if (pendingId != null && p != null && (0 until p.mediaItemCount).none { p.getMediaItemAt(it).mediaId == pendingId }) pendingId = null
        queueBestWish()
    }

    private fun queueBestWish() {
        if (pendingId != null) return
        val wish = brain.pickNext(_floor.value, currentArtist) ?: return publish()
        val song = songs[wish.id] ?: return publish()
        player?.playNext(song.toMediaItem())
        pendingId = wish.id
        _log.value = "Als Nächstes: ${wish.title} (${wish.votes} Stimme(n))"
        applyVoteOrder()
        publish()
    }

    private fun rescueFloor() {
        queueBestWish()
        _log.value = "Tanzfläche leer – wechsle früher"
        player?.service?.crossfadeToNext()
    }

    private fun publish() {
        _wishes.value = brain.wishes(_floor.value, currentArtist)
    }

    /** Sorts the upcoming songs by guest rating; liked songs move forward, disliked ones back. */
    private fun applyVoteOrder() {
        val p = player?.player ?: return
        // shortcut: with shuffle on the play order is not the list order, so ratings only show, they do not reorder
        if (p.shuffleModeEnabled) return
        val start = p.currentMediaItemIndex + 1
        val end = p.mediaItemCount.coerceAtMost(start + QUEUE_SORTED)
        if (start >= end) return
        val order = voteOrder((start until end).map { brain.scoreOf(p.getMediaItemAt(it).mediaId) })
        val positions = order.indices.toMutableList()
        order.forEachIndexed { target, original ->
            val from = positions.indexOf(original)
            if (from != target) {
                p.moveMediaItem(start + from, start + target)
                positions.add(target, positions.removeAt(from))
            }
        }
    }

    private suspend fun loadTrends() {
        val page = YouTube.getChartsPage().getOrNull() ?: return
        page.sections.forEach { section ->
            section.items.filterIsInstance<SongItem>().forEachIndexed { i, song ->
                val hint = if (section.chartType == com.metrolist.innertube.pages.ChartsPage.ChartType.TRENDING) "🔥 Trend" else "Charts #${song.chartPosition ?: (i + 1)}"
                trends.putIfAbsent(song.id, hint)
                trends.putIfAbsent(trendKey(song), hint)
            }
        }
    }

    private fun trendKey(song: SongItem) = "${song.title}|${song.artists.firstOrNull()?.name}".lowercase()

    private fun entry(song: SongItem) = Entry(
        song.id, song.title, song.artists.joinToString { it.name }, song.thumbnail,
        trends[song.id] ?: trends[trendKey(song)],
    )

    private suspend fun searchSongs(query: String): List<SongItem> =
        withTimeoutOrNull(NET_TIMEOUT_MS) {
            YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>()
        }.orEmpty()

    /** Page title of a streaming link, only for the known music sites and without following redirects. */
    private fun linkTitle(link: String): String? = runCatching {
        val url = URL(link.trim().substringBefore(' '))
        if (url.protocol != "https" || url.host.lowercase() !in LINK_HOSTS) return null
        val c = url.openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 6_000
            c.readTimeout = 6_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36")
            c.setRequestProperty("Accept-Language", "en")
            if (c.responseCode != 200) return null
            val head = c.inputStream.use { input ->
                val buffer = ByteArray(PAGE_HEAD_BYTES)
                var size = 0
                while (size < buffer.size) {
                    val n = input.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    size += n
                }
                String(buffer, 0, size)
            }
            Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(head)?.groupValues?.get(1)
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun addSong(song: SongItem, guest: String): String {
        songs[song.id] = song
        val artist = song.artists.joinToString { it.name }
        if (song.id == now.value?.id) return "${song.title} läuft gerade"
        if (upcoming.value.any { it.id == song.id }) {
            brain.rate(song.id, guest, 1)
            scope?.launch { applyVoteOrder() }
            return "${song.title} steht schon in der Warteschlange – deine Stimme zählt"
        }
        val result = brain.addWish(song.id, song.title, artist, song.thumbnail, guest)
        scope?.launch { queueBestWish() }
        return when (result) {
            WishResult.ADDED -> "Gewünscht: ${song.title} – $artist"
            WishResult.VOTED -> "Stimme für ${song.title} gezählt"
            WishResult.ALREADY_VOTED -> "Hast du schon gewünscht"
            WishResult.LIMIT_REACHED -> "Du hast schon 3 offene Wünsche"
        }
    }

    private fun entriesJson(list: List<Entry>, guest: String? = null) =
        list.joinToString(",", "[", "]") { entryJson(it, guest) }

    private fun entryJson(e: Entry, guest: String?) =
        "{\"id\":${WishServer.jsonString(e.id)},\"title\":${WishServer.jsonString(e.title)}," +
            "\"artist\":${WishServer.jsonString(e.artist)},\"thumbnail\":${WishServer.jsonString(e.thumbnail)}," +
            "\"trend\":${WishServer.jsonString(e.trend)},\"votes\":${brain.scoreOf(e.id)}," +
            "\"mine\":${if (guest == null) 0 else brain.ratingOf(e.id, guest)}}"

    private val handler = object : WishServer.Handler {
        override fun wish(query: String, guest: String): String {
            val videoId = youtubeId(query)
            val isLink = query.startsWith("http://", ignoreCase = true) || query.startsWith("https://", ignoreCase = true)
            val song = runBlocking {
                when {
                    videoId != null -> withTimeoutOrNull(NET_TIMEOUT_MS) { YouTube.queue(listOf(videoId)).getOrNull()?.firstOrNull() }
                    isLink -> linkTitle(query)?.let(::cleanPageTitle)?.takeIf { it.length >= 2 }?.let { searchSongs(it).firstOrNull() }
                    else -> searchSongs(query).firstOrNull()
                }
            }
            if (song == null) {
                return if (isLink) "Diesen Link konnte ich nicht lesen. Tippe bitte Titel und Interpret ein."
                else "Nichts gefunden zu „$query“."
            }
            return addSong(song, guest)
        }

        override fun wishId(id: String, guest: String): String =
            songs[id]?.let { addSong(it, guest) } ?: "Bitte noch einmal suchen."

        override fun vote(id: String, value: Int, guest: String): Boolean {
            // Only songs that are on the lists can be rated, so guests cannot fill memory with made-up ids.
            if (upcoming.value.none { it.id == id } && _wishes.value.none { it.id == id }) return false
            return brain.rate(id, guest, value).also {
                if (it) scope?.launch {
                    applyVoteOrder()
                    publish()
                }
            }
        }

        override fun stateJson(guest: String): String {
            val wishEntries = _wishes.value.map { Entry(it.id, it.title, it.artist, it.thumbnail) }
            return "{\"now\":${now.value?.let { entryJson(it, null) } ?: "null"}," +
                "\"queue\":${entriesJson(upcoming.value, guest)},\"wishes\":${entriesJson(wishEntries, guest)}}"
        }

        override fun suggestJson(query: String): String = runBlocking {
            coroutineScope {
                val found = async { searchSongs(query) }
                val artist = withTimeoutOrNull(NET_TIMEOUT_MS) {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST).getOrNull()?.items?.filterIsInstance<ArtistItem>()?.firstOrNull()
                }?.takeIf { it.title.contains(query, ignoreCase = true) }
                // The artist page lists the top songs first, most popular on top.
                val hits = artist?.let { a ->
                    withTimeoutOrNull(NET_TIMEOUT_MS) { YouTube.artist(a.id).getOrNull() }
                        ?.sections?.firstNotNullOfOrNull { sec -> sec.items.filterIsInstance<SongItem>().takeIf { it.isNotEmpty() } }
                }.orEmpty()
                val items = (hits + found.await()).distinctBy { it.id }.take(SUGGESTIONS)
                items.forEach { songs[it.id] = it }
                "{\"artist\":${WishServer.jsonString(artist?.title?.takeIf { hits.isNotEmpty() })},\"items\":${entriesJson(items.map(::entry))}}"
            }
        }

        override fun screenshotJson(image: ByteArray): String {
            val query = runCatching {
                val bitmap = BitmapFactory.decodeByteArray(image, 0, image.size) ?: return@runCatching ""
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                try {
                    val text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)), 20, TimeUnit.SECONDS)
                    ocrQuery(text.textBlocks.flatMap { it.lines }.map { it.text to (it.boundingBox?.height() ?: 0) })
                } finally {
                    recognizer.close()
                    bitmap.recycle()
                }
            }.onFailure { Timber.e(it, "KI-DJ screenshot OCR failed") }.getOrDefault("")
            val items = if (query.isBlank()) emptyList() else runBlocking { searchSongs(query) }.take(5)
            items.forEach { songs[it.id] = it }
            return "{\"query\":${WishServer.jsonString(query)},\"items\":${entriesJson(items.map(::entry))}}"
        }
    }

    private const val QUEUE_SHOWN = 20
    private const val QUEUE_SORTED = 50
    private const val SUGGESTIONS = 8
    private const val NET_TIMEOUT_MS = 8_000L
    private const val PAGE_HEAD_BYTES = 300_000

    private fun localIp(): String? =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
}

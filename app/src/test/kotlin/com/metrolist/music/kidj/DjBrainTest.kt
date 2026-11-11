package com.metrolist.music.kidj

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjBrainTest {
    @Test
    fun `same song from another guest counts as vote, limit per guest`() {
        val b = DjBrain(maxOpenPerGuest = 2)
        assertEquals(WishResult.ADDED, b.addWish("a", "A", "X", null, "g1"))
        assertEquals(WishResult.VOTED, b.addWish("a", "A", "X", null, "g2"))
        assertEquals(WishResult.ALREADY_VOTED, b.addWish("a", "A", "X", null, "g1"))
        assertEquals(WishResult.ADDED, b.addWish("b", "B", "Y", null, "g1"))
        assertEquals(WishResult.LIMIT_REACHED, b.addWish("c", "C", "Z", null, "g1"))
    }

    @Test
    fun `most votes wins on a full floor and pick removes the wish`() {
        val b = DjBrain()
        b.addWish("a", "A", "X", null, "g1")
        b.addWish("b", "B", "Y", null, "g1")
        b.rate("b", "g2", 1)
        assertEquals("b", b.pickNext(0.6, null)?.id)
        assertEquals("a", b.pickNext(0.6, null)?.id)
        assertNull(b.pickNext(0.6, null))
    }

    @Test
    fun `empty floor prefers an artist that filled it before`() {
        val b = DjBrain()
        repeat(5) { b.learn("Proven", 0.9) }
        b.addWish("a", "A", "Unknown", null, "g1")
        b.rate("a", "g2", 1)
        b.addWish("p", "P", "Proven", null, "g3")
        assertEquals("p", b.pickNext(0.05, null)?.id)
    }

    @Test
    fun `rescue fires after a long empty floor and respects cooldown`() {
        val r = RescueWatch(lowForMs = 1000, cooldownMs = 5000)
        assertFalse(r.feed(0.0, 10_000))
        assertTrue(r.feed(0.0, 11_000))
        assertFalse(r.feed(0.0, 12_500))
        assertFalse(r.feed(0.5, 13_000))
        assertFalse(r.feed(0.0, 14_000))
        assertTrue(r.feed(0.0, 16_100))
    }

    @Test
    fun `motion energy grows with pixel change`() {
        val a = ByteArray(64) { 10 }
        val b = ByteArray(64) { 60 }
        assertEquals(0.0, motionEnergy(a, a, 10.0), 1e-9)
        assertEquals(50.0 / 255 * 2, motionEnergy(a, b, 2.0), 1e-9)
        assertEquals(1.0, motionEnergy(a, b, 100.0), 1e-9)
    }

    @Test
    fun `wish server parses form posts and escapes json`() {
        val raw = "POST /api/wish?x=1 HTTP/1.1\r\nHost: x\r\nContent-Length: 14\r\n\r\nq=Daft+Punk%21"
        val req = WishServer.readRequest(raw.byteInputStream())!!
        assertEquals("POST", req.method)
        assertEquals("/api/wish", req.path)
        assertEquals("Daft Punk!", req.form["q"])
        assertNull(WishServer.readRequest("POST / HTTP/1.1\r\nContent-Length: 99999\r\n\r\n".byteInputStream()))
        assertEquals("\"a\\\"b\\u003cscript\\u003e\"", WishServer.jsonString("a\"b<script>"))
    }

    @Test
    fun `wish server routes to handler`() {
        var got = ""
        val server = WishServer(0, object : WishServer.Handler {
            override fun wish(query: String, guest: String) = "ok".also { got = "$query@$guest" }
            override fun wishId(id: String, guest: String) = "ok".also { got = "id:$id" }
            override fun vote(id: String, value: Int, guest: String) = true.also { got = "$id:$value" }
            override fun stateJson(guest: String) = "{}"
            override fun suggestJson(query: String) = "s:$query"
            override fun screenshotJson(image: ByteArray) = "img:${image.size}"
        })
        fun req(method: String, path: String, form: Map<String, String> = emptyMap(), body: ByteArray = ByteArray(0)) =
            server.route(WishServer.Request(method, path, form, body), "1.2.3.4")
        assertEquals(200, req("POST", "/api/wish", mapOf("q" to "  Song  ")).status)
        assertEquals("Song@1.2.3.4", got)
        req("POST", "/api/wish", mapOf("id" to "abc"))
        assertEquals("id:abc", got)
        assertEquals(400, req("POST", "/api/wish", mapOf("q" to "x")).status)
        req("POST", "/api/vote", mapOf("id" to "abc", "v" to "-1"))
        assertEquals("abc:-1", got)
        req("POST", "/api/vote", mapOf("id" to "abc", "v" to "junk"))
        assertEquals("abc:1", got)
        assertEquals("{}", req("GET", "/api/state").body)
        assertEquals("s:abba", req("GET", "/api/suggest", mapOf("q" to "abba")).body)
        assertEquals("{\"items\":[]}", req("GET", "/api/suggest", mapOf("q" to "a")).body)
        assertEquals("img:3", req("POST", "/api/screenshot", body = byteArrayOf(1, 2, 3)).body)
        assertTrue(req("GET", "/").body.contains("Musikwunsch"))
    }

    @Test
    fun `request parsing reads query string, image bodies and survives bad escapes`() {
        val get = WishServer.readRequest("GET /api/suggest?q=daft%20punk HTTP/1.1\r\n\r\n".byteInputStream())!!
        assertEquals("daft punk", get.form["q"])
        val img = WishServer.readRequest("POST /api/screenshot HTTP/1.1\r\nContent-Length: 5000\r\n\r\n".toByteArray().plus(ByteArray(5000)).inputStream())!!
        assertEquals(5000, img.body.size)
        assertNull(WishServer.readRequest("POST /api/wish HTTP/1.1\r\nContent-Length: 5000\r\n\r\n".byteInputStream()))
        assertEquals(emptyMap<String, String>(), WishServer.parseForm("q=%zz"))
    }

    @Test
    fun `likes and dislikes add up per guest and can be changed`() {
        val b = DjBrain()
        assertTrue(b.rate("a", "g1", 1))
        assertFalse(b.rate("a", "g1", 1))
        assertTrue(b.rate("a", "g2", -1))
        assertTrue(b.rate("a", "g3", -1))
        assertEquals(-1, b.scoreOf("a"))
        assertTrue(b.rate("a", "g3", 1))
        assertEquals(1, b.scoreOf("a"))
        assertEquals(-1, b.ratingOf("a", "g2"))
        assertEquals(0, b.ratingOf("a", "nobody"))
    }

    @Test
    fun `disliked wish loses against a liked one`() {
        val b = DjBrain()
        b.addWish("a", "A", "X", null, "g1")
        b.addWish("b", "B", "Y", null, "g2")
        b.rate("a", "g2", -1)
        b.rate("a", "g3", -1)
        assertEquals("b", b.pickNext(0.6, null)?.id)
    }

    @Test
    fun `queue order follows score and keeps unrated songs in place`() {
        assertEquals(listOf(2, 0, 1, 4, 3), voteOrder(listOf(0, 0, 2, -1, 0)))
        assertEquals(listOf(0, 1, 2), voteOrder(listOf(0, 0, 0)))
    }

    @Test
    fun `links and page titles become search queries`() {
        assertEquals("dQw4w9WgXcQ", youtubeId("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=x"))
        assertEquals("dQw4w9WgXcQ", youtubeId("schau https://youtu.be/dQw4w9WgXcQ?t=3"))
        assertEquals("dQw4w9WgXcQ", youtubeId("https://www.youtube.com/watch?list=PL1&v=dQw4w9WgXcQ"))
        assertNull(youtubeId("https://open.spotify.com/track/0DiWol3AO6WpXZgp0goxAV"))
        assertEquals("One More Time Daft Punk", cleanPageTitle("One More Time - song and lyrics by Daft Punk | Spotify"))
        assertEquals("One More Time Daft Punk", cleanPageTitle("\u200eOne More Time – Song by Daft Punk – Apple Music"))
    }

    @Test
    fun `screenshot query takes the two biggest text lines`() {
        val lines = listOf("21:37" to 90, "One More Time" to 60, "Daft Punk" to 40, "Discovery · 2001" to 20, "1:23" to 70, "A" to 99)
        assertEquals("One More Time Daft Punk", ocrQuery(lines))
        assertEquals("", ocrQuery(emptyList()))
    }
}

/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.kidj

import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Tiny LAN web server for guests who scanned the QR code.
 * GET / -> wish page, GET /api/wishes -> JSON, POST /api/wish (q=...), POST /api/vote (id=...).
 */
class WishServer(
    private val port: Int,
    private val handler: Handler,
) {
    interface Handler {
        /** Free text or a link. Returns a short message for the guest. */
        fun wish(query: String, guest: String): String

        /** A song picked from suggestions. */
        fun wishId(id: String, guest: String): String

        /** Like ([value] 1) or dislike (-1). False if the guest already rated it that way. */
        fun vote(id: String, value: Int, guest: String): Boolean

        /** Now playing, upcoming queue and open wishes as JSON, with this guest's own ratings. */
        fun stateJson(guest: String): String

        /** Suggestions while typing as JSON. */
        fun suggestJson(query: String): String

        /** Songs recognized on an uploaded screenshot as JSON (same shape as suggestions). */
        fun screenshotJson(image: ByteArray): String
    }

    private var socket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    val isRunning get() = socket?.isClosed == false

    fun start() {
        if (isRunning) return
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        socket = server
        Thread({
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { client.use { serve(it) } }
            }
        }, "KiDjWishServer").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = 5_000
        val guest = client.inetAddress.hostAddress ?: "?"
        val response = runCatching {
            val req = readRequest(BufferedInputStream(client.getInputStream())) ?: return
            route(req, guest)
        }.getOrElse { Response(500, "text/plain", "error") }
        val body = response.body.toByteArray()
        val head = "HTTP/1.1 ${response.status} OK\r\n" +
            "Content-Type: ${response.type}; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray())
            write(body)
            flush()
        }
    }

    internal fun route(req: Request, guest: String): Response = when {
        req.method == "GET" && req.path == "/" -> Response(200, "text/html", WISH_PAGE)
        req.method == "GET" && req.path == "/api/state" -> Response(200, "application/json", handler.stateJson(guest))
        req.method == "GET" && req.path == "/api/suggest" -> {
            val q = req.form["q"]?.trim().orEmpty().take(MAX_QUERY)
            Response(200, "application/json", if (q.length < 2) "{\"items\":[]}" else handler.suggestJson(q))
        }
        req.method == "POST" && req.path == "/api/wish" -> {
            val id = req.form["id"].orEmpty().take(32)
            val q = req.form["q"]?.trim().orEmpty().take(MAX_QUERY)
            when {
                id.isNotEmpty() -> Response(200, "application/json", jsonMessage(handler.wishId(id, guest)))
                q.length < 2 -> Response(400, "application/json", jsonMessage("Bitte Titel, Interpret oder Link eingeben."))
                else -> Response(200, "application/json", jsonMessage(handler.wish(q, guest)))
            }
        }
        req.method == "POST" && req.path == "/api/vote" -> {
            val value = if (req.form["v"] == "-1") -1 else 1
            val ok = handler.vote(req.form["id"].orEmpty().take(32), value, guest)
            Response(200, "application/json", jsonMessage(if (!ok) "Schon so bewertet" else if (value > 0) "Gefällt dir" else "Abgewählt"))
        }
        req.method == "POST" && req.path == SCREENSHOT_PATH ->
            if (req.body.isEmpty()) Response(400, "application/json", "{\"items\":[]}")
            else Response(200, "application/json", handler.screenshotJson(req.body))
        else -> Response(404, "text/plain", "not found")
    }

    internal class Request(val method: String, val path: String, val form: Map<String, String>, val body: ByteArray = ByteArray(0))

    internal data class Response(val status: Int, val type: String, val body: String)

    companion object {
        const val DEFAULT_PORT = 8080
        private const val MAX_BODY = 2048
        private const val MAX_IMAGE = 2_000_000
        private const val MAX_QUERY = 300
        private const val SCREENSHOT_PATH = "/api/screenshot"

        internal fun readRequest(input: InputStream): Request? {
            val lines = mutableListOf<String>()
            val line = StringBuilder()
            var total = 0
            while (true) {
                val c = input.read()
                if (c < 0) return null
                if (++total > 8192) return null
                if (c == '\n'.code) {
                    val l = line.toString().trimEnd('\r')
                    if (l.isEmpty()) break
                    lines += l
                    line.clear()
                } else {
                    line.append(c.toChar())
                }
            }
            val parts = lines.firstOrNull()?.split(" ") ?: return null
            if (parts.size < 2) return null
            val length = lines.drop(1)
                .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            val path = parts[1].substringBefore('?')
            val isImage = path == SCREENSHOT_PATH
            if (length > (if (isImage) MAX_IMAGE else MAX_BODY)) return null
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            val query = parseForm(parts[1].substringAfter('?', ""))
            return if (isImage) Request(parts[0], path, query, body.copyOf(read))
            else Request(parts[0], path, query + parseForm(String(body, 0, read)))
        }

        internal fun parseForm(body: String): Map<String, String> =
            body.split('&').filter { '=' in it }.mapNotNull {
                runCatching {
                    URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
                }.getOrNull()
            }.toMap()

        fun jsonString(s: String?): String {
            if (s == null) return "null"
            val sb = StringBuilder("\"")
            for (c in s) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c < ' ' || c == '<' || c == '>' -> sb.append(String.format("\\u%04x", c.code))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        private fun jsonMessage(msg: String) = "{\"message\":${jsonString(msg)}}"

        private const val WISH_PAGE = WISH_PAGE_HTML
    }
}

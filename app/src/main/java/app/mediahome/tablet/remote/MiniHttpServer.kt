package app.mediahome.tablet.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    private val headers: Map<String, String>,
    val body: String,
    val remoteAddress: String,
    /** Only for upload paths: the request body, not read yet (exactly [contentLength] bytes). */
    val bodyStream: InputStream? = null,
    val contentLength: Long = body.length.toLong(),
) {
    fun header(name: String): String? = headers[name.lowercase()]

    /** Form fields from an application/x-www-form-urlencoded body (what iPhone Shortcuts sends as "Form"). */
    private val form: Map<String, String> by lazy {
        if ((header("content-type") ?: "").startsWith("application/x-www-form-urlencoded", ignoreCase = true)) {
            MiniHttpServer.parseQuery(body.trim())
        } else {
            emptyMap()
        }
    }

    /** A parameter from the address (?name=) or, failing that, from a form body. */
    fun param(name: String): String? = query[name] ?: form[name]
}

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** For downloads: sent instead of [body], [streamLength] bytes, then closed. */
    val stream: InputStream? = null,
    val streamLength: Long = -1,
) {
    companion object {
        fun json(status: Int, json: String) =
            HttpResponse(status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8))
    }
}

/**
 * A deliberately small HTTP/1.1 server (one request per connection, 64 KB body limit, 10 s read timeout).
 * Paths in [streamingPaths] (file uploads) may send large bodies; the handler reads them as a stream after its checks.
 * No third-party libraries. Used only on the local network for the Media Home web remote.
 */
class MiniHttpServer(
    private val port: Int,
    private val streamingPaths: Set<String> = emptySet(),
    private val handler: (HttpRequest) -> HttpResponse,
) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val isRunning: Boolean get() = socket?.isClosed == false
    val localPort: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start() {
        if (isRunning) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        socket = s
        val p = Executors.newFixedThreadPool(8)
        pool = p
        Thread({ acceptLoop(s, p) }, "mediahome-http").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun acceptLoop(s: ServerSocket, p: ExecutorService) {
        while (!s.isClosed) {
            val client = try {
                s.accept()
            } catch (e: SocketException) {
                break
            } catch (e: Exception) {
                continue
            }
            try {
                p.execute { serve(client) }
            } catch (e: Exception) {
                try { client.close() } catch (_: Exception) { }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.use { c ->
                c.soTimeout = 10_000
                val input = BufferedInputStream(c.getInputStream())
                val req = parse(input, c.inetAddress?.hostAddress ?: "") ?: return
                val res = if (!isLocalNetwork(c.inetAddress)) {
                    HttpResponse.json(403, "{\"ok\":false,\"error\":\"local network only\"}")
                } else try {
                    handler(req)
                } catch (e: Exception) {
                    HttpResponse.json(500, "{\"ok\":false,\"error\":\"internal error\"}")
                }
                try {
                    write(c.getOutputStream(), res)
                } finally {
                    try { res.stream?.close() } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) return null
        }
        return buf.toString("ISO-8859-1")
    }

    private fun parse(input: InputStream, remote: String): HttpRequest? {
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        var count = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (++count > 100) return null
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val target = parts[1]
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) parseQuery(target.substring(q + 1)) else emptyMap()
        val method = parts[0].uppercase()
        if (method == "POST" && path in streamingPaths) {
            val big = headers["content-length"]?.toLongOrNull() ?: return null
            if (big < 0 || big > MAX_UPLOAD) return null
            return HttpRequest(method, path, query, headers, "", remote, LimitedInputStream(input, big), big)
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len < 0 || len > 65_536) return null
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        return HttpRequest(method, path, query, headers, String(body, 0, read, Charsets.UTF_8), remote)
    }

    private fun write(out: OutputStream, r: HttpResponse) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(REASONS[r.status] ?: "OK").append("\r\n")
        sb.append("Content-Type: ").append(r.contentType).append("\r\n")
        sb.append("Content-Length: ").append(if (r.stream != null) r.streamLength else r.body.size.toLong()).append("\r\n")
        sb.append("Cache-Control: no-store\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Connection: close\r\n")
        for ((k, v) in r.extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        val s = r.stream
        if (s == null) {
            out.write(r.body)
        } else {
            val buf = ByteArray(64 * 1024)
            var left = r.streamLength
            while (left > 0) {
                val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    companion object {
        /** Largest upload accepted (64 GiB; the real limit is the free space on the drive). */
        const val MAX_UPLOAD = 64L * 1024 * 1024 * 1024

        /**
         * Only devices on the home network may use the remote: loopback, private IPv4 (10/8, 172.16/12, 192.168/16),
         * link-local, and private IPv6 (fc00::/7). Anything else (for example a public IPv6 address reaching the
         * tablet through the router) gets 403 before the PIN is even checked.
         */
        fun isLocalNetwork(a: InetAddress?): Boolean {
            if (a == null) return false
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            return a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc
        }

        private val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 409 to "Conflict", 413 to "Payload Too Large", 429 to "Too Many Requests",
            500 to "Internal Server Error", 502 to "Bad Gateway", 507 to "Insufficient Storage",
        )

        fun parseQuery(s: String): Map<String, String> = s.split('&').filter { it.isNotEmpty() }.associate {
            val i = it.indexOf('=')
            val k = if (i >= 0) it.substring(0, i) else it
            val v = if (i >= 0) it.substring(i + 1) else ""
            val dk = try { URLDecoder.decode(k, "UTF-8") } catch (e: IllegalArgumentException) { k }
            val dv = try { URLDecoder.decode(v, "UTF-8") } catch (e: IllegalArgumentException) { v }
            dk to dv
        }
    }
}

/** Reads at most [limit] bytes from [inner] (one request body), never past it. */
class LimitedInputStream(private val inner: InputStream, private var limit: Long) : InputStream() {
    override fun read(): Int {
        if (limit <= 0) return -1
        val b = inner.read()
        if (b >= 0) limit--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit <= 0) return -1
        val n = inner.read(b, off, minOf(len.toLong(), limit).toInt())
        if (n > 0) limit -= n
        return n
    }

    /** Bytes of the body not read yet. */
    val remaining: Long get() = limit
}

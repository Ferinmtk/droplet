package dev.droplet.app.mesh

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * Files sent directly (docs/mesh.md §9.5): offers, serving them with Range,
 * and downloading with resume. Exactly the reference's files.py.
 */
object MeshFiles {
    val OFFER_ID = Regex("^[0-9a-f]{16,64}$")
    private const val CHUNK = 256 * 1024
    const val MAX_SIZE = 1L shl 40
    const val SPACE_MARGIN = 64L * 1024 * 1024
    private const val KEEP_COMPLETED = 500

    /** A file name that can't escape a folder or hide itself. */
    fun safeName(raw: String?): String {
        var name = (raw ?: "").replace('\\', '/').substringAfterLast('/')
        name = name.filter { !Character.isISOControl(it) && Character.getType(it) != Character.FORMAT.toInt() && it !in "<>:\"|?*" }.trim()
        name = name.trimStart('.').trim()
        if (name.toByteArray().size > 200) {
            val dot = name.lastIndexOf('.')
            val ext = if (dot > 0 && name.length - dot - 1 <= 16) name.substring(dot + 1) else ""
            var base = if (ext.isNotEmpty()) name.substring(0, dot) else name
            while (base.toByteArray().size > 200 - ext.length - 1) base = base.dropLast(1)
            name = if (ext.isNotEmpty()) "$base.$ext" else base
        }
        return name.ifEmpty { "file" }
    }

    sealed class Range {
        object Whole : Range()
        object Bad : Range()
        data class Span(val first: Long, val last: Long) : Range()
    }

    /** A `Range` header: the whole file, one span (inclusive), or unsatisfiable. Only a single `bytes=` range. */
    fun parseRange(header: String?, size: Long): Range {
        if (header.isNullOrEmpty()) return Range.Whole
        val m = Regex("\\s*bytes\\s*=\\s*(\\d*)\\s*-\\s*(\\d*)\\s*").matchEntire(header) ?: return Range.Whole
        val a = m.groupValues[1]
        val b = m.groupValues[2]
        if (a.isEmpty() && b.isEmpty()) return Range.Whole
        if (a.isEmpty()) {
            val n = b.toBigInteger()
            if (n.signum() == 0 || size == 0L) return Range.Bad
            val tail = n.min(size.toBigInteger()).toLong()
            return Range.Span(size - tail, size - 1)
        }
        val first = a.toBigInteger()
        if (first >= size.toBigInteger()) return Range.Bad
        val last = if (b.isNotEmpty()) b.toBigInteger().min((size - 1).toBigInteger()) else (size - 1).toBigInteger()
        if (b.isNotEmpty() && b.toBigInteger() < first) return Range.Bad
        return Range.Span(first.toLong(), last.toLong())
    }

    /** What this phone offers one peer. [open] gives the file from a byte offset. */
    class Offer(
        val id: String,
        val fp: String,
        val name: String,
        val size: Long,
        val mime: String,
        val open: (from: Long) -> InputStream,
        val check: (() -> String?)? = null,
    ) {
        @Volatile var sent = 0L
        @Volatile var lastActivity = System.currentTimeMillis()
        @Volatile var result: Pair<Boolean, String>? = null
        private val done = java.util.concurrent.CountDownLatch(1)

        fun message(): JSONObject = JSONObject().put("t", "offer").put("id", id).put("name", name).put("size", size).put("mime", mime)

        fun finish(ok: Boolean, error: String = "") {
            if (result == null) result = ok to error
            done.countDown()
        }

        fun await(ms: Long): Boolean = done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    class Offers {
        private val offers = java.util.concurrent.ConcurrentHashMap<String, Offer>()

        fun add(o: Offer) { offers[o.id] = o }
        fun get(id: String): Offer? = offers[id]
        fun remove(id: String) { offers.remove(id) }

        /** The receiver's ack or nack: only the peer the offer was made to counts. */
        fun resolve(fp: String, id: String, ok: Boolean, error: String = "") {
            get(id)?.takeIf { it.fp == fp }?.finish(ok, error)
        }

        /** Answers GET/HEAD /mesh/files/<id> for peer [fp]. */
        fun serve(out: OutputStream, id: String, fp: String, range: String?, method: String, sendHead: (Int, Map<String, String>) -> Unit) {
            val o = if (OFFER_ID.matches(id)) get(id) else null
            if (o == null || o.fp != fp) {
                // the same answer whether it doesn't exist or isn't for you
                sendHead(404, mapOf("Content-Length" to "0"))
                return
            }
            val why = o.check?.invoke()
            if (why != null) {
                sendHead(410, mapOf("Content-Length" to "0"))
                o.finish(false, why)
                return
            }
            val r = parseRange(range, o.size)
            if (r is Range.Bad) {
                sendHead(416, mapOf("Content-Range" to "bytes */${o.size}", "Content-Length" to "0"))
                return
            }
            val (first, last) = if (r is Range.Span) r.first to r.last else 0L to o.size - 1
            val length = maxOf(0L, last - first + 1)
            val head = linkedMapOf("Content-Type" to o.mime.ifEmpty { "application/octet-stream" },
                "Content-Length" to length.toString(), "Accept-Ranges" to "bytes")
            if (r is Range.Span) head["Content-Range"] = "bytes $first-$last/${o.size}"
            sendHead(if (r is Range.Span) 206 else 200, head)
            if (method == "HEAD" || length == 0L) return
            o.lastActivity = System.currentTimeMillis()
            o.open(first).use { input ->
                val buf = ByteArray(CHUNK)
                var done = 0L
                while (done < length) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), length - done).toInt())
                    if (n < 0) throw IOException("the file got shorter while it was being sent")
                    out.write(buf, 0, n)
                    done += n
                    o.sent += n
                    o.lastActivity = System.currentTimeMillis()
                }
                out.flush()
            }
        }
    }

    /** Offers already received, by (sender fingerprint, id), so a re-offer isn't saved twice. */
    class Completed(private val file: File) {
        private val items = ArrayList<JSONObject>()

        init {
            runCatching { JSONArray(file.readText()) }.getOrNull()?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { items.add(it) }
            }
        }

        @Synchronized
        fun has(fp: String, id: String): String? =
            items.firstOrNull { it.optString("fp") == fp && it.optString("id") == id }?.optString("path")

        @Synchronized
        fun add(fp: String, id: String, path: String) {
            items.add(JSONObject().put("fp", fp).put("id", id).put("path", path).put("ts", System.currentTimeMillis() / 1000))
            while (items.size > KEEP_COMPLETED) items.removeAt(0)
            file.parentFile?.mkdirs()
            MeshIdentity.writePrivate(file, JSONArray(items).toString().toByteArray())
        }
    }

    class DownloadError(message: String, val permanent: Boolean = false) : Exception(message)

    class Checked(val id: String, val name: String, val size: Long, val mime: String)

    /** (id, safe name, size, mime) from an offer; a malformed one is a permanent error. */
    fun checkOffer(msg: JSONObject): Checked {
        val id = msg.opt("id") as? String
        if (id == null || !OFFER_ID.matches(id)) throw DownloadError("bad offer id", true)
        val size = msg.opt("size")
        if (size !is Int && size !is Long) throw DownloadError("bad size", true)
        val n = (size as Number).toLong()
        if (n !in 0..MAX_SIZE) throw DownloadError("bad size", true)
        val mime = (msg.opt("mime") as? String)?.takeIf { Regex("[\\w.+-]+/[\\w.+-]+").matches(it) } ?: "application/octet-stream"
        return Checked(id, safeName(msg.opt("name") as? String), n, mime)
    }

    fun partFiles(dir: File, fp: String, id: String): Pair<File, File> =
        File(dir, ".droplet-${fp.take(16)}-$id.part") to File(dir, ".droplet-${fp.take(16)}-$id.json")

    /**
     * Fetches an offer into a partial file in [dir], resuming what's there.
     * Returns the complete part file; the caller gives it its final home.
     */
    fun download(identity: MeshIdentity, fp: String, hosts: List<Pair<String, Int>>, c: Checked, dir: File,
                 tries: Int = 5, freeSpace: (File) -> Long = { it.usableSpace },
                 sleep: (Long) -> Unit = { Thread.sleep(it) }, onProgress: ((Long, Long) -> Unit)? = null,
                 log: (String) -> Unit = {}): File {
        dir.mkdirs()
        val (part, meta) = partFiles(dir, fp, c.id)
        val prev = runCatching { JSONObject(meta.readText()) }.getOrNull()
        if (prev == null || prev.optLong("size", -1) != c.size || !part.exists() || part.length() > c.size) {
            part.delete()
            meta.writeText(JSONObject().put("size", c.size).put("name", c.name).put("fp", fp).toString())
        }
        val have0 = if (part.exists()) part.length() else 0L
        if (c.size - have0 + SPACE_MARGIN > freeSpace(dir)) throw DownloadError("not enough space for ${c.name} (${c.size} bytes)", true)
        var lastError = "no address to fetch it from"
        var delay = 1000L
        for (attempt in 0 until tries) {
            for ((host, port) in hosts) {
                val have = if (part.exists()) part.length() else 0L
                if (have == c.size) break
                try {
                    fetch(identity, fp, host, port, c.id, part, have, c.size, onProgress, log)
                    break
                } catch (e: DownloadError) {
                    if (e.permanent) throw e
                    lastError = e.message ?: "failed"
                } catch (e: IOException) {
                    lastError = "$host: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (part.exists() && part.length() == c.size) break
            if (attempt == tries - 1) throw DownloadError(lastError)
            log("download of ${c.name} stopped at ${if (part.exists()) part.length() else 0} of ${c.size} bytes ($lastError); resuming in ${delay / 1000} s")
            sleep(delay)
            delay = minOf(delay * 2, 16_000)
        }
        if (!part.exists()) part.createNewFile()
        if (part.length() != c.size) throw DownloadError("got ${part.length()} bytes, expected ${c.size}")
        meta.delete()
        return part
    }

    private fun fetch(identity: MeshIdentity, fp: String, host: String, port: Int, id: String, part: File, have0: Long,
                      size: Long, onProgress: ((Long, Long) -> Unit)?, log: (String) -> Unit) {
        var have = have0
        val client = MeshTls.client(identity, fp)
        val req = Request.Builder().url("https://${hostPort(host, port)}/mesh/files/$id")
            .header("User-Agent", MeshPairing.USER_AGENT)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        client.newCall(req).execute().use { resp ->
            when {
                resp.code == 404 || resp.code == 410 -> throw DownloadError("the sender doesn't offer it any more", true)
                have > 0 && resp.code == 200 -> have = 0   // it ignored the range: start again
                resp.code != 200 && resp.code != 206 -> throw DownloadError("the sender answered ${resp.code}")
            }
            if (resp.code == 206) {
                val m = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(resp.header("Content-Range") ?: "")
                if (m == null || m.groupValues[1].toLong() != have || m.groupValues[3].toLong() != size) {
                    throw DownloadError("the sender's range doesn't match the partial file")
                }
            }
            if (have > 0) log("resuming ${part.name} at byte $have of $size")
            val body = resp.body ?: throw DownloadError("no body")
            RandomAccessFile(part, "rw").use { f ->
                f.setLength(have)
                f.seek(have)
                var got = have
                val buf = ByteArray(CHUNK)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (got + n > size) throw DownloadError("the sender sent more than it offered", true)
                        f.write(buf, 0, n)
                        got += n
                        onProgress?.invoke(got, size)
                    }
                }
                f.fd.sync()
                if (got != size) throw DownloadError("the connection ended at $got of $size bytes")
            }
        }
    }
}

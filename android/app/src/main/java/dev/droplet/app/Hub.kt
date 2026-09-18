package dev.droplet.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import okhttp3.FormBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class HubException(message: String) : IOException(message)

data class Device(val id: String, val name: String, val online: Boolean, val self: Boolean)

data class Ring(val id: String, val from: String, val ts: Double)

/** A file to upload: a content URI plus the name and size the sharing app reported. */
data class Outgoing(val uri: Uri, val name: String, val size: Long, val mime: String?)

/**
 * The droplet hub's HTTP API, called with the same cookies as the WebView so
 * the hub sees the same device (the HttpOnly `droplet_device` token) and the
 * same PIN session.
 */
object Hub {
    const val DEVICE_COOKIE = "droplet_device"
    val userAgent = "droplet-android/${BuildConfig.VERSION_NAME}"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Uploads can be big; after the last byte the hub still has to write the file. */
    private val uploadClient: OkHttpClient = client.newBuilder()
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    val base: String? get() = Prefs.hubUrl

    /** "t15.tail7375fe.ts.net" style label for notifications. */
    fun hostLabel(): String = base?.let { Uri.parse(it).host } ?: "the hub"

    /** Tidies what someone typed into a base URL, or null if it isn't one. */
    fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = Uri.parse(s)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty()) return null
        return "${uri.scheme}://${uri.encodedAuthority}"
    }

    private fun cookies(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    /** True once this phone has been named in the web app (the hub gave it a device cookie). */
    fun hasDevice(): Boolean = deviceToken() != null

    /**
     * This device's token: the WebView's `droplet_device` cookie. Native code
     * sends it as a bearer too, which is what the live connection needs.
     */
    fun deviceToken(hub: String? = base): String? {
        val b = hub ?: return null
        return cookies(b)?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$DEVICE_COOKIE=") }
            ?.substringAfter('=')?.takeIf { it.isNotEmpty() }
    }

    /** wss://host/ws for an https hub, ws:// for the emulator's plain-http one. */
    fun socketUrl(hub: String? = base): String? =
        hub?.let { (if (it.startsWith("https://")) "wss://" + it.removePrefix("https://") else "ws://" + it.removePrefix("http://")) + "/ws" }

    /** Cookies (the PIN session) and the device token, for a request to [url] on the hub. */
    fun authHeaders(url: String, hub: String? = base): List<Pair<String, String>> = buildList {
        cookies(url)?.let { add("Cookie" to it) }
        deviceToken(hub)?.let { add("Authorization" to "Bearer $it") }
    }

    private fun request(path: String, hub: String? = base): Request.Builder {
        val b = hub ?: throw HubException("No hub set up")
        val url = b + path
        return Request.Builder().url(url).header("User-Agent", userAgent).apply {
            authHeaders(url, b).forEach { (k, v) -> header(k, v) }
        }
    }

    /**
     * Trades a six-digit link code (made on another browser's droplet page)
     * for that device's identity, and stores the token as the WebView's
     * cookie, so the page and the native parts all become that device.
     * Returns the device's name.
     */
    fun link(code: String): String {
        val b = base ?: throw HubException("No hub set up")
        val body = JSONObject().put("code", code).put("client", "droplet-android on ${android.os.Build.MODEL}")
        // no Authorization here: the code alone says which device this becomes
        val req = Request.Builder().url("$b/api/device/link").header("User-Agent", userAgent).apply {
            cookies(b)?.let { header("Cookie", it) }
        }.post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val res = client.newCall(req).execute().json()
        val token = res.optString("token").takeIf { it.isNotEmpty() } ?: throw HubException("The hub sent no token")
        val secure = if (b.startsWith("https://")) "; Secure" else ""
        val cm = CookieManager.getInstance()
        cm.setCookie(b, "$DEVICE_COOKIE=$token; Max-Age=${5 * 365 * 24 * 3600}; Path=/; HttpOnly; SameSite=Lax$secure")
        cm.flush()
        return res.optString("name", "that device")
    }

    private fun Response.json(): JSONObject {
        use {
            // the PIN gate redirects anything without a session to the login page
            if (request.url.encodedPath == "/login") throw HubException("The hub wants its PIN: open droplet and log in")
            val text = body?.string().orEmpty()
            if (!isSuccessful) {
                val err = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw HubException(err?.takeIf { it.isNotBlank() } ?: "The hub answered $code")
            }
            return runCatching { JSONObject(text) }.getOrElse { throw HubException("The hub sent something unexpected") }
        }
    }

    private fun get(path: String): JSONObject = client.newCall(request(path).build()).execute().json()

    private fun post(path: String, body: RequestBody, http: OkHttpClient = client): JSONObject =
        http.newCall(request(path).post(body).build()).execute().json()

    fun postJson(path: String, json: JSONObject): JSONObject =
        post(path, json.toString().toRequestBody("application/json".toMediaType()))

    /** Checks that [url] is a droplet hub; returns null if so, else why not. */
    fun probe(url: String): String? = try {
        client.newCall(request("/api/me", url).build()).execute().use { r ->
            when {
                r.request.url.encodedPath == "/login" -> null  // a hub with a PIN: fine, the page asks for it
                !r.isSuccessful -> "it answered ${r.code}"
                runCatching { JSONObject(r.body!!.string()).has("device") }.getOrDefault(false) -> null
                else -> "not-droplet"
            }
        }
    } catch (e: IOException) {
        e.message ?: e.javaClass.simpleName
    }

    fun me(): JSONObject = get("/api/me")

    fun files(): JSONObject = get("/api/files")

    fun devices(files: JSONObject = files()): List<Device> {
        val arr = files.optJSONArray("devices") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Device(d.getString("id"), d.getString("name"), d.optBoolean("online"), d.optBoolean("self"))
        }
    }

    /** The ring for this device, if another device is ringing it now. */
    fun ring(): Ring? {
        val r = get("/api/ring").optJSONObject("ring") ?: return null
        return Ring(r.optString("id"), r.optString("from", "another device"), r.optDouble("ts", 0.0))
    }

    fun stopRing() {
        post("/api/ring/stop", ByteArray(0).toRequestBody(null))
    }

    fun sendText(text: String, to: String) {
        post("/text", FormBody.Builder().add("text", text).add("to", to).build())
    }

    /**
     * Streams [files] to /upload as one multipart request, reporting bytes sent.
     * Nothing is buffered in memory, so big videos are fine.
     */
    fun upload(context: Context, files: List<Outgoing>, to: String, progress: (sent: Long, total: Long) -> Unit): List<String> {
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
        for (f in files) form.addFormDataPart("files", f.name, UriBody(context, f))
        val body = CountingBody(form.build(), progress)
        val saved = post("/upload?to=" + Uri.encode(to), body, uploadClient).optJSONArray("saved") ?: JSONArray()
        return (0 until saved.length()).map { saved.getString(it) }
    }

    private class UriBody(private val context: Context, private val file: Outgoing) : RequestBody() {
        override fun contentType(): MediaType? =
            (file.mime ?: "application/octet-stream").toMediaTypeOrNull()

        override fun contentLength(): Long = file.size

        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(file.uri)
                ?: throw IOException("Can't read ${file.name}")
            input.source().use { sink.writeAll(it) }
        }
    }

    private class CountingBody(private val inner: RequestBody, private val progress: (Long, Long) -> Unit) : RequestBody() {
        override fun contentType() = inner.contentType()
        override fun contentLength() = inner.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            val counting = object : ForwardingSink(sink) {
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    progress(sent, total)
                }
            }.buffer()
            inner.writeTo(counting)
            counting.flush()
        }
    }
}

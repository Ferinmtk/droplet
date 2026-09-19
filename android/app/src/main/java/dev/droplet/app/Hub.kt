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
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

open class HubException(message: String) : IOException(message)

/** The hub answered `403 {"pair": true}`: this device hasn't been let in (or no longer is). */
class PairingRequired(message: String) : HubException(message)

/** No route to the hub: not on its Wi-Fi, and the tailnet is out of reach. */
class HubUnreachable(message: String) : HubException(message)

data class Device(val id: String, val name: String, val online: Boolean, val self: Boolean)

data class Ring(val id: String, val from: String, val ts: Double)

/** A file to upload: a content URI plus the name and size the sharing app reported. */
data class Outgoing(val uri: Uri, val name: String, val size: Long, val mime: String?)

/**
 * The droplet hub's HTTP API. Every call goes to the route [Router] picked
 * (the LAN with the pinned certificate, or the tailnet), with this device's
 * token as a bearer and the WebView's cookies for that origin (a PIN
 * session). A call that can't reach the hub asks the router again and is
 * retried once when that's safe.
 */
object Hub {
    const val DEVICE_COOKIE = "droplet_device"
    val userAgent = "droplet-android/${BuildConfig.VERSION_NAME}"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** "t15" style label for notifications and the share sheet. */
    fun hostLabel(): String = Router.hubLabel()

    /** Tidies what someone typed into a base URL, or null if it isn't one. */
    fun normalize(input: String): String? {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        val uri = Uri.parse(s)
        if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrEmpty()) return null
        return "${uri.scheme}://${uri.encodedAuthority}"
    }

    // --- the device token ----------------------------------------------------------

    private fun cookies(url: String): String? =
        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

    /** The `droplet_device` cookie the WebView holds for [origin], if any. */
    fun cookieToken(origin: String): String? =
        cookies(origin)?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$DEVICE_COOKIE=") }
            ?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    /** True once this phone is a device on the hub (it has a token, approved or not). */
    fun hasDevice(): Boolean = deviceToken() != null

    /**
     * This device's token. Kept in [Prefs]; an install from before 1.2 has it
     * only as the WebView cookie of the tailnet origin, and it's copied over
     * the first time it's needed.
     */
    fun deviceToken(): String? {
        Prefs.deviceToken?.let { return it }
        val found = listOfNotNull(Prefs.hubUrl, Router.current()?.base).firstNotNullOfOrNull { cookieToken(it) }
        if (found != null) Prefs.deviceToken = found
        return found
    }

    /** Makes [token] this device's identity everywhere: storage, and the WebView's cookie on every hub origin. */
    fun setToken(token: String?) {
        Prefs.deviceToken = token
        if (token != null) knownOrigins().forEach { installCookie(it) }
        runCatching { CookieManager.getInstance().flush() }
    }

    /** Forgets this device's token: storage, and the cookie on every hub origin (and any PIN session). */
    fun clearToken() {
        val origins = knownOrigins()
        Prefs.deviceToken = null
        runCatching {
            val cm = CookieManager.getInstance()
            // expire it origin by origin at once; removeAllCookies finishes later
            for (o in origins) {
                val secure = if (o.startsWith("https://")) "; Secure" else ""
                cm.setCookie(o, "$DEVICE_COOKIE=; Max-Age=0; Path=/; HttpOnly; SameSite=Lax$secure")
            }
            cm.removeAllCookies(null)
            cm.flush()
        }
    }

    /**
     * Gives the WebView the device cookie for [origin] before it loads there.
     * Cookies are per origin, so the LAN origin needs its own copy (docs/local-first.md §5).
     */
    fun installCookie(origin: String) {
        val token = deviceToken() ?: return
        if (cookieToken(origin) == token) return
        val secure = if (origin.startsWith("https://")) "; Secure" else ""
        runCatching {
            CookieManager.getInstance().setCookie(origin,
                "$DEVICE_COOKIE=$token; Max-Age=${5 * 365 * 24 * 3600}; Path=/; HttpOnly; SameSite=Lax$secure")
        }
    }

    /** Every origin the hub has been reached on: the tailnet URL, the LAN addresses, the current route. */
    fun knownOrigins(): List<String> =
        (listOfNotNull(Prefs.hubUrl, Router.current()?.base) + Prefs.lanAddresses.map { "https://$it" }).distinct()

    /** Whether [url] is on one of the hub's origins. */
    fun isHubUrl(url: Uri): Boolean {
        val origin = "${url.scheme}://${url.encodedAuthority}"
        return knownOrigins().any { it.equals(origin, ignoreCase = true) }
    }

    /** wss://host/ws for an https hub, ws:// for the emulator's plain-http one. */
    fun socketUrl(base: String): String =
        (if (base.startsWith("https://")) "wss://" + base.removePrefix("https://") else "ws://" + base.removePrefix("http://")) + "/ws"

    /**
     * The WebView's cookies for [url] (a PIN session), without the device
     * cookie: the bearer token says who this is, and a stale cookie must not
     * say otherwise.
     */
    fun sessionCookies(url: String): String? =
        cookies(url)?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("$DEVICE_COOKIE=") }
            ?.joinToString("; ")?.takeIf { it.isNotEmpty() }

    /** Cookies (the PIN session) and the device token, for a request to [url] on the hub. */
    fun authHeaders(url: String): List<Pair<String, String>> = buildList {
        sessionCookies(url)?.let { add("Cookie" to it) }
        deviceToken()?.let { add("Authorization" to "Bearer $it") }
    }

    fun request(route: Router.Route, path: String): Request.Builder {
        val url = route.base + path
        return Request.Builder().url(url).header("User-Agent", userAgent).apply {
            authHeaders(url).forEach { (k, v) -> header(k, v) }
        }
    }

    // --- calling it ------------------------------------------------------------------

    /**
     * Whether [e] means the request never reached the hub, so sending it
     * again can't do anything twice.
     */
    fun neverArrived(e: IOException): Boolean = when (e) {
        is ConnectException, is NoRouteToHostException, is UnknownHostException, is SSLHandshakeException -> true
        is SocketTimeoutException -> e.message?.contains("connect", ignoreCase = true) == true
        else -> Pinning.mismatch(e) != null || e.cause is ConnectException
    }

    /**
     * Runs a request on the current route. If the hub can't be reached there,
     * the router looks again and the request goes once more on what it finds:
     * always for [idempotent] requests, and for others only when the first
     * attempt provably never arrived.
     */
    fun <T> call(path: String, idempotent: Boolean, http: (Router.Route) -> OkHttpClient = Router::clientFor,
                 build: (Request.Builder) -> Request.Builder = { it }, handle: (Response) -> T): T {
        var route = Router.resolveBlocking() ?: throw unreachable()
        var attempt = 0
        while (true) {
            val req = build(request(route, path)).build()
            try {
                return http(route).newCall(req).execute().let(handle)
            } catch (e: IOException) {
                if (e is HubException || attempt++ > 0 || !(idempotent || neverArrived(e))) throw e
                route = Router.afterFailure(route) ?: throw unreachable()
            }
        }
    }

    private fun unreachable() = HubUnreachable(
        if (Prefs.hubUrl != null) "Not on ${Router.hubLabel()}'s Wi-Fi, and Tailscale is off"
        else "Not on ${Router.hubLabel()}'s Wi-Fi"
    )

    /** Parses a JSON answer; `403 {"pair": true}` becomes [PairingRequired]. */
    fun Response.json(): JSONObject {
        use {
            // hubs from before local-first: the PIN gate redirects anything without a session to the login page
            if (request.url.encodedPath == "/login") throw HubException("The hub wants its PIN: open droplet and log in")
            val text = body?.string().orEmpty()
            val parsed = runCatching { JSONObject(text) }.getOrNull()
            if (!isSuccessful) {
                if (code == 403 && parsed?.optBoolean("pair") == true) {
                    Router.pairingNeeded(true)
                    throw PairingRequired(parsed.optString("error").ifBlank { "This phone hasn't been let in yet" })
                }
                val err = parsed?.optString("error")
                throw HubException(err?.takeIf { it.isNotBlank() } ?: "The hub answered $code")
            }
            return parsed ?: throw HubException("The hub sent something unexpected")
        }
    }

    private fun get(path: String): JSONObject = call(path, idempotent = true) { it.json() }

    private fun post(path: String, body: RequestBody, idempotent: Boolean = false,
                     http: (Router.Route) -> OkHttpClient = Router::clientFor): JSONObject =
        call(path, idempotent, http, build = { it.post(body) }) { it.json() }

    fun postJson(path: String, json: JSONObject): JSONObject =
        post(path, json.toString().toRequestBody("application/json".toMediaType()))

    /**
     * Trades a six-digit link code (made on another browser's droplet page)
     * for that device's identity, which the page and the native parts then
     * share. Returns the device's name.
     */
    fun link(code: String): String {
        val body = JSONObject().put("code", code).put("client", "droplet-android on ${android.os.Build.MODEL}")
            .toString().toRequestBody("application/json".toMediaType())
        // no Authorization here: the code alone says which device this becomes
        val res = call("/api/device/link", idempotent = false, build = { it.removeHeader("Authorization").post(body) }) { it.json() }
        val token = res.optString("token").takeIf { it.isNotEmpty() } ?: throw HubException("The hub sent no token")
        setToken(token)
        Router.pairingNeeded(false)
        return res.optString("name", "that device")
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
        // stopping twice is harmless
        post("/api/ring/stop", ByteArray(0).toRequestBody(null), idempotent = true)
    }

    /** Rings another device through the hub (which also reaches a closed app by push), or stops it. */
    fun ringDevice(id: String, stop: Boolean) {
        post("/api/device/" + Uri.encode(id) + "/ring" + (if (stop) "/stop" else ""),
            "{}".toRequestBody("application/json".toMediaType()), idempotent = stop)
    }

    /** POST /api/mesh/announce: this phone's mesh identity, for the hub's roster (docs/mesh.md §9.6). */
    fun meshAnnounce(body: JSONObject): JSONObject = postJson("/api/mesh/announce", body)

    /** GET /api/mesh/roster: every other approved device's mesh identity. */
    fun meshRoster(): JSONObject = get("/api/mesh/roster")

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
        // uploads can be big; after the last byte the hub still has to write the file
        val saved = post("/upload?to=" + Uri.encode(to), body,
            http = { Router.clientFor(it).newBuilder().readTimeout(5, TimeUnit.MINUTES).build() })
            .optJSONArray("saved") ?: JSONArray()
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

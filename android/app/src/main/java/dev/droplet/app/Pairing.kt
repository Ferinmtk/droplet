package dev.droplet.app

import android.webkit.CookieManager
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * Getting this phone let in to a hub (docs/local-first.md §4), on a route
 * that setup is still deciding on. Blocking calls: run them off the main thread.
 */
object Pairing {
    /** Where this device stands with the hub. */
    sealed class Standing {
        /** Let in: the app can be used. */
        data class In(val name: String) : Standing()
        /** Asked to join; waiting for one of the owner's devices to allow it. */
        data class Waiting(val id: String, val name: String, val code: String) : Standing()
        /** Not a device here: the join request was denied or expired, or it never asked. */
        data class Out(val trusted: Boolean, val pin: Boolean, val suggested: String?) : Standing()
    }

    private fun req(route: Router.Route, path: String, token: String?): Request.Builder =
        Request.Builder().url(route.base + path).header("User-Agent", Hub.userAgent).apply {
            token?.let { header("Authorization", "Bearer $it") }
            Hub.sessionCookies(route.base)?.let { header("Cookie", it) }
        }

    private fun Response.jsonBody(): JSONObject = use {
        val text = body?.string().orEmpty()
        val j = runCatching { JSONObject(text) }.getOrNull()
        if (!isSuccessful) throw HubException(j?.optString("error")?.takeIf { it.isNotBlank() } ?: "The hub answered $code")
        j ?: throw HubException("That doesn't look like a droplet hub")
    }

    /** GET /api/hub/info. Null when the hub predates it (404); throws when unreachable. */
    fun info(route: Router.Route): HubInfo? {
        Router.clientFor(route).newCall(req(route, "/api/hub/info", null).build()).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw HubException("The hub answered ${r.code}")
            return HubInfo.parse(r.body?.string().orEmpty()) ?: throw HubException("That doesn't look like a droplet hub")
        }
    }

    /** GET /api/me with [token]: where this device stands. */
    fun standing(route: Router.Route, token: String?): Standing {
        val me = Router.clientFor(route).newCall(req(route, "/api/me", token).build()).execute().jsonBody()
        val d = me.optJSONObject("device")
        return when {
            d == null -> Standing.Out(me.optBoolean("trusted"), me.optBoolean("pin"), me.optString("suggested").takeIf { !me.isNull("suggested") && it.isNotBlank() })
            d.optBoolean("pending") -> Standing.Waiting(d.optString("id"), d.optString("name"), d.optString("code"))
            else -> Standing.In(d.optString("name"))
        }
    }

    /**
     * POST /api/device {name}: asks to join (or, on a trusted route, joins
     * outright). Returns the new standing and the device token.
     */
    fun join(route: Router.Route, name: String): Pair<Standing, String> {
        val body = JSONObject().put("name", name).toString().toRequestBody("application/json".toMediaType())
        // no token: a device that's gone asks afresh
        val res = Router.clientFor(route).newCall(req(route, "/api/device", null).post(body).build()).execute()
        val token = res.headers("Set-Cookie").firstOrNull { it.startsWith("${Hub.DEVICE_COOKIE}=") }
            ?.substringAfter('=')?.substringBefore(';')?.takeIf { it.isNotEmpty() }
        val j = res.jsonBody()
        token ?: throw HubException("The hub sent no device token")
        val standing = if (j.optBoolean("pending")) Standing.Waiting(j.optString("id"), j.optString("name", name), j.optString("code"))
        else Standing.In(j.optString("name", name))
        return standing to token
    }

    /**
     * POST /login with the hub's PIN. The PIN lets this device in, and the
     * session cookie is kept for the WebView on this origin. False if the PIN is wrong.
     */
    fun login(route: Router.Route, pin: String, token: String?): Boolean {
        val http = Router.clientFor(route).newBuilder().followRedirects(false).followSslRedirects(false).build()
        http.newCall(req(route, "/login", token).post(FormBody.Builder().add("pin", pin).build()).build()).execute().use { r ->
            // right: a redirect to the app; wrong: the login page again, with an error
            if (r.code !in 300..399) return false
            val cm = CookieManager.getInstance()
            r.headers("Set-Cookie").forEach { runCatching { cm.setCookie(route.base, it) } }
            runCatching { cm.flush() }
            return true
        }
    }

    /** POST /api/device/link: become the device that made [code]. Returns (name, token). */
    fun link(route: Router.Route, code: String): Pair<String, String> {
        val body = JSONObject().put("code", code).put("client", "droplet-android on ${android.os.Build.MODEL}")
            .toString().toRequestBody("application/json".toMediaType())
        val j = Router.clientFor(route).newCall(req(route, "/api/device/link", null).post(body).build()).execute().jsonBody()
        val token = j.optString("token").takeIf { it.isNotEmpty() } ?: throw HubException("The hub sent no token")
        return j.optString("name", "that device") to token
    }

    /** Withdraws a join request this phone no longer needs (it linked or logged in another way). */
    fun withdraw(route: Router.Route, id: String, token: String) {
        try {
            Router.clientFor(route).newCall(req(route, "/api/device/$id/remove", token)
                .post(ByteArray(0).toRequestBody(null)).build()).execute().close()
        } catch (e: IOException) {
            // it expires by itself within a day
        }
    }
}

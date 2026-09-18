package dev.droplet.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * What other devices ask of this phone (docs/remote.md), the same whichever
 * way it arrives: over the hub's live connection ([Live]) or over a direct
 * mesh link ([Mesh]). Only the way a file goes back differs.
 */
object Rpc {
    private const val BUDGET_MS = 28_000L  // the hub gives up after 30 s

    /** Answers one `rpc`: the `rpc-result` to send back. */
    suspend fun answer(app: Context, msg: JSONObject, caps: Set<String>, sender: FileBridge.Sender?): JSONObject {
        val id = msg.optString("id")
        val method = msg.optString("method")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val from = msg.optJSONObject("from")
        val reply = JSONObject().put("t", "rpc-result").put("id", id)
        val wake = app.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droplet:rpc").apply { acquire(BUDGET_MS + 2_000) }
        try {
            val result = withTimeoutOrNull(BUDGET_MS) {
                when (method.substringBefore('.')) {
                    "files" -> if ("files" in caps) FileBridge.call(app, method, params, from, sender)
                        else throw RpcError(app.getString(R.string.live_err_off, app.getString(R.string.cap_files)))
                    "sms" -> if ("sms" in caps) SmsBridge.call(app, method, params)
                        else throw RpcError(app.getString(R.string.live_err_off, app.getString(R.string.cap_sms)))
                    else -> throw RpcError("The phone doesn't know $method")
                }
            } ?: throw RpcError(app.getString(R.string.live_err_slow))
            reply.put("result", result)
        } catch (e: RpcError) {
            reply.put("error", e.message)
        } catch (e: SecurityException) {
            reply.put("error", app.getString(R.string.live_err_permission))
        } catch (e: Exception) {
            reply.put("error", e.message ?: e.javaClass.simpleName)
        } finally {
            if (wake.isHeld) wake.release()
        }
        return reply
    }
}

/**
 * This phone's latest `state` (media, battery), sent to the hub and to every
 * direct link, and kept so a link that opens later gets it at once.
 */
object States {
    val last = ConcurrentHashMap<String, Any>()

    /** True if it went anywhere. */
    fun publish(kind: String, data: JSONObject?): Boolean {
        if (data != null) last[kind] = data else last.remove(kind)
        val hub = Live.sendState(kind, data)
        val mesh = Mesh.broadcast(JSONObject().put("t", "state").put("kind", kind).put("data", data ?: JSONObject.NULL))
        return hub || mesh
    }
}

/**
 * Keeps the media bridge running while anyone can see it: the hub
 * connection, or at least one direct link.
 */
@SuppressLint("StaticFieldLeak")  // the application context
object Presence {
    private val holders = mutableSetOf<String>()
    private var running = false
    private var app: Context? = null

    @Synchronized
    fun hold(context: Context, tag: String) {
        app = context.applicationContext
        if (holders.add(tag)) update()
    }

    @Synchronized
    fun release(tag: String) {
        if (holders.remove(tag)) update()
    }

    /** What this phone offers changed (a switch, a permission). */
    @Synchronized
    fun update() {
        val ctx = app ?: return
        val want = holders.isNotEmpty() && "media" in Caps.current(ctx)
        if (want && !running) MediaBridge.start(ctx)
        if (!want && running) {
            MediaBridge.stop()
            States.last.remove("media")
        }
        running = want
    }
}

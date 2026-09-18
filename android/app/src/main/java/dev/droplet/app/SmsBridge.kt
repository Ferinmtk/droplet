package dev.droplet.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The phone's text messages for other devices (docs/remote.md §3.4, `sms.*`):
 * threads and messages from the Telephony provider, and sending through
 * SmsManager on the default SIM.
 *
 * SMS only; MMS (group chats, pictures) isn't read. droplet isn't the default
 * SMS app, so Android itself files what it sends in the Sent box.
 */
object SmsBridge {
    private const val SCAN_LIMIT = 20_000     // rows looked at for the thread list
    private const val REPLY_BUDGET = 400_000  // characters, well under the hub's 512 KB frame cap
    private const val SEND_WAIT_MS = 25_000L

    suspend fun call(context: Context, method: String, params: JSONObject): JSONObject = when (method) {
        "sms.threads" -> threads(context, params.optInt("limit", 50).coerceIn(1, 500))
        "sms.thread" -> thread(context, params.opt("id")?.toString().orEmpty(), params.optInt("limit", 100).coerceIn(1, 1000))
        "sms.send" -> send(context, params.optString("address"), params.optString("body"))
        else -> throw RpcError("The phone doesn't know $method")
    }

    private class Thread(val id: String, val address: String, val snippet: String, val ts: Double, var unread: Int)

    private fun threads(context: Context, limit: Int): JSONObject {
        val found = LinkedHashMap<String, Thread>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                Telephony.Sms.TYPE, Telephony.Sms.READ),
            "${Telephony.Sms.TYPE} != ?", arrayOf(Telephony.Sms.MESSAGE_TYPE_DRAFT.toString()),
            "${Telephony.Sms.DATE} DESC",
        ) ?: throw RpcError(context.getString(R.string.sms_err_provider))
        cursor.use { c ->
            var rows = 0
            while (c.moveToNext() && rows++ < SCAN_LIMIT) {
                val id = c.getLong(0).toString()
                val incoming = c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX
                val unread = if (incoming && c.getInt(5) == 0) 1 else 0
                val t = found[id]
                if (t != null) {
                    t.unread += unread
                } else {
                    // rows come newest first, so the first one seen is the thread's latest
                    found[id] = Thread(id, c.getString(1).orEmpty(), c.getString(2).orEmpty().take(160),
                        c.getLong(3) / 1000.0, unread)
                }
            }
        }
        val names = Names(context)
        val out = JSONArray()
        found.values.take(limit).forEach { t ->
            out.put(JSONObject()
                .put("id", t.id)
                .put("address", t.address)
                .put("name", names[t.address] ?: JSONObject.NULL)
                .put("snippet", t.snippet)
                .put("ts", t.ts)
                .put("unread", t.unread))
        }
        return JSONObject().put("threads", out)
    }

    private fun thread(context: Context, id: String, limit: Int): JSONObject {
        if (id.isEmpty() || id.any { !it.isDigit() }) throw RpcError("No such conversation")
        val messages = ArrayList<JSONObject>()
        var budget = REPLY_BUDGET
        val cursor = context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
            "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} != ?",
            arrayOf(id, Telephony.Sms.MESSAGE_TYPE_DRAFT.toString()),
            "${Telephony.Sms.DATE} DESC",
        ) ?: throw RpcError(context.getString(R.string.sms_err_provider))
        cursor.use { c ->
            while (c.moveToNext() && messages.size < limit) {
                val body = c.getString(1).orEmpty()
                budget -= body.length + 80
                if (budget < 0) break  // the oldest ones are left out rather than overflow the frame
                val type = c.getInt(3)
                messages += JSONObject()
                    .put("id", c.getLong(0).toString())
                    .put("body", body)
                    .put("ts", c.getLong(2) / 1000.0)
                    .put("out", type != Telephony.Sms.MESSAGE_TYPE_INBOX)
                    .apply { if (type == Telephony.Sms.MESSAGE_TYPE_FAILED) put("failed", true) }
            }
        }
        return JSONObject().put("messages", JSONArray(messages.asReversed()))
    }

    private suspend fun send(context: Context, address: String, body: String): JSONObject {
        val to = address.trim()
        // a phone number or a short code; anything else is a mistake upstream
        if (to.isEmpty() || to.length > 40 || !to.all { it.isLetterOrDigit() || it in "+*#-() ." })
            throw RpcError(context.getString(R.string.sms_err_address))
        if (body.isBlank()) throw RpcError(context.getString(R.string.sms_err_empty))
        if (body.length > 5_000) throw RpcError(context.getString(R.string.sms_err_long))

        val sms = manager(context)
        val parts = sms.divideMessage(body)
        val action = "dev.droplet.app.SMS_SENT." + UUID.randomUUID()
        val results = Channel<Int>(Channel.UNLIMITED)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results.trySend(resultCode)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val sent = ArrayList(parts.indices.map { i ->
                PendingIntent.getBroadcast(context, i, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
            })
            try {
                if (parts.size == 1) sms.sendTextMessage(to, null, body, sent[0], null)
                else sms.sendMultipartTextMessage(to, null, parts, sent, null)
            } catch (e: SecurityException) {
                throw RpcError(context.getString(R.string.sms_err_blocked))
            } catch (e: IllegalArgumentException) {
                throw RpcError(context.getString(R.string.sms_err_address))
            }
            // each part reports back once the radio has handed it over
            val codes = withTimeoutOrNull(SEND_WAIT_MS) { List(parts.size) { results.receive() } }
                ?: throw RpcError(context.getString(R.string.sms_err_timeout))
            val failed = codes.firstOrNull { it != Activity.RESULT_OK } ?: return JSONObject().put("ok", true)
            throw RpcError(when (failed) {
                SmsManager.RESULT_ERROR_RADIO_OFF -> context.getString(R.string.sms_err_radio)
                SmsManager.RESULT_ERROR_NO_SERVICE -> context.getString(R.string.sms_err_service)
                else -> context.getString(R.string.sms_err_failed, failed)
            })
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** The default SMS SIM's manager (multi-SIM phones), or the system default. */
    private fun manager(context: Context): SmsManager {
        val sub = SmsManager.getDefaultSmsSubscriptionId()
        return if (Build.VERSION.SDK_INT >= 31) {
            val base = context.getSystemService(SmsManager::class.java)
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(sub) else base
        } else {
            @Suppress("DEPRECATION")
            if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) SmsManager.getSmsManagerForSubscriptionId(sub)
            else SmsManager.getDefault()
        }
    }

    /** Contact names for addresses, when READ_CONTACTS is granted; looked up once per request. */
    private class Names(private val context: Context) {
        private val allowed = Caps.contactsAllowed(context)
        private val cache = HashMap<String, String?>()

        operator fun get(address: String): String? {
            if (!allowed || address.isBlank()) return null
            return cache.getOrPut(address) {
                runCatching {
                    context.contentResolver.query(
                        Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                        arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
                    )?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull()
            }
        }
    }
}

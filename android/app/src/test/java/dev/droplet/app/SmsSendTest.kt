package dev.droplet.app

import android.Manifest
import android.app.Activity
import android.app.Application
import android.os.Looper
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `sms.send`: long messages go out in parts, and the answer waits for the
 * radio's report for every part. Runs on SDK 30, where Robolectric's
 * SmsManager can split messages without a telephony service.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SmsSendTest {
    private lateinit var app: Application
    @Suppress("DEPRECATION")
    private val sms get() = SmsManager.getSmsManagerForSubscriptionId(1)

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // the last one is androidx's signature permission for NOT_EXPORTED receivers before Android 13,
        // granted at install on a real phone
        shadowOf(app).grantPermissions(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS,
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        // a phone with one SIM, set as the default for SMS
        org.robolectric.shadows.ShadowSmsManager.setDefaultSmsSubscriptionId(1)
    }

    /** Runs sms.send in the background, lets [radio] answer, and returns the result or the error. */
    private fun send(address: String, body: String, radio: () -> Unit): Result<JSONObject> = runBlocking {
        val call = async(Dispatchers.IO) {
            runCatching { SmsBridge.call(app, "sms.send", JSONObject().put("address", address).put("body", body)) }
        }
        val end = System.currentTimeMillis() + 10_000
        while (shadowOf(sms).lastSentTextMessageParams == null && shadowOf(sms).lastSentMultipartTextMessageParams == null) {
            if (call.isCompleted || System.currentTimeMillis() > end) break
            Thread.sleep(20)
        }
        radio()
        shadowOf(Looper.getMainLooper()).idle()  // deliver the "sent" broadcasts
        call.await()
    }

    @Test
    fun longMessageIsSentInPartsAndConfirmed() {
        val body = "word ".repeat(80).trim()
        val result = send("+254700000001", body) {
            val p = shadowOf(sms).lastSentMultipartTextMessageParams ?: fail("not sent as multipart") as Nothing
            assertEquals("+254700000001", p.destinationAddress)
            assertTrue(p.parts.size > 1)
            assertEquals(body, p.parts.joinToString(""))
            p.sentIntents.forEach { it.send(app, Activity.RESULT_OK, null) }
        }
        assertTrue(result.getOrThrow().getBoolean("ok"))
    }

    @Test
    fun radioFailureIsReported() {
        val result = send("+254700000001", "short") {
            val p = shadowOf(sms).lastSentTextMessageParams ?: fail("not sent") as Nothing
            assertEquals("short", p.text)
            p.sentIntent.send(app, SmsManager.RESULT_ERROR_NO_SERVICE, null)
        }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("signal"))
    }

    @Test
    fun nonsenseAddressIsRefusedBeforeSending() {
        val result = send("rm -rf /", "x") {}
        assertTrue(result.exceptionOrNull() is RpcError)
        assertEquals(null, shadowOf(sms).lastSentTextMessageParams)
    }
}

package dev.droplet.app

import android.app.Application
import android.net.ConnectivityManager
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/**
 * docs/local-first.md against real hubs: the app's own code (the pinned
 * client, the route manager, setup and pairing, the live connection) on
 * Robolectric, talking to droplet hubs over this machine's LAN address.
 *
 * Requests from 127.0.0.1 count as the hub machine itself (trusted), so the
 * test plays the owner that way. The app connects to the LAN address, where
 * it's a stranger that must be let in, exactly like a phone on the Wi-Fi.
 *
 *   DROPLET_HOME=$(mktemp -d) DROPLET_PORT=8831 DROPLET_LAN_TLS_PORT=8832 DROPLET_PUSH=0 python app.py
 *   DROPLET_HOME=$(mktemp -d) DROPLET_PORT=8981 DROPLET_LAN_TLS_PORT=8982 DROPLET_PUSH=0 DROPLET_PIN=2468 python app.py
 *   # the "clone": the first hub's id, its own certificate (a regenerated one, or an impostor)
 *   mkdir clone; cp <first home>/.hub_id clone/
 *   DROPLET_HOME=clone DROPLET_PORT=8985 DROPLET_LAN_TLS_PORT=8986 DROPLET_PUSH=0 python app.py
 *   DROPLET_TEST_HUB=http://127.0.0.1:8831 DROPLET_TEST_PIN_HUB=http://127.0.0.1:8981 DROPLET_TEST_PIN=2468 \
 *     DROPLET_TEST_CLONE_HUB=http://127.0.0.1:8985 ./gradlew testReleaseUnitTest
 *
 * mDNS itself (NsdManager) can't run here: discovery is replaced by the
 * announcement the hub makes, and the TXT parsing is covered by LocalFirstUnitTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFirstHubTest {
    private val hub = System.getProperty("droplet.testHub").orEmpty()
    private val pinHub = System.getProperty("droplet.testPinHub").orEmpty()
    private val pinCode = System.getProperty("droplet.testPin").orEmpty()
    private val cloneHub = System.getProperty("droplet.testCloneHub").orEmpty()
    private val http = OkHttpClient.Builder().readTimeout(20, TimeUnit.SECONDS).build()
    private lateinit var app: Application
    private lateinit var info: HubInfo
    private var openssl: Process? = null
    private val defaultDiscover = Router.discover

    @Before
    fun setUp() {
        assumeTrue("set DROPLET_TEST_HUB to run the local-first tests", hub.isNotEmpty())
        app = ApplicationProvider.getApplicationContext()
        info = hubInfo(hub)
        assertNotNull("the test hub needs its LAN listener", info.fingerprint)
        assertTrue("the test hub must report a LAN address", info.lan.isNotEmpty())
        clean()
        // an ordinary connected network (Robolectric's default has no INTERNET capability)
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val nc = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(nc).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(nc).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, nc)
        Router.discover = { _, _ -> emptyList() }
        Router.lanTimeoutMs = 1_500
        Router.discoveryMs = 300
    }

    @After
    fun tearDown() {
        if (hub.isEmpty()) return
        Live.release("test")
        Router.release("test")
        Router.discover = defaultDiscover
        Router.lanReachable = { true }
        Router.lanRecheckMs = 3 * 60_000L
        SetupActivity.browser = null
        openssl?.destroy()
        // join requests left open would hit the hub's cap of five
        for (h in listOf(hub, pinHub).filter { it.isNotEmpty() }) {
            runCatching { pending(h).forEach { owner(h, "/api/device/${it.getString("id")}/remove") } }
        }
        clean()
    }

    private fun clean() {
        Hub.clearToken()
        Prefs.forgetHub()
        Router.reset()
    }

    // --- helpers ---------------------------------------------------------------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))

    private fun waitFor(what: String, timeoutMs: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            idle()
            if (cond()) return
            Thread.sleep(50)
        }
        throw AssertionError("timed out waiting for $what; route: ${Router.state.value}; live: ${Live.state.value}")
    }

    private fun hubInfo(base: String): HubInfo =
        HubInfo.parse(http.newCall(Request.Builder().url("$base/api/hub/info").build()).execute().body!!.string())!!

    private val lan get() = info.lan.first()               // "192.168.100.13:8832"
    private val lanHost get() = lan.substringBeforeLast(':')
    private val lanPort get() = lan.substringAfterLast(':').toInt()
    private val lanRoute get() = Router.Route(Router.Kind.LAN, "https://$lan", info.fingerprint)
    private val announced get() = Announced(info.id, info.fingerprint!!, info.name ?: "hub", lanHost, lanPort, null, null)

    /** A device named by the owner on the hub machine itself (trusted, so let in at once). */
    private fun register(base: String, name: String): Pair<String, String> {
        val res = http.newCall(Request.Builder().url("$base/api/device")
            .post(JSONObject().put("name", name).toString().toRequestBody("application/json".toMediaType())).build()).execute()
        val token = res.headers("Set-Cookie").first { it.startsWith("droplet_device=") }.substringAfter('=').substringBefore(';')
        return JSONObject(res.body!!.string()).getString("id") to token
    }

    /** The owner answering a join request, from the hub machine. */
    private fun owner(base: String, path: String): Int =
        http.newCall(Request.Builder().url(base + path).post(ByteArray(0).toRequestBody()).build()).execute().use { it.code }

    private fun pending(base: String): List<JSONObject> {
        val arr = JSONObject(http.newCall(Request.Builder().url("$base/api/pair").build()).execute().body!!.string()).getJSONArray("pending")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun unique(prefix: String) = "$prefix-${System.nanoTime() % 1_000_000}"

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** An unrelated TLS server with its own self-signed certificate: (port, fingerprint). */
    private fun otherTlsServer(): Pair<Int, String> {
        val dir = File(System.getProperty("java.io.tmpdir"), "droplet-lf-${System.nanoTime()}").apply { mkdirs() }
        val gen = ProcessBuilder("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
            "-keyout", "$dir/k.pem", "-out", "$dir/c.pem", "-days", "2", "-subj", "/CN=not-droplet")
            .redirectErrorStream(true).start()
        assertTrue(gen.waitFor(30, TimeUnit.SECONDS) && gen.exitValue() == 0)
        val fp = File("$dir/c.pem").inputStream().use { Pinning.fingerprint(CertificateFactory.getInstance("X.509").generateCertificate(it)) }
        val port = freePort()
        openssl = ProcessBuilder("openssl", "s_server", "-accept", "$port", "-cert", "$dir/c.pem", "-key", "$dir/k.pem", "-www", "-quiet")
            .redirectErrorStream(true).redirectOutput(File("$dir/log")).start()
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            if (runCatching { Socket("127.0.0.1", port).close() }.isSuccess) return port to fp
            Thread.sleep(100)
        }
        fail("openssl s_server didn't start")
        throw IllegalStateException()
    }

    private fun get(client: OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            assertTrue("$url answered ${r.code}", r.isSuccessful)
            r.body!!.string()
        }

    private fun expectMismatch(client: OkHttpClient, url: String): Pinning.Mismatch {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().close()
        } catch (e: IOException) {
            return Pinning.mismatch(e) ?: throw AssertionError("failed, but not on the pin: $e", e)
        }
        throw AssertionError("$url was accepted")
    }

    // --- 1. the pinned client ----------------------------------------------------------

    @Test
    fun pinnedClientTrustsOnlyTheHubsCertificate() {
        val fp = info.fingerprint!!
        val pinned = Pinning.client(fp)
        // the hub, by its LAN address and by another name for the same machine: the name doesn't matter, the certificate does
        assertEquals(info.id, HubInfo.parse(get(pinned, "https://$lan/api/hub/info"))!!.id)
        assertEquals(info.id, HubInfo.parse(get(pinned, "https://127.0.0.1:$lanPort/api/hub/info"))!!.id)

        // the ordinary client (the tailnet's) refuses the self-signed certificate
        try {
            Hub.client.newCall(Request.Builder().url("https://$lan/api/hub/info").build()).execute().close()
            fail("the verified client accepted a self-signed certificate")
        } catch (e: SSLHandshakeException) {
            assertNull("refused by ordinary verification, not by a pin", Pinning.mismatch(e))
        }

        // a different self-signed certificate is refused, and the one it showed is reported
        val (port, otherFp) = otherTlsServer()
        assertEquals(otherFp, expectMismatch(pinned, "https://127.0.0.1:$port/").seen)
        // (it is a working TLS server: pinned to its own certificate, it answers)
        assertTrue(get(Pinning.client(otherFp), "https://127.0.0.1:$port/").isNotEmpty())
        // and pinned to that one, the hub is refused
        assertEquals(fp, expectMismatch(Pinning.client(otherFp), "https://$lan/api/hub/info").seen)

        // reading a certificate without trusting it (first use of a typed address)
        assertEquals(fp, Pinning.capture(lanHost, lanPort))
        assertEquals(otherFp, Pinning.capture("127.0.0.1", port))
        assertNull(Pinning.capture("127.0.0.1", freePort()))

        // a hub with our id but another certificate (a regenerated one, or an impostor) is refused too
        if (cloneHub.isNotEmpty()) {
            val clone = hubInfo(cloneHub)
            assertEquals(info.id, clone.id)
            assertEquals(clone.fingerprint, expectMismatch(pinned, "https://${clone.lan.first()}/api/hub/info").seen)
        }
    }

    // --- 2. the route manager ----------------------------------------------------------

    private fun pairedWith(tailnet: String?) {
        Prefs.hubId = info.id
        Prefs.hubFingerprint = info.fingerprint
        Prefs.pinSource = Prefs.PIN_TAILNET
        Prefs.hubUrl = tailnet
    }

    @Test
    fun routeManagerPrefersTheLanFallsBackAndReturns() {
        // the plain-http port stands in for the tailnet URL: a different route to the same hub
        pairedWith(tailnet = hub)
        Prefs.lanAddresses = listOf(lan)
        val first = runBlocking { Router.resolve(force = true) }
        assertEquals(lanRoute, first)
        assertEquals(lanRoute, Router.state.value.route)

        // the stored address no longer answers, but the hub announces where it is now
        Prefs.lanAddresses = listOf("127.0.0.1:1")
        Router.discover = { _, enough -> listOf(announced).also { l -> assertTrue(l.any(enough)) } }
        assertEquals(lanRoute, runBlocking { Router.resolve(force = true) })
        assertEquals("the address that worked goes first", lan, Prefs.lanAddresses.first())

        // away from home: no LAN address reachable, so the tailnet
        Router.lanReachable = { false }
        val away = runBlocking { Router.resolve(force = true) }
        assertEquals(Router.Route(Router.Kind.TAILNET, hub), away)
        assertEquals("Via 127.0.0.1", Router.describe(app))
        // requests go there too
        assertTrue(Hub.me().has("device"))

        // home again: joining the Wi-Fi brings it back to the LAN
        Router.hold("test")
        Router.lanReachable = { true }
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callbacks = shadowOf(cm).networkCallbacks
        assertTrue("the router watches the network while held", callbacks.isNotEmpty())
        callbacks.forEach { it.onAvailable(cm.activeNetwork!!) }
        waitFor("the LAN after joining the Wi-Fi") { Router.current() == lanRoute }
        assertTrue(Router.describe(app).startsWith("On Wi-Fi · ${info.name} · $lanHost"))

        // and without any network event, the periodic look finds it again
        Router.release("test")
        Router.lanReachable = { false }
        assertEquals(Router.Kind.TAILNET, runBlocking { Router.resolve(force = true) }?.kind)
        Router.lanRecheckMs = 400
        Router.hold("test")
        Router.lanReachable = { true }
        waitFor("the LAN from the periodic look") { Router.current() == lanRoute }

        // nothing reachable at all
        Router.release("test")
        Router.lanReachable = { false }
        Prefs.hubUrl = "http://127.0.0.1:1"
        assertNull(runBlocking { Router.resolve(force = true) })
        assertTrue(Router.state.value.unreachable)
        try {
            Hub.me()
            fail("a request with no route must fail")
        } catch (e: HubUnreachable) {
            assertTrue(e.message!!.contains("Wi-Fi"))
        }
    }

    @Test
    fun aChangedCertificateIsNeverUsedSilently() {
        assumeTrue("set DROPLET_TEST_CLONE_HUB", cloneHub.isNotEmpty())
        val clone = hubInfo(cloneHub)
        val cloneLan = clone.lan.first()
        pairedWith(tailnet = null)

        // the last address now shows another certificate, and says it's our hub
        Prefs.lanAddresses = listOf(cloneLan)
        assertNull(runBlocking { Router.resolve(force = true) })
        assertEquals(Router.IdentityChange(cloneLan, clone.fingerprint!!), Router.state.value.identityChanged)
        assertEquals("the pin is kept", info.fingerprint, Prefs.hubFingerprint)

        // mDNS announces our id with another fingerprint
        Prefs.lanAddresses = emptyList()
        Router.discover = { _, _ -> listOf(announced.copy(fingerprint = clone.fingerprint, port = cloneLan.substringAfterLast(':').toInt())) }
        assertNull(runBlocking { Router.resolve(force = true) })
        assertEquals(clone.fingerprint, Router.state.value.identityChanged?.seen)

        // the (verified) tailnet says the hub's certificate changed: the tailnet still works, the LAN stays off
        Router.discover = { _, _ -> emptyList() }
        Prefs.hubUrl = cloneHub
        val r = runBlocking { Router.resolve(force = true) }
        assertEquals(Router.Kind.TAILNET, r?.kind)
        assertNotNull(Router.state.value.identityChanged)
        assertEquals(info.fingerprint, Prefs.hubFingerprint)

        // the real hub back at its address: fine again, and the warning goes
        Prefs.hubUrl = null
        Prefs.lanAddresses = listOf(lan)
        assertEquals(lanRoute, runBlocking { Router.resolve(force = true) })
        assertNull(Router.state.value.identityChanged)
    }

    // --- 3. pairing on the LAN, and the live connection over pinned TLS ------------------

    private fun startSetup(vararg hubs: Announced): org.robolectric.android.controller.ActivityController<SetupActivity> {
        SetupActivity.browser = { _, onChange, _ -> onChange(hubs.toList()); Discovery.Handle { } }
        val c = Robolectric.buildActivity(SetupActivity::class.java).setup()
        // a first run asks "with a hub, or without?" first: with one
        if (c.get().visible(R.id.panel_choose)) c.get().findViewById<View>(R.id.choose_hub).performClick()
        return c
    }

    private fun SetupActivity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    /** Setup up to the code screen: pick the discovered hub, name the phone, ask to join. */
    private fun askToJoin(setup: SetupActivity, name: String): String {
        waitFor("the discovered hub listed") { setup.findViewById<LinearLayout>(R.id.hubs).childCount == 1 }
        assertEquals(info.name, setup.findViewById<LinearLayout>(R.id.hubs).getChildAt(0).findViewById<TextView>(R.id.hub_name).text.toString())
        setup.findViewById<LinearLayout>(R.id.hubs).getChildAt(0).performClick()
        waitFor("the name step") { setup.visible(R.id.panel_name) }
        setup.findViewById<TextView>(R.id.name).text = name
        setup.findViewById<View>(R.id.join).performClick()
        waitFor("the code") {
            val err = setup.findViewById<TextView>(R.id.name_error)
            if (err.visibility == View.VISIBLE) fail("joining failed: ${err.text}")
            setup.visible(R.id.panel_code)
        }
        return setup.findViewById<TextView>(R.id.code).text.toString().filter { it.isDigit() }
    }

    @Test
    fun pairsOnTheLanAndConnectsLive() {
        val name = unique("robo-lan")
        val setup = startSetup(announced).get()
        assertTrue(setup.visible(R.id.panel_find))
        val code = askToJoin(setup, name)
        assertEquals(4, code.length)
        assertEquals("the hub has the key for our pin, trusted on first use", Prefs.PIN_TOFU, Prefs.pinSource)
        assertTrue(setup.findViewById<TextView>(R.id.code_help).text.contains(name))

        // the owner sees the same code on another device, and allows it
        val request = pending(hub).first { it.getString("name") == name }
        assertEquals(code, request.getString("code"))
        idle()
        assertTrue("still waiting until the owner answers", setup.visible(R.id.panel_code))
        assertEquals(200, owner(hub, "/api/device/${request.getString("id")}/approve"))

        waitFor("setup to finish") { setup.isFinishing }
        assertEquals(MainActivity::class.java.name, shadowOf(setup).nextStartedActivity.component?.className)
        assertEquals(lanRoute, Router.current())
        assertEquals(info.id, Prefs.hubId)
        assertEquals(info.fingerprint, Prefs.hubFingerprint)
        assertEquals(listOf(lan), Prefs.lanAddresses.take(1))
        assertNotNull(Prefs.deviceToken)
        // the WebView gets the device cookie on the LAN origin
        assertEquals(Prefs.deviceToken, Hub.cookieToken(lanRoute.base))
        val me = Hub.me().getJSONObject("device")
        assertEquals(name, me.getString("name"))
        assertFalse(me.optBoolean("pending"))
        assertTrue(Hub.files().has("devices"))

        // the live connection: wss:// to the LAN address, pinned
        Live.hold("test")
        waitFor("live over the LAN") { Live.state.value.connected }
        assertEquals(lanRoute.base, Live.connectedBase())
        assertEquals(me.getString("id"), Live.state.value.deviceId)

        // the route moves to the tailnet (here: the plain port), and the socket follows
        val away = Router.Route(Router.Kind.TAILNET, hub)
        Router.use(away)
        waitFor("live over the tailnet") { Live.state.value.connected && Live.connectedBase() == hub }
        Router.use(lanRoute)
        waitFor("live back on the LAN") { Live.state.value.connected && Live.connectedBase() == lanRoute.base }
    }

    @Test
    fun aDeniedJoinSaysSo() {
        val name = unique("robo-deny")
        val setup = startSetup(announced).get()
        askToJoin(setup, name)
        val request = pending(hub).first { it.getString("name") == name }

        // until let in, the API answers 403 {"pair": true}, which the app treats as "pair", not "error"
        Router.use(lanRoute)
        Router.pairingNeeded(false)
        try {
            Hub.files()
            fail("a device waiting to be let in got the file list")
        } catch (e: PairingRequired) {
            assertTrue(Router.pairing.value)
        }
        // the live connection gets the same answer
        Router.pairingNeeded(false)
        Live.hold("test")
        waitFor("live to be refused") { Router.pairing.value }
        assertFalse(Live.state.value.connected)
        Live.release("test")

        // the owner says no
        assertEquals(200, owner(hub, "/api/device/${request.getString("id")}/remove"))
        waitFor("the not-let-in screen") { setup.visible(R.id.panel_declined) }
        assertTrue(setup.findViewById<TextView>(R.id.declined_body).text.contains(name))
        assertFalse(setup.isFinishing)
        // asking again goes back to naming
        setup.findViewById<View>(R.id.ask_again).performClick()
        idle()
        assertTrue(setup.visible(R.id.panel_name))
    }

    @Test
    fun theHubsPinLetsTheDeviceIn() {
        assumeTrue("set DROPLET_TEST_PIN_HUB and DROPLET_TEST_PIN", pinHub.isNotEmpty() && pinCode.isNotEmpty())
        val pinInfo = hubInfo(pinHub)
        assertTrue(pinInfo.pin)
        val route = Router.Route(Router.Kind.LAN, "https://${pinInfo.lan.first()}", pinInfo.fingerprint)

        val (st, token) = Pairing.join(route, unique("robo-pin"))
        assertTrue(st is Pairing.Standing.Waiting)
        assertFalse("a wrong PIN", Pairing.login(route, "0000", token))
        assertTrue(Pairing.standing(route, token) is Pairing.Standing.Waiting)
        assertTrue("the right PIN", Pairing.login(route, pinCode, token))
        assertTrue(Pairing.standing(route, token) is Pairing.Standing.In)
        // the PIN session is kept for the WebView on that origin
        assertTrue(CookieManager.getInstance().getCookie(route.base).orEmpty().contains("session="))

        // the setup screen offers the PIN on this hub
        val announcedPin = Announced(pinInfo.id, pinInfo.fingerprint!!, "pin-hub", route.host, route.base.substringAfterLast(':').toInt(), null, null)
        clean()
        val setup = startSetup(announcedPin).get()
        waitFor("the hub") { setup.findViewById<LinearLayout>(R.id.hubs).childCount == 1 }
        setup.findViewById<LinearLayout>(R.id.hubs).getChildAt(0).performClick()
        waitFor("the name step") { setup.visible(R.id.panel_name) }
        setup.findViewById<TextView>(R.id.name).text = unique("robo-pin-ui")
        setup.findViewById<View>(R.id.join).performClick()
        waitFor("the code") { setup.visible(R.id.panel_code) }
        assertTrue(setup.visible(R.id.pin))
    }

    @Test
    fun linkWithCodeDuringPairing() {
        // the owner makes a link code on a device that's already in
        val (ownerId, ownerToken) = register(hub, unique("robo-owner"))
        val linkCode = JSONObject(http.newCall(Request.Builder().url("$hub/api/device/link-code")
            .header("Authorization", "Bearer $ownerToken").post(ByteArray(0).toRequestBody()).build())
            .execute().body!!.string()).getString("code")
        val name = unique("robo-link")
        val setup = startSetup(announced).get()
        askToJoin(setup, name)
        val request = pending(hub).first { it.getString("name") == name }

        // the dialog's work, without the dialog
        val (linked, token) = Pairing.link(lanRoute, linkCode)
        assertTrue(linked.startsWith("robo-owner"))
        Pairing.withdraw(lanRoute, request.getString("id"), token)
        assertTrue("the unneeded join request is withdrawn", pending(hub).none { it.getString("id") == request.getString("id") })
        Hub.setToken(token)
        Router.use(lanRoute)
        assertEquals(ownerId, Hub.me().getJSONObject("device").getString("id"))
    }

    // --- 4. upgrading from 1.1, which knew only the tailnet URL ------------------------------

    @Test
    fun upgradesFromATailnetOnlyInstall() {
        val (id, token) = register(hub, unique("robo-old"))
        // what 1.1 left behind: the hub URL, and the token as the WebView's cookie
        Prefs.hubUrl = hub
        CookieManager.getInstance().setCookie(hub, "droplet_device=$token; Path=/; HttpOnly")
        assertNull(Prefs.hubId)

        val r = runBlocking { Router.resolve(force = true) }
        assertEquals("the pin came from the tailnet's answer, and the LAN is used", lanRoute, r)
        assertEquals(info.id, Prefs.hubId)
        assertEquals(info.fingerprint, Prefs.hubFingerprint)
        // (plain http stands in for the tailnet here, so it isn't counted as verified)
        assertEquals(Prefs.PIN_TOFU, Prefs.pinSource)
        assertEquals(hub, Prefs.hubUrl)
        assertEquals(token, Hub.deviceToken())
        assertEquals(token, Prefs.deviceToken)
        assertEquals(id, Hub.me().getJSONObject("device").getString("id"))
    }

    @Test
    fun upgradesWithoutTailscaleByTrustOnFirstUse() {
        val (id, token) = register(hub, unique("robo-tofu"))
        val tailnet = "https://t15.example.invalid"
        Prefs.hubUrl = tailnet
        CookieManager.getInstance().setCookie(tailnet, "droplet_device=$token; Path=/; Secure; HttpOnly")

        // two hubs claim our tailnet name: ambiguous, so nothing is trusted
        val impostor = announced.copy(id = "aaaaaaaaaaaaaaaa", tailnet = tailnet, host = "127.0.0.1")
        Router.discover = { _, _ -> listOf(announced.copy(tailnet = tailnet), impostor) }
        assertNull(runBlocking { Router.resolve(force = true) })
        assertNull(Prefs.hubId)

        // one hub on the Wi-Fi announces the tailnet URL this phone knows: trusted on first use
        Router.discover = { _, _ -> listOf(announced.copy(tailnet = tailnet)) }
        assertEquals(lanRoute, runBlocking { Router.resolve(force = true) })
        assertEquals(info.id, Prefs.hubId)
        assertEquals(Prefs.PIN_TOFU, Prefs.pinSource)
        assertEquals(id, Hub.me().getJSONObject("device").getString("id"))
    }
}

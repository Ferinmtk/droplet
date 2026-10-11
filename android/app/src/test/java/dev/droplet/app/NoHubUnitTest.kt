package dev.droplet.app

import android.app.Application
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import dev.droplet.app.mesh.MeshIdentity
import dev.droplet.app.mesh.TrustList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Without a hub, and moving between that and having one: what setup offers
 * first, where notification links go, and that forgetting a hub keeps the
 * devices paired directly. No network needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoHubUnitTest {
    private lateinit var app: Application
    private lateinit var tmp: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        tmp = java.nio.file.Files.createTempDirectory("nohub-unit").toFile()
        MeshIdentity.keystoreAllowed = false
        Mesh.dirOverride = File(tmp, "phone")
        Mesh.portOverride = 0
        Mesh.directoryFactory = { null }
        Router.discover = { _, _ -> emptyList() }
    }

    @After
    fun tearDown() {
        Mesh.release("test")
        Mesh.dirOverride = null
        Mesh.portOverride = null
        Mesh.directoryFactory = { NsdPeerDirectory(it) }
        SetupActivity.browser = null
        Router.reset()
        Prefs.forgetHub()
        Prefs.noHub = false
        tmp.deleteRecursively()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun notificationLinksGoToTheWebAppOrTheMatchingScreen() {
        val peers = mapOf("0123456789abcdef" to "f".repeat(64))
        fun t(path: String, hub: Boolean, ringing: Boolean = false) = DeepLink.target(path, hub, ringing) { peers[it] }
        // with a hub, everything goes to the web app as before
        for (p in listOf("/", "/#chat-0123456789abcdef", "/#ring", "/#inbox")) assertEquals(DeepLink.Target.Hub(p), t(p, hub = true))
        // without one: the native chat for a device the mesh knows, the ring screen while ringing, else home
        assertEquals(DeepLink.Target.Chat("f".repeat(64)), t("/#chat-0123456789abcdef", hub = false))
        assertEquals(DeepLink.Target.Home, t("/#chat-aaaaaaaaaaaaaaaa", hub = false))
        assertEquals(DeepLink.Target.Ring, t("/#ring", hub = false, ringing = true))
        assertEquals(DeepLink.Target.Home, t("/#ring", hub = false))
        assertEquals(DeepLink.Target.Home, t("/#inbox", hub = false))
        assertEquals(DeepLink.Target.Home, t("/", hub = false))
    }

    @Test
    fun setupGoesStraightToNamingThePhoneOnAFreshInstall() {
        var looked = false
        SetupActivity.browser = { _, onChange, _ ->
            looked = true
            onChange(listOf(Announced("9b16173d305cd15a", "ab".repeat(32), "t15", "192.168.100.20", 8443, 8000, null)))
            Discovery.Handle { }
        }
        val a = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        fun shown(id: Int) = a.findViewById<View>(id).visibility == View.VISIBLE
        // no choice, no hub: naming the phone is the first thing, and nothing looks for hubs
        assertTrue(shown(R.id.panel_direct))
        assertFalse(shown(R.id.panel_choose))
        assertFalse(shown(R.id.panel_find))
        assertFalse("no way to a hub from here", shown(R.id.direct_back))
        assertFalse("no hubs looked for on a fresh install", looked)
        assertEquals(emptyList<String>(), HiddenHub.hubWords(a))
        // an empty name isn't a name
        a.findViewById<android.widget.TextView>(R.id.direct_name).text = "   "
        a.findViewById<View>(R.id.direct_go).performClick()
        idle()
        assertTrue(shown(R.id.direct_error))
        assertFalse(Prefs.isSetUp)
        // Back leaves setup: there's nothing before naming the phone
        a.onBackPressedDispatcher.onBackPressed()
        assertTrue(a.isFinishing)

        // the hidden way in (Settings: long-press About) on a first run: hub setup, with a way back
        val first = Robolectric.buildActivity(SetupActivity::class.java,
            android.content.Intent(app, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ADD_HUB, true)).setup().get()
        assertEquals(View.VISIBLE, first.findViewById<View>(R.id.panel_find).visibility)
        assertEquals(View.VISIBLE, first.findViewById<View>(R.id.find_back).visibility)
        first.findViewById<View>(R.id.find_back).performClick()
        assertEquals(View.VISIBLE, first.findViewById<View>(R.id.panel_direct).visibility)
        assertEquals(View.VISIBLE, first.findViewById<View>(R.id.direct_back).visibility)
        first.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.VISIBLE, first.findViewById<View>(R.id.panel_find).visibility)

        // a phone set up without one: the hidden way in goes straight to finding it
        Prefs.noHub = true
        val add = Robolectric.buildActivity(SetupActivity::class.java,
            android.content.Intent(app, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_ADD_HUB, true)).setup().get()
        assertEquals(View.VISIBLE, add.findViewById<View>(R.id.panel_find).visibility)
        assertEquals(View.GONE, add.findViewById<View>(R.id.panel_choose).visibility)
        assertEquals(View.GONE, add.findViewById<View>(R.id.find_back).visibility)
        // and without it, setup is just naming the phone again: no hub
        val plain = Robolectric.buildActivity(SetupActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, plain.findViewById<View>(R.id.panel_direct).visibility)
        assertEquals(View.GONE, plain.findViewById<View>(R.id.panel_find).visibility)
    }

    @Test
    fun forgettingTheHubKeepsDirectPairingsAndTheHomeScreen() {
        // a hub user with one device from the hub's roster and one paired directly
        Prefs.hubUrl = "https://t15.example.invalid"
        Prefs.hubId = "9b16173d305cd15a"
        Prefs.hubFingerprint = "ab".repeat(32)
        Prefs.meshDeviceName = "robo"
        Prefs.stayConnected = true
        Mesh.hold("test")
        val end = System.currentTimeMillis() + 20_000
        while (Mesh.node == null && System.currentTimeMillis() < end) Thread.sleep(50)
        val n = Mesh.node!!
        val paired = MeshIdentity.loadOrCreate(File(tmp, "paired"))
        val roster = MeshIdentity.loadOrCreate(File(tmp, "roster"))
        n.trust.addPaired(TrustList.makeEntry(peerId = "1234567890abcdef", name = "slim", certPem = paired.pem,
            source = TrustList.SOURCE_PAIRED, os = "linux"))
        n.trust.syncRoster(listOf(TrustList.makeEntry(peerId = "fedcba0987654321", name = "office", certPem = roster.pem,
            source = TrustList.SOURCE_ROSTER, os = "windows", hub = "9b16173d305cd15a")), "9b16173d305cd15a")
        assertEquals(2, n.trust.all().size)
        assertTrue(Prefs.isSetUp)

        // Settings → Forget this hub
        val settings = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        settings.findViewById<View>(R.id.forget).performClick()
        idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        idle()
        val gone = System.currentTimeMillis() + 10_000
        while (n.trust.all().size != 1 && System.currentTimeMillis() < gone) Thread.sleep(50)
        assertFalse(Prefs.hasHub)
        assertTrue("still set up: no hub now", Prefs.noHub && Prefs.isSetUp)
        assertEquals("the directly paired device stays; the hub's goes", listOf("slim"), n.trust.all().map { it.name })
        assertFalse("Settings stays open, now without a hub", settings.isFinishing)
        // the hub is hidden again: no Add a hub, no Forget or Find again, just "This phone"
        assertEquals(View.GONE, settings.findViewById<View>(R.id.add_hub).visibility)
        assertEquals("no Forget or Find again without a hub", View.GONE, settings.findViewById<View>(R.id.hub_buttons).visibility)
        assertEquals(View.GONE, settings.findViewById<View>(R.id.hub_row).visibility)
        assertEquals("This phone", settings.findViewById<android.widget.TextView>(R.id.hub_header).text.toString())
        assertNull(shadowOf(settings).nextStartedActivity)

        // the home screen, with the device and no Hub tile
        val home = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        idle()
        assertFalse(home.isFinishing)
        assertEquals(View.GONE, home.findViewById<View>(R.id.tile_hub).visibility)
        assertEquals(1, home.findViewById<android.widget.LinearLayout>(R.id.devices).childCount)
        assertNotNull(home)
    }
}

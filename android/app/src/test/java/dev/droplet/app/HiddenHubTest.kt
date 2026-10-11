package dev.droplet.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import dev.droplet.app.mesh.MeshIdentity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

/** What's on screen that names a hub: every visible text, hint and description saying "hub". */
object HiddenHub {
    private val HUB = Regex("\\bhubs?\\b", RegexOption.IGNORE_CASE)   // "hub", not "GitHub"

    fun hubWords(activity: Activity): List<String> {
        shadowOf(Looper.getMainLooper()).idle()
        val found = mutableListOf<String>()
        fun walk(v: View) {
            if (!v.isShown) return
            if (v is TextView) {
                for (t in listOf(v.text, v.hint)) if (t != null && HUB.containsMatchIn(t)) found += t.toString()
            }
            v.contentDescription?.let { if (HUB.containsMatchIn(it)) found += it.toString() }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(activity.window.decorView)
        return found
    }
}

/**
 * The hub is optional and hidden: a fresh install never sees the word (setup
 * goes straight to naming the phone, Settings shows no hub section, the home
 * screen no Hub tile), while a phone already linked to a hub keeps all of its
 * hub screens. No network needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HiddenHubTest {
    private lateinit var app: Application
    private lateinit var tmp: File
    private val opened = mutableListOf<ActivityController<*>>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        tmp = java.nio.file.Files.createTempDirectory("hidden-hub").toFile()
        MeshIdentity.keystoreAllowed = false
        Mesh.dirOverride = File(tmp, "phone")
        Mesh.portOverride = 0
        Mesh.directoryFactory = { null }
        Router.discover = { _, _ -> emptyList() }
        SetupActivity.browser = { _, _, _ -> Discovery.Handle { } }
    }

    @After
    fun tearDown() {
        for (c in opened.reversed()) runCatching { c.pause().stop().destroy() }
        Mesh.dirOverride = null
        Mesh.portOverride = null
        Mesh.directoryFactory = { NsdPeerDirectory(it) }
        SetupActivity.browser = null
        Router.reset()
        Prefs.forgetHub()
        Prefs.noHub = false
        tmp.deleteRecursively()
    }

    private fun <T : Activity> open(cls: Class<T>, intent: Intent? = null): T {
        val c = if (intent == null) Robolectric.buildActivity(cls) else Robolectric.buildActivity(cls, intent)
        opened += c
        return c.setup().get()
    }

    private fun Activity.shown(id: Int) = findViewById<View>(id).isShown

    /** The activity [from] started that is a [cls], whatever else it started first. */
    private fun startedFrom(from: Activity, cls: Class<*>): Intent {
        val seen = mutableListOf<String?>()
        while (true) {
            val i = shadowOf(from).nextStartedActivity ?: break
            if (i.component?.className == cls.name) return i
            seen += i.component?.className
        }
        throw AssertionError("${cls.simpleName} wasn't started (started: $seen)")
    }

    @Test
    fun aFreshInstallNeverSeesAHub() {
        assertFalse(Prefs.isSetUp)

        // setup: naming the phone, nothing else
        val setup = open(SetupActivity::class.java)
        assertTrue(setup.shown(R.id.panel_direct))
        assertFalse(setup.shown(R.id.panel_choose))
        assertFalse(setup.shown(R.id.panel_find))
        assertEquals("setup names no hub", emptyList<String>(), HiddenHub.hubWords(setup))

        // set up without a hub, as "Continue" does
        Prefs.noHub = true
        Prefs.meshEnabled = true
        Prefs.meshDeviceName = "robo"
        assertTrue(Prefs.isSetUp)
        assertFalse(Prefs.hasHub)

        // Settings: "This phone" where the hub card was, and no way to a hub in sight
        val settings = open(SettingsActivity::class.java)
        assertEquals("This phone", settings.findViewById<TextView>(R.id.hub_header).text.toString())
        for (id in listOf(R.id.hub_row, R.id.hub_none, R.id.add_hub, R.id.hub_buttons, R.id.link_row, R.id.open,
                R.id.this_phone_title)) {
            assertFalse("${app.resources.getResourceEntryName(id)} hidden", settings.shown(id))
        }
        assertTrue("renaming the phone stays", settings.shown(R.id.rename))
        assertEquals("Settings names no hub", emptyList<String>(), HiddenHub.hubWords(settings))

        // the home screen: no Hub tile, and the first-device help doesn't mention one
        val home = open(MainActivity::class.java)
        assertFalse(home.isFinishing)
        assertFalse(home.shown(R.id.tile_hub))
        assertEquals("home names no hub", emptyList<String>(), HiddenHub.hubWords(home))

        // the status lines the notification and remote screen use
        assertFalse(Router.describe(app).contains("hub", ignoreCase = true))
    }

    @Test
    fun aPhoneLinkedToAHubKeepsItsHubScreens() {
        Prefs.hubUrl = "https://t15.example.invalid"
        Prefs.hubId = "9b16173d305cd15a"
        Prefs.hubFingerprint = "ab".repeat(32)
        Prefs.hubName = "t15"
        Prefs.meshDeviceName = "robo"
        assertTrue(Prefs.hasHub && Prefs.isSetUp)

        // Settings: the hub card, as before
        val settings = open(SettingsActivity::class.java)
        assertEquals("Hub", settings.findViewById<TextView>(R.id.hub_header).text.toString())
        assertEquals("t15", settings.findViewById<TextView>(R.id.hub_name).text.toString())
        for (id in listOf(R.id.hub_row, R.id.change_hub, R.id.hub_buttons, R.id.open, R.id.this_phone_title)) {
            assertTrue("${app.resources.getResourceEntryName(id)} shown", settings.shown(id))
        }
        assertFalse("no Add a hub when there is one", settings.shown(R.id.add_hub))

        // Change → hub setup, finding the hub (not naming the phone)
        settings.findViewById<View>(R.id.change_hub).performClick()
        val change = startedFrom(settings, SetupActivity::class.java)
        val setup = open(SetupActivity::class.java, change)
        assertTrue(setup.shown(R.id.panel_find))
        assertFalse(setup.shown(R.id.panel_direct))

        // home: the Hub tile, which opens the hub's web app; links go there too
        val home = open(MainActivity::class.java)
        assertEquals(View.VISIBLE, home.findViewById<View>(R.id.tile_hub).visibility)
        home.findViewById<View>(R.id.tile_hub).performClick()
        startedFrom(home, HubActivity::class.java)
        assertEquals(DeepLink.Target.Hub("/"), DeepLink.target("/", Prefs.hasHub, false) { null })
    }

    @Test
    fun theHiddenWayInOpensHubSetup() {
        Prefs.noHub = true
        Prefs.meshDeviceName = "robo"
        val settings = open(SettingsActivity::class.java)
        assertTrue("a long-press on About", settings.findViewById<View>(R.id.about).performLongClick())
        val next = startedFrom(settings, SetupActivity::class.java)
        assertTrue(next.getBooleanExtra(SetupActivity.EXTRA_ADD_HUB, false))
        val setup = open(SetupActivity::class.java, next)
        assertTrue(setup.shown(R.id.panel_find))
    }
}

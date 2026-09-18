package dev.droplet.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the remote and Settings screens to PNGs for a human to look at
 * (no emulator needed). Runs only when DROPLET_SHOTS names an output folder.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h873dp-xxhdpi")
class ScreensTest {
    private val out = System.getProperty("droplet.shots").orEmpty()

    private fun shoot(activity: Activity, name: String) {
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(android.graphics.Canvas(bmp))
        File(out).mkdirs()
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun remote() {
        assumeTrue(out.isNotEmpty())
        Prefs.init(ApplicationProvider.getApplicationContext())
        shoot(Robolectric.buildActivity(RemoteActivity::class.java).setup().get(), "remote")
    }

    @Test
    fun settings() {
        assumeTrue(out.isNotEmpty())
        Prefs.hubUrl = "https://t15.tail7375fe.ts.net"
        val a = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        shoot(a, "settings")
        // the remote-control part, further down
        val scroll = a.findViewById<android.widget.ScrollView>(R.id.scroll)
        val section = a.findViewById<android.view.View>(R.id.live_state)
        var y = 0
        var v: android.view.View? = section
        while (v != null && v !== scroll) { y += v.top; v = v.parent as? android.view.View }
        scroll.scrollTo(0, y - 200)
        shoot(a, "settings-remote")
        scroll.scrollTo(0, y + 900)
        shoot(a, "settings-caps")
    }
}

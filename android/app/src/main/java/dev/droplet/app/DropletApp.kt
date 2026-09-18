package dev.droplet.app

import android.app.Application
import android.webkit.CookieManager

class DropletApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Notifs.createChannels(this)
        Router.init(this)
        Live.init(this)
        BtHid.init(this)
        // Native code reads the WebView's cookies (the device token) to call the
        // hub. Loading the cookie store here, on the main thread, means the
        // services can use it from background threads later.
        runCatching { CookieManager.getInstance() }
    }
}

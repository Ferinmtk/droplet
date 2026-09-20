package dev.droplet.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.droplet.app.databinding.ActivityHubBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The hub's droplet web app, plus the native bits a browser tab can't do.
 * Opened from the Hub tile on the home screen ([MainActivity]), with a bar
 * whose arrow goes back there; only when a hub is set up.
 *
 * The page loads on whatever route [Router] picked: the hub's LAN address
 * (HTTPS with the pinned certificate) at home, its tailnet URL away. When the
 * route changes the page moves with it, keeping where it was.
 */
class HubActivity : AppCompatActivity() {
    private lateinit var b: ActivityHubBinding
    private val web get() = b.web

    /** The origin the page is on now ("https://192.168.100.20:8443"), null before the first load. */
    private var origin: String? = null
    /** Where to go once there's a route (a notification's deep link, or the page before a restart). */
    private var pendingPath: String? = null
    private var clearHistoryOnLoad = false
    private var resumed = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var loadFailed = false
    /** The device token the page was loaded with; Link with code swaps it. */
    private var loadedToken: String? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val uris = if (res.resultCode != RESULT_OK || data == null) null else {
            data.clipData?.let { clip -> List(clip.itemCount) { clip.getItemAt(it).uri } }
                ?: data.data?.let { listOf(it) }
        }
        fileCallback?.onReceiveValue(uris?.toTypedArray())
        fileCallback = null
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        edgeToEdge()
        super.onCreate(savedInstanceState)
        if (!Prefs.hasHub) {
            // no hub (any more): the home screen is the app
            finish()
            return
        }
        b = ActivityHubBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()
        b.home.setOnClickListener { finish() }
        b.barTitle.text = getString(R.string.hub_title, Router.hubLabel())

        setUpWebView()
        b.retry.setOnClickListener { retry() }
        b.offlineSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.offlineTv.setOnClickListener { startActivity(dev.droplet.app.tv.TvActivity.intent(this)) }
        b.openTailscale.setOnClickListener { openTailscale() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack() && b.offline.visibility != View.VISIBLE) web.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        // after a restart, go back to the same place in the page (on whichever origin is right now)
        pendingPath = intent.getStringExtra(EXTRA_PATH) ?: savedInstanceState?.getString(STATE_PATH)
        b.progress.isIndeterminate = true
        b.progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Router.state.collect { routeState(it) } }
                launch {
                    Router.pairing.collect { needed ->
                        if (!needed) return@collect
                        // the hub doesn't know this phone (any more): pair natively, once per refusal
                        Router.pairingNeeded(false)
                        startActivity(Intent(this@HubActivity, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_PAIR, true))
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val path = intent.getStringExtra(EXTRA_PATH) ?: return
        val o = origin
        if (o != null && Router.current()?.base == o) web.loadUrl(o + path) else pendingPath = path
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::b.isInitialized) pagePath()?.let { outState.putString(STATE_PATH, it) }
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        if (!Prefs.hasHub) {
            // forgotten in Settings: back to the home screen
            finish()
            return
        }
        b.barTitle.text = getString(R.string.hub_title, Router.hubLabel())
        visible = true
        resumed = true
        web.onResume()
        // keeps the route fresh while the app is on screen; looks again if it's been a while
        Router.hold(ROUTER_TAG)
        // MIUI and force-stop kill the service; opening the app brings it back
        if (Prefs.stayConnected && !ConnectionService.running) runCatching { ConnectionService.start(this) }
        // the page may have just named this phone (a new device token)
        Live.refresh()
        // a route change while paused: move now
        routeState(Router.state.value)
        // linked to another device in Settings: show the page as that device
        val token = Hub.deviceToken()
        if (loadedToken != null && token != null && token != loadedToken && origin != null) {
            Hub.installCookie(origin!!)
            web.reload()
        }
        loadedToken = token
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // the only moment Android lets droplet read the clipboard: send it if it changed
        if (hasFocus && ::b.isInitialized) runCatching { ClipBridge.onForeground(this) }
    }

    override fun onPause() {
        super.onPause()
        if (!::b.isInitialized) return
        visible = false
        resumed = false
        web.onPause()
        Router.release(ROUTER_TAG)
        // the services read the device cookie from disk-backed storage
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (::b.isInitialized) {
            fileCallback?.onReceiveValue(null)
            web.destroy()
        }
        super.onDestroy()
    }

    // --- following the route ---------------------------------------------------

    private fun routeState(s: Router.State) {
        if (!::b.isInitialized || isFinishing) return
        val r = s.route
        when {
            // not looked yet, or looking: keep what's on screen
            r == null && !s.unreachable -> if (origin == null && b.offline.visibility != View.VISIBLE) showLooking()
            r == null -> showOffline(if (s.identityChanged != null) Offline.IDENTITY else Offline.UNREACHABLE, null, s.identityChanged)
            // moves only while on screen: a reload behind the user's back loses nothing, but can wait
            r.base != origin -> if (resumed || origin == null) switchTo(r)
            // back after being out of reach (at most every few seconds, so a page that keeps failing can't loop)
            b.offline.visibility == View.VISIBLE && !s.searching && offlineKind != Offline.IDENTITY &&
                SystemClock.elapsedRealtime() - lastAutoRetry > AUTO_RETRY_MS -> {
                lastAutoRetry = SystemClock.elapsedRealtime()
                retry()
            }
        }
    }

    /** Loads the page on [r], keeping the path and #hash it was on. */
    private fun switchTo(r: Router.Route) {
        val path = pendingPath ?: pagePath() ?: "/"
        pendingPath = null
        // cookies are per origin: the LAN origin needs the device cookie before the page asks who it is
        Hub.installCookie(r.base)
        clearHistoryOnLoad = origin != null
        origin = r.base
        loadedToken = Hub.deviceToken()
        showWeb()
        web.loadUrl(r.base + path)
    }

    /** The page's path, query and #hash, if it's on one of the hub's origins. */
    private fun pagePath(): String? {
        val url = web.url?.let { Uri.parse(it) } ?: return null
        if (url.scheme !in listOf("http", "https") || !Hub.isHubUrl(url)) return null
        return (url.encodedPath?.ifEmpty { "/" } ?: "/") +
            (url.encodedQuery?.let { "?$it" } ?: "") + (url.encodedFragment?.let { "#$it" } ?: "")
    }

    // --- WebView --------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setUpWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            setSupportZoom(false)
            userAgentString = "$userAgentString ${Hub.userAgent}"
        }
        // the page registers a service worker (offline page, share parking);
        // WebView only runs service workers once a client is set. (Chromium
        // won't register one on the LAN origin, whose certificate is pinned
        // rather than publicly trusted; the page works without it.)
        runCatching {
            ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
            })
        }
        web.addJavascriptInterface(Bridge(), "DropletApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                openElsewhere(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                loadFailed = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                b.progress.visibility = View.GONE
                if (clearHistoryOnLoad) {
                    // the old origin's pages are gone: Back mustn't lead there
                    clearHistoryOnLoad = false
                    view.clearHistory()
                }
                if (loadFailed) return
                // the service worker's own offline page: show ours instead
                if (view.title?.contains("offline") == true && url?.let { Hub.isHubUrl(Uri.parse(it)) } == true) {
                    showOffline(Offline.LOAD_FAILED, null)
                    return
                }
                showWeb()
                adoptPageToken()
                view.evaluateJavascript(PAGE_SCRIPT) { matchPageColour(it) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                showOffline(Offline.LOAD_FAILED, error.description?.toString())
                // the route may have gone (left the Wi-Fi, Tailscale off): look again
                Router.refresh()
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                // 502/503/504: tailscale serve is up but droplet isn't
                if (request.isForMainFrame && response.statusCode in 502..504) {
                    showOffline(Offline.LOAD_FAILED, getString(R.string.offline_not_running, response.statusCode))
                }
            }

            /**
             * The LAN origin's certificate is self-signed: go on only if it's
             * exactly the pinned one (docs/local-first.md §5). Anything else,
             * including a publicly valid certificate with some other problem,
             * is refused.
             */
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                if (Pinning.webViewMayProceed(error.certificate, Prefs.hubFingerprint)) {
                    handler.proceed()
                } else {
                    handler.cancel()
                    if (error.url?.let { Hub.isHubUrl(Uri.parse(it)) } == true) {
                        // our hub's address, but not our hub's certificate
                        showOffline(Offline.LOAD_FAILED, getString(R.string.live_err_identity))
                        Router.refresh()
                    }
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (origin == null) return  // still looking for the hub: the bar keeps spinning
                b.progress.isIndeterminate = false
                b.progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                b.progress.setProgressCompat(newProgress, true)
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try {
                    pickFiles.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    toast(getString(R.string.no_file_picker))
                    false
                }
            }
        }

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            download(url, userAgent, contentDisposition, mimeType)
        }
    }

    /**
     * The page named or re-linked this phone on the current origin: that
     * token is now the phone's identity, on every origin and for the
     * native parts.
     */
    private fun adoptPageToken() {
        val o = origin ?: return
        val fromPage = Hub.cookieToken(o) ?: return
        if (fromPage == Prefs.deviceToken) return
        Hub.setToken(fromPage)
        loadedToken = fromPage
        Live.refresh()
    }

    /** Hub pages stay in the app; everything else opens in its own app or the browser. */
    private fun openElsewhere(url: Uri): Boolean {
        if ((url.scheme == "http" || url.scheme == "https") && Hub.isHubUrl(url)) return false
        if (url.scheme == "blob" || url.scheme == "data" || url.scheme == "about") return false
        try {
            val intent = if (url.scheme == "intent") Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, url)
            // links from the page may only reach apps that accept browser links
            intent.addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null)
            intent.selector = null
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.no_app_for_link))
        }
        return true
    }

    // --- out of reach ------------------------------------------------------------

    private enum class Offline { UNREACHABLE, LOAD_FAILED, IDENTITY }

    private var offlineKind: Offline? = null
    private var lastAutoRetry = 0L

    private fun showLooking() {
        b.offline.visibility = View.GONE
        b.progress.isIndeterminate = true
        b.progress.visibility = View.VISIBLE
    }

    private fun showOffline(kind: Offline, detail: String?, identity: Router.IdentityChange? = Router.state.value.identityChanged) {
        loadFailed = true
        offlineKind = kind
        val hub = Router.hubLabel()
        val tailscale = Prefs.hubUrl?.let { Uri.parse(it).host?.endsWith(".ts.net") } == true
        when (kind) {
            Offline.IDENTITY -> {
                b.offlineTitle.text = getString(R.string.offline_identity_title)
                b.offlineBody.text = getString(R.string.offline_identity_body, identity?.address ?: hub, hub)
                b.retry.setText(R.string.offline_repair)
            }
            else -> {
                b.offlineTitle.text = getString(R.string.offline_title, hub)
                b.offlineBody.text = getString(if (Prefs.hubUrl != null) R.string.offline_body else R.string.offline_body_lan, hub)
                b.retry.setText(R.string.retry)
            }
        }
        b.offlineDetail.text = detail?.let { friendlyError(it) } ?: getString(R.string.offline_body_retry)
        b.openTailscale.visibility = if (kind != Offline.IDENTITY && tailscale && tailscaleIntent() != null) View.VISIBLE else View.GONE
        b.offline.visibility = View.VISIBLE
        b.progress.visibility = View.GONE
        web.visibility = View.INVISIBLE
        setBarColour(ContextCompat.getColor(this, R.color.r_bg))
    }

    private fun showWeb() {
        b.offline.visibility = View.GONE
        web.visibility = View.VISIBLE
        offlineKind = null
    }

    private fun retry() {
        if (offlineKind == Offline.IDENTITY) {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_REPAIR, true))
            return
        }
        val r = Router.current()
        if (r == null) {
            showLooking()
            Router.refresh()
            return
        }
        showWeb()
        if (r.base != origin) switchTo(r) else web.loadUrl(r.base + (pagePath() ?: "/"))
    }

    private fun tailscaleIntent(): Intent? = packageManager.getLaunchIntentForPackage(TAILSCALE)

    private fun openTailscale() {
        val intent = tailscaleIntent() ?: return
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app_for_link))
        }
    }

    private fun friendlyError(raw: String): String = when {
        "NAME_NOT_RESOLVED" in raw -> getString(R.string.offline_err_name)
        "CONNECTION_REFUSED" in raw -> getString(R.string.offline_err_refused)
        "TIMED_OUT" in raw || "ADDRESS_UNREACHABLE" in raw -> getString(R.string.offline_err_timeout)
        "INTERNET_DISCONNECTED" in raw -> getString(R.string.offline_err_offline)
        "CERT" in raw || "SSL" in raw -> getString(R.string.live_err_identity)
        else -> raw
    }

    /** Colour the status/navigation bar area like the page, and pick matching bar icons. */
    private fun matchPageColour(result: String?) {
        val nums = Regex("\\d+").findAll(result ?: return).map { it.value.toInt() }.toList()
        if (nums.size < 3) return
        setBarColour(Color.rgb(nums[0], nums[1], nums[2]))
    }

    private fun setBarColour(color: Int) {
        b.root.setBackgroundColor(color)
        window.decorView.setBackgroundColor(color)
        val light = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 > 140
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        // the bar's arrow and title read on the page's colour
        val ink = if (light) Color.rgb(15, 23, 42) else Color.rgb(227, 241, 239)
        b.home.imageTintList = android.content.res.ColorStateList.valueOf(ink)
        b.barTitle.setTextColor(ink)
    }

    // --- downloads ------------------------------------------------------------

    private fun download(url: String, userAgent: String, contentDisposition: String?, mimeType: String?) {
        if (!url.startsWith("http")) return  // blob: downloads go through the bridge
        if (Build.VERSION.SDK_INT < 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val name = fileName(url, contentDisposition)
        val route = Router.current()
        if (route?.kind == Router.Kind.LAN && url.startsWith(route.base + "/")) {
            // DownloadManager can't check a pinned certificate: fetch it here
            downloadPinned(route, url, name, mimeType)
            return
        }
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
            addRequestHeader("User-Agent", userAgent)
            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
            setTitle(name)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        try {
            getSystemService(DownloadManager::class.java).enqueue(request)
            toast(getString(R.string.download_started, name))
        } catch (e: Exception) {
            toast(getString(R.string.download_failed, name))
        }
    }

    /**
     * A download from the LAN origin, over the pinned client, into Downloads.
     * Runs in the app's scope, so leaving the screen doesn't stop it.
     */
    private fun downloadPinned(route: Router.Route, url: String, fallbackName: String, mimeType: String?) {
        toast(getString(R.string.download_started, fallbackName))
        val app = applicationContext
        Router.scope.launch {
            var name = fallbackName
            val ok = runCatching {
                val req = Hub.request(route, url.removePrefix(route.base)).build()
                Router.clientFor(route).newBuilder().readTimeout(5, TimeUnit.MINUTES).build().newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("The hub answered ${r.code}")
                    name = fileName(url, r.header("Content-Disposition") ?: "attachment; filename=\"$fallbackName\"")
                    val mime = mimeType?.takeIf { it.isNotBlank() } ?: r.body?.contentType()?.let { "${it.type}/${it.subtype}" }
                        ?: "application/octet-stream"
                    val tmp = File.createTempFile("dl", null, app.cacheDir)
                    try {
                        tmp.outputStream().use { out -> r.body!!.byteStream().use { it.copyTo(out) } }
                        saveToDownloads(tmp, name, mime)
                    } finally {
                        tmp.delete()
                    }
                }
            }.isSuccess
            withContext(Dispatchers.Main) {
                Toast.makeText(app, app.getString(if (ok) R.string.download_saved else R.string.download_failed, name), Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Flask sends `filename*=UTF-8''…` for non-ASCII names and `filename=` otherwise. */
    private fun fileName(url: String, cd: String?): String {
        val star = cd?.let { Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)").find(it)?.groupValues?.get(1) }
        val plain = cd?.let { Regex("filename=\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
        val fromUrl = Uri.parse(url).lastPathSegment
        val raw = star?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() } ?: plain ?: fromUrl ?: "download"
        return raw.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().ifEmpty { "download" }
    }

    /**
     * The page builds zips in JavaScript and "downloads" a blob: URL, which
     * DownloadManager can't fetch. The injected script streams the blob here
     * in base64 chunks, and it's saved to Downloads.
     */
    private inner class Bridge {
        private val saves = ConcurrentHashMap<Int, Pair<File, String>>()
        private val names = ConcurrentHashMap<Int, String>()
        private val ids = AtomicInteger()

        @JavascriptInterface
        fun openSettings() {
            runOnUiThread { startActivity(Intent(this@HubActivity, SettingsActivity::class.java)) }
        }

        /** The phone's own TV remote, which talks to the TV directly (for a web page link to it). */
        @JavascriptInterface
        fun openTvRemote() {
            runOnUiThread { startActivity(dev.droplet.app.tv.TvActivity.intent(this@HubActivity)) }
        }

        @JavascriptInterface
        fun beginSave(name: String, mime: String): Int {
            val id = ids.incrementAndGet()
            val tmp = File.createTempFile("blob", null, cacheDir)
            saves[id] = tmp to mime
            names[id] = fileName("x", "attachment; filename=\"$name\"")
            return id
        }

        @JavascriptInterface
        fun appendChunk(id: Int, base64: String) {
            val (file, _) = saves[id] ?: return
            FileOutputStream(file, true).use { it.write(Base64.decode(base64, Base64.DEFAULT)) }
        }

        @JavascriptInterface
        fun finishSave(id: Int) {
            val (file, mime) = saves.remove(id) ?: return
            val name = names.remove(id) ?: "download"
            val ok = runCatching { saveToDownloads(file, name, mime) }.isSuccess
            file.delete()
            runOnUiThread { toast(getString(if (ok) R.string.download_saved else R.string.download_failed, name)) }
        }

        @JavascriptInterface
        fun failSave(name: String) {
            runOnUiThread { toast(getString(R.string.download_failed, name)) }
        }
    }

    private fun saveToDownloads(file: File, name: String, mime: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            var dest = File(dir, name)
            var i = 1
            while (dest.exists()) dest = File(dir, "${name.substringBeforeLast('.')}-${i++}.${name.substringAfterLast('.', "")}")
            file.copyTo(dest)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** A path in the page ("/#chat-<id>"), as the home screen hands it over. */
        const val EXTRA_PATH = MainActivity.EXTRA_PATH
        private const val STATE_PATH = "page_path"
        private const val ROUTER_TAG = "main"
        private const val TAILSCALE = "com.tailscale.ipn"
        private const val AUTO_RETRY_MS = 5_000L

        /** Whether the web app is on screen (its own page flashes new items then). */
        @Volatile
        var visible = false

        /**
         * Runs after every page load: adds an app-settings button to the header,
         * routes blob: downloads through the bridge, and returns the page's
         * background colour for the system bars.
         */
        private val PAGE_SCRIPT = """
            (function () {
              if (!window.__dropletApp) {
                window.__dropletApp = true;
                var revoke = URL.revokeObjectURL.bind(URL);
                // the page revokes its zip URL straight after clicking it; give the save time to read it
                URL.revokeObjectURL = function (u) { setTimeout(function () { revoke(u); }, 120000); };
                var b64 = function (buf) {
                  var s = "", bytes = new Uint8Array(buf);
                  for (var i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
                  return btoa(s);
                };
                var save = function (url, name) {
                  fetch(url).then(function (r) { return r.blob(); }).then(async function (blob) {
                    var id = DropletApp.beginSave(name || "download", blob.type || "application/octet-stream");
                    for (var i = 0; i < blob.size; i += 786432) DropletApp.appendChunk(id, b64(await blob.slice(i, i + 786432).arrayBuffer()));
                    DropletApp.finishSave(id);
                  }).catch(function () { DropletApp.failSave(name || "download"); });
                };
                var click = HTMLAnchorElement.prototype.click;
                HTMLAnchorElement.prototype.click = function () {
                  if (this.href && this.href.indexOf("blob:") === 0) return save(this.href, this.download);
                  return click.call(this);
                };
                document.addEventListener("click", function (e) {
                  var a = e.target.closest && e.target.closest('a[href^="blob:"]');
                  if (a) { e.preventDefault(); save(a.href, a.download); }
                }, true);
              }
              // app settings button: at the right of the redesigned header's bar,
              // or inside the <h1> on hubs running the older page
              var bar = document.querySelector(".appbar-in");
              var h1 = document.querySelector("h1");
              if ((bar || h1) && !document.getElementById("app-settings")) {
                var btn = document.createElement("button");
                btn.id = "app-settings";
                btn.type = "button";
                btn.title = "App settings";
                btn.setAttribute("aria-label", "App settings");
                btn.onclick = function () { DropletApp.openSettings(); };
                if (bar) {
                  btn.className = "icon-btn ghost";
                  btn.style.marginLeft = "auto";
                  if (document.getElementById("i-gear")) {
                    btn.innerHTML = '<svg class="ico" aria-hidden="true"><use href="#i-gear"/></svg>';
                  } else {
                    btn.textContent = "⚙";
                  }
                  var install = document.getElementById("install");
                  bar.insertBefore(btn, install ? install.nextSibling : null);
                } else {
                  btn.textContent = "⚙";
                  btn.style.cssText = "font-size:1.05rem;line-height:1;padding:.35rem .55rem;margin-left:auto";
                  h1.appendChild(btn);
                }
              }
              return getComputedStyle(document.body).backgroundColor;
            })();
        """.trimIndent()
    }
}

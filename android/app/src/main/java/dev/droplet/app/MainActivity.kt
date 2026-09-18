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
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
import dev.droplet.app.databinding.ActivityMainBinding
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The droplet web app, full screen, plus the native bits a browser tab can't do. */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val web get() = b.web
    private lateinit var hub: String

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var loadFailed = false
    private var failedUrl: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        hub = Prefs.hubUrl ?: run {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()

        setUpWebView()
        b.retry.setOnClickListener { retry() }
        b.offlineSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack() && b.offline.visibility != View.VISIBLE) web.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(hub + (intent.getStringExtra(EXTRA_PATH) ?: "/"))
        }

        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedNotifications &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Prefs.askedNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_PATH)?.let { web.loadUrl(hub + it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::b.isInitialized) web.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized) return
        visible = true
        web.onResume()
        // MIUI and force-stop kill the service; opening the app brings it back
        if (Prefs.stayConnected && !ConnectionService.running) runCatching { ConnectionService.start(this) }
        watchNetwork(true)
        // the hub address may have changed in settings
        if (Prefs.hubUrl != null && Prefs.hubUrl != hub) {
            hub = Prefs.hubUrl!!
            web.clearHistory()
            web.loadUrl("$hub/")
        }
    }

    override fun onPause() {
        super.onPause()
        if (!::b.isInitialized) return
        visible = false
        web.onPause()
        watchNetwork(false)
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
        // WebView only runs service workers once a client is set
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
                if (loadFailed) return
                // the service worker's own offline page: show ours instead
                if (view.title?.contains("offline") == true && url?.startsWith(hub) == true) {
                    showOffline(url, null)
                    return
                }
                showWeb()
                view.evaluateJavascript(PAGE_SCRIPT) { matchPageColour(it) }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) showOffline(request.url.toString(), error.description?.toString())
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                // 502/503/504: tailscale serve is up but droplet isn't
                if (request.isForMainFrame && response.statusCode in 502..504) {
                    showOffline(request.url.toString(), "The hub machine answered, but droplet isn't running on it (${response.statusCode}).")
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
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

    /** Hub pages stay in the app; everything else opens in its own app or the browser. */
    private fun openElsewhere(url: Uri): Boolean {
        val hubUri = Uri.parse(hub)
        if (url.scheme == hubUri.scheme && url.encodedAuthority == hubUri.encodedAuthority) return false
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

    private fun showOffline(url: String?, detail: String?) {
        loadFailed = true
        failedUrl = url
        b.offlineDetail.text = detail?.let { friendlyError(it) } ?: ""
        b.offline.visibility = View.VISIBLE
        web.visibility = View.INVISIBLE
        setBarColour(ContextCompat.getColor(this, R.color.bg))
    }

    private fun showWeb() {
        b.offline.visibility = View.GONE
        web.visibility = View.VISIBLE
    }

    private fun retry() {
        b.offline.visibility = View.GONE
        web.visibility = View.VISIBLE
        val url = failedUrl?.takeIf { it.startsWith(hub) } ?: "$hub/"
        failedUrl = null
        web.loadUrl(url)
    }

    private fun friendlyError(raw: String): String = when {
        "NAME_NOT_RESOLVED" in raw -> "The hub's name didn't resolve. Tailscale is probably off."
        "CONNECTION_REFUSED" in raw -> "The hub refused the connection. Is droplet running?"
        "TIMED_OUT" in raw || "ADDRESS_UNREACHABLE" in raw -> "The hub didn't answer in time."
        "INTERNET_DISCONNECTED" in raw -> "This phone is offline."
        else -> raw
    }

    /** Retry by itself as soon as the network comes back. */
    private fun watchNetwork(on: Boolean) {
        val cm = getSystemService(ConnectivityManager::class.java)
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
        if (!on) return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { if (b.offline.visibility == View.VISIBLE) retry() }
            }
        }.also { cm.registerDefaultNetworkCallback(it) }
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
            runOnUiThread { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
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
        const val EXTRA_PATH = "path"

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

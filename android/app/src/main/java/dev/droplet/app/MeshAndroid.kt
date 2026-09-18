package dev.droplet.app

import android.content.ContentValues
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dev.droplet.app.mesh.MeshFiles
import dev.droplet.app.mesh.Seen
import dev.droplet.app.mesh.PeerDirectory
import dev.droplet.app.mesh.TrustList
import java.io.File

/**
 * mDNS for the mesh (docs/mesh.md §2) with Android's NsdManager: announces
 * `_droplet-peer._tcp` on the mesh port with the TXT records, and browses
 * for other peers with the same code that finds hubs ([Discovery]).
 * An announcement is only a hint: the connection is checked by fingerprint.
 */
class NsdPeerDirectory(context: Context) : PeerDirectory {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private var registration: NsdManager.RegistrationListener? = null
    private var browsing: Discovery.Handle? = null
    @Volatile private var seen: List<Seen> = emptyList()
    private var port = 0
    private var ownFp = ""

    override fun start(port: Int, txt: Map<String, String>, onSeen: (Seen) -> Unit) {
        this.port = port
        ownFp = txt["fp"].orEmpty()
        register(txt)
        browsing = Discovery.browseType(app, SERVICE_TYPE, ::parse, onChange = { list ->
            val others = list.filter { it.fp != ownFp }
            val before = seen
            seen = others
            others.filter { it !in before }.forEach(onSeen)
        })
    }

    @Synchronized
    private fun register(txt: Map<String, String>) {
        nsd ?: return
        val label = (txt["name"] ?: "droplet").replace(Regex("[^\\w\\- ]"), "").take(40).trim().ifEmpty { "droplet" }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching {
            val info = NsdServiceInfo().apply {
                serviceName = "$label ${txt["id"].orEmpty().take(6)}"
                serviceType = SERVICE_TYPE
                setPort(port)
                // a value over 255 bytes throws: such a record is left out rather than the whole announcement
                txt.forEach { (k, v) -> runCatching { setAttribute(k, v) } }
            }
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        }
    }

    @Synchronized
    private fun unregister() {
        registration?.let { l -> runCatching { nsd?.unregisterService(l) } }
        registration = null
    }

    override fun update(txt: Map<String, String>) {
        unregister()
        register(txt)
    }

    override fun peers(): List<Seen> = seen

    override fun close() {
        browsing?.stop()
        browsing = null
        unregister()
    }

    companion object {
        const val SERVICE_TYPE = "_droplet-peer._tcp"
        private val OSES = setOf("android", "windows", "linux")

        /** TXT records → a peer, or null if it isn't a usable one. */
        fun parse(host: String?, port: Int, attrs: Map<String, ByteArray?>): Seen? {
            fun txt(key: String): String? = attrs.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?.value?.toString(Charsets.UTF_8)?.trim()
            val id = txt("id")?.lowercase() ?: return null
            val fp = txt("fp")?.replace(":", "")?.lowercase() ?: return null
            val v = txt("v") ?: return null
            if (!TrustList.PEER_ID.matches(id) || !TrustList.FINGERPRINT.matches(fp) || v.isEmpty() || !v.all { it.isDigit() }) return null
            if (host.isNullOrBlank() || port !in 1..65535) return null
            val os = txt("os")?.lowercase().orEmpty()
            val hub = txt("hub")?.lowercase().orEmpty()
            return Seen(fp = fp, id = id, name = TrustList.cleanName(txt("name"), id), os = if (os in OSES) os else "",
                caps = TrustList.cleanCaps(txt("caps").orEmpty().split(',')), hub = if (TrustList.PEER_ID.matches(hub)) hub else "",
                port = port, addresses = listOf(host))
        }
    }
}

/** Received files go to Downloads/droplet, where the Files app and other apps see them. */
object MeshDownloads {
    const val FOLDER = "droplet"

    fun save(context: Context, part: File, name: String, mime: String): Mesh.Saved {
        if (Build.VERSION.SDK_INT >= 29) return saveMediaStore(context, part, name, mime)
        // Android 8/9: the public folder if storage may be written, else the app's own Downloads
        val public = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        val dir = if ((public.isDirectory || public.mkdirs()) && public.canWrite()) public
            else File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), FOLDER).apply { mkdirs() }
        val dest = unique(name) { !File(dir, it).exists() }.let { File(dir, it) }
        part.inputStream().use { i -> dest.outputStream().use { i.copyTo(it) } }
        return Mesh.Saved(android.net.Uri.fromFile(dest), dest.path)
    }

    /** "photo.jpg", then "photo (1).jpg", … until [free] says yes. */
    fun unique(name: String, free: (String) -> Boolean): String {
        val safe = MeshFiles.safeName(name)
        if (free(safe)) return safe
        val dot = safe.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) safe.substring(0, dot) to safe.substring(dot) else safe to ""
        var i = 1
        while (true) {
            val n = "$stem ($i)$ext"
            if (free(n)) return n
            i++
        }
    }

    @androidx.annotation.RequiresApi(29)
    private fun saveMediaStore(context: Context, part: File, name: String, mime: String): Mesh.Saved {
        val cr = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val rel = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"
        // never overwrite: pick a name no item in the folder has
        val taken = HashSet<String>()
        cr.query(collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(rel), null)?.use { c -> while (c.moveToNext()) c.getString(0)?.let { taken.add(it) } }
        val finalName = unique(name) { it !in taken }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, rel)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(collection, values) ?: throw java.io.IOException("Downloads isn't available")
        try {
            cr.openOutputStream(uri, "w")?.use { out -> part.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("can't write to Downloads")
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        // MediaStore may still have renamed it; say what it's called now
        val shown = cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: finalName
        return Mesh.Saved(uri, "Download/$FOLDER/$shown")
    }
}

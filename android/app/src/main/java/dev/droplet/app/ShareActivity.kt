package dev.droplet.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.droplet.app.databinding.SheetShareBinding
import dev.droplet.app.mesh.Links
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Share → droplet" from any app: a native sheet listing your devices (and
 * the hub, when there is one), then a background transfer with a progress
 * notification. Without a hub the devices are the directly paired ones, and
 * everything goes to them directly.
 */
class ShareActivity : AppCompatActivity() {
    private companion object {
        const val MESH_TAG = "share"
        val DIRECT = setOf("lan", "tailnet")
    }

    private lateinit var sheet: BottomSheetDialog
    private lateinit var b: SheetShareBinding
    private var files: List<Outgoing> = emptyList()
    private var text: String? = null
    private var devices: List<Device> = emptyList()
    private var named = false
    private var unreadable = 0
    /** The shared text is nothing but a web link: it goes as a link (opened on your own devices). */
    private var link = false
    /** Picking several devices: ticked ones, by target, with their names. */
    private var selecting = false
    private val chosen = LinkedHashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // with no hub, directly paired devices are still there
        if (!Prefs.isSetUp || !Prefs.hasHub && !Prefs.meshEnabled) {
            Toast.makeText(this, R.string.share_no_hub, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        b = SheetShareBinding.inflate(layoutInflater)
        sheet = BottomSheetDialog(this).apply {
            setContentView(b.root)
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            setOnDismissListener { finish() }
        }
        b.retry.setOnClickListener { loadDevices() }
        b.several.setOnClickListener {
            selecting = !selecting
            chosen.clear()
            renderTargets()
        }
        b.sendSeveral.setOnClickListener { sendSeveral() }
        lifecycleScope.launch {
            val uris = sharedUris(intent)
            text = sharedText(intent)
            files = withContext(Dispatchers.IO) { uris.mapNotNull { describe(it) } }
            // a shared web page: its link goes as a link, which opens on your own devices
            if (files.isEmpty()) Links.onlyUrl(text)?.let { text = it; link = true }
            if (files.isEmpty() && text == null) {
                Toast.makeText(this@ShareActivity, if (unreadable > 0) R.string.share_unreadable else R.string.share_nothing,
                    Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            b.summary.text = summary()
            renderTargets()
            sheet.show()
            if (Prefs.hasHub) loadDevices()
            // mesh peers appear as the mesh starts, and their routes as they change
            launch { Mesh.changes.collect { renderTargets() } }
            launch { Mesh.state.collect { renderTargets() } }
        }
    }

    override fun onStart() {
        super.onStart()
        Mesh.hold(MESH_TAG)
    }

    override fun onStop() {
        Mesh.release(MESH_TAG)
        super.onStop()
    }

    private fun loadDevices() {
        b.loading.visibility = View.VISIBLE
        b.status.visibility = View.GONE
        b.retry.visibility = View.GONE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Hub.devices() to Hub.hasDevice() } }
            b.loading.visibility = View.GONE
            result.onSuccess { (list, hasDevice) ->
                named = hasDevice && list.any { it.self }
                devices = list.filter { !it.self }
                renderTargets()
                // text to a device is a chat message, and chat needs a named sender
                if (!named && devices.isNotEmpty()) status(getString(R.string.share_unnamed))
            }.onFailure {
                when (it) {
                    // a guest may still drop files on the hub itself
                    is PairingRequired -> status(getString(R.string.share_not_let_in, Router.hubLabel()))
                    is HubUnreachable -> {
                        status(it.message.orEmpty())
                        b.retry.visibility = View.VISIBLE
                    }
                    else -> {
                        status(getString(R.string.share_unreachable) + "\n" + (it.message ?: ""))
                        b.retry.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun status(msg: String) {
        b.status.text = msg.trim()
        b.status.visibility = View.VISIBLE
    }

    private fun renderTargets() {
        if (!::b.isInitialized) return
        b.targets.removeAllViews()
        val last = Prefs.lastTarget
        // the hub itself isn't a device to send to among several
        val hubRow = if (Prefs.hasHub && !selecting) row(getString(R.string.share_hub), getString(R.string.share_hub_sub) + " · " + Hub.hostLabel(),
            R.drawable.ic_hub, online = null, lastUsed = last == "hub" || last == null) { send("hub", getString(R.string.share_hub)) } else null
        val mesh = Mesh.peers()
        val rows = devices.map { d ->
            // a device the mesh knows goes directly when it can (and through the hub when not)
            val peer = mesh.firstOrNull { it.entry.id == d.id }
            val usable = named || text == null || peer != null
            val sub = getString(if (d.online) R.string.share_online else R.string.share_offline) +
                (peer?.let { " · " + Mesh.describeRoute(this, it.route) } ?: "")
            val name = peer?.entry?.label ?: d.name
            d.id to row(name, sub, R.drawable.ic_device, online = d.online || peer?.route in DIRECT,
                lastUsed = last == d.id, enabled = usable, target = d.id) { send(d.id, name) }
        } + mesh.filter { p -> devices.none { it.id == p.entry.id } }.map { p ->
            val id = PeersActivity.MESH_PREFIX + p.entry.fp
            val sub = Mesh.describeRoute(this, p.route) + (p.entry.nickname.takeIf { it.isNotEmpty() }?.let { " · " + p.entry.name } ?: "")
            id to row(p.entry.label, sub, R.drawable.ic_device, online = p.route in DIRECT,
                lastUsed = last == id, target = id) { send(id, p.entry.label) }
        }
        b.several.visibility = if (rows.size >= 2) View.VISIBLE else View.GONE
        b.several.setText(if (selecting) R.string.share_one else R.string.share_several)
        b.sendSeveral.visibility = if (selecting) View.VISIBLE else View.GONE
        b.sendSeveral.isEnabled = chosen.isNotEmpty()
        b.sendSeveral.text = if (chosen.isEmpty()) getString(R.string.share_send_several_none)
            else resources.getQuantityString(R.plurals.share_send_several, chosen.size, chosen.size)
        // the last destination goes first: sharing tends to repeat
        val ordered = listOfNotNull(hubRow?.let { "hub" to it }) + rows
        ordered.sortedByDescending { it.first == last }.forEach { b.targets.addView(it.second) }
        // no hub and nothing paired yet: say how to get somewhere to send it
        if (ordered.isEmpty() && !Prefs.hasHub && Mesh.node != null) {
            status(getString(R.string.share_no_devices))
            b.retry.setText(R.string.home_pair)
            b.retry.setOnClickListener {
                startActivity(Intent(this, PeersActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                sheet.dismiss()
            }
            b.retry.visibility = View.VISIBLE
        } else if (!Prefs.hasHub) {
            b.status.visibility = View.GONE
            b.retry.visibility = View.GONE
        }
        // no hub: the list is the mesh's, which may still be starting
        if (!Prefs.hasHub) b.loading.visibility = if (Mesh.node == null && Prefs.meshEnabled) View.VISIBLE else View.GONE
    }

    private fun row(name: String, sub: String, icon: Int, online: Boolean?, lastUsed: Boolean,
                    enabled: Boolean = true, target: String? = null, onClick: () -> Unit): View {
        val v = b.targets.inflate(R.layout.item_target)
        v.findViewById<TextView>(R.id.name).text = name
        v.findViewById<TextView>(R.id.sub).text = sub
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<View>(R.id.dot).visibility = if (online == true) View.VISIBLE else View.GONE
        v.findViewById<View>(R.id.last).visibility =
            if (!selecting && lastUsed && (devices.isNotEmpty() || Mesh.peers().isNotEmpty())) View.VISIBLE else View.GONE
        val check = v.findViewById<CompoundButton>(R.id.check)
        check.visibility = if (selecting && target != null) View.VISIBLE else View.GONE
        check.isChecked = target != null && target in chosen
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.45f
        v.setOnClickListener {
            if (selecting && target != null) {
                if (chosen.remove(target) == null) chosen[target] = name
                renderTargets()
            } else onClick()
        }
        return v
    }

    /** Each ticked device gets its own transfer and its own result, in one notification. */
    private fun sendSeveral() {
        val all = chosen.entries.map { it.key to it.value }
        if (all.isEmpty()) return
        try {
            UploadService.start(this, files, text, all[0].first, all[0].second, link = link, more = all.drop(1))
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.share_unreadable, Toast.LENGTH_LONG).show()
        }
        sheet.setOnDismissListener(null)
        sheet.dismiss()
        finish()
    }

    private fun send(to: String, toName: String) {
        Prefs.lastTarget = to
        try {
            UploadService.start(this, files, text, to, toName, link = link)
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.share_unreadable, Toast.LENGTH_LONG).show()
        }
        sheet.setOnDismissListener(null)
        sheet.dismiss()
        finish()
    }

    private fun summary(): String {
        val parts = mutableListOf<String>()
        if (files.isNotEmpty()) {
            val first = files.first().name
            val names = if (files.size == 1) first else getString(R.string.share_more, first, files.size - 1)
            val total = files.sumOf { maxOf(it.size, 0) }
            parts += if (total > 0) "$names · ${Formatter.formatShortFileSize(this, total)}" else names
        }
        text?.let { parts += if (link) getString(R.string.share_link_summary, it.take(120)) else "“" + it.replace('\n', ' ').take(120) + "”" }
        return parts.joinToString("\n")
    }

    private fun sharedUris(intent: Intent): List<Uri> {
        val out = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        }
        if (out.isEmpty()) {
            // some apps only fill ClipData
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it } }
        }
        return out.distinct()
    }

    /** Subject + text, as apps fill them inconsistently (often repeating the link). */
    private fun sharedText(intent: Intent): String? {
        val body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim().orEmpty()
        val combined = when {
            body.isEmpty() -> subject
            subject.isEmpty() || body.contains(subject) -> body
            else -> "$subject\n$body"
        }
        return combined.ifEmpty { null }
    }

    /**
     * The real file name and size. Xiaomi's Gallery shares content URIs whose
     * last path segment is a number, so the display name is what matters.
     */
    private fun describe(uri: Uri): Outgoing? = try { describeOrThrow(uri) } catch (e: Exception) {
        // not readable: the sharing app didn't grant access, or it's gone
        unreadable++
        null
    }

    private fun describeOrThrow(uri: Uri): Outgoing {
        var name: String? = null
        var size = -1L
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !c.isNull(it) }?.let { name = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }
        }
        // opening it proves this app may read it (and gives the size when the query didn't)
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { fd ->
            if (size < 0 && fd.length >= 0) size = fd.length
        } ?: throw java.io.FileNotFoundException(uri.toString())
        val mime = contentResolver.getType(uri)
        var n = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "shared"
        if (!n.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { n = "$n.$it" }
        }
        return Outgoing(uri, n, size, mime)
    }
}

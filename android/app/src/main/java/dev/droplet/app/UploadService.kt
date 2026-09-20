package dev.droplet.app

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends shared files and text to the hub, one share at a time, with a
 * progress notification. A foreground service, so a big video keeps
 * going after the share sheet closes.
 */
class UploadService : Service() {
    private data class Share(val files: List<Outgoing>, val text: String?, val to: String, val toName: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Share>(Channel.UNLIMITED)
    private val pending = AtomicInteger()
    private var lastStartId = 0
    @Volatile private var current: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (share in queue) {
                val job = launch { run(share) }
                current = job
                job.join()
                current = null
                withContext(Dispatchers.Main) {
                    if (pending.decrementAndGet() == 0) {
                        ServiceCompat.stopForeground(this@UploadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf(lastStartId)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // must be foreground within seconds of startForegroundService, whatever happens next
        ServiceCompat.startForeground(this, Notifs.ID_UPLOAD, progress(getString(R.string.up_preparing), null, 0, 0),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (intent?.action == ACTION_CANCEL) {
            current?.cancel()
            if (pending.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        val share = intent?.let { parse(it) }
        if (share == null) {
            if (pending.get() == 0) stopSelf(startId)
            return START_NOT_STICKY
        }
        pending.incrementAndGet()
        queue.trySend(share)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun parse(intent: Intent): Share? {
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java) ?: arrayListOf()
        val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(0)
        val mimes = intent.getStringArrayExtra(EXTRA_MIMES) ?: emptyArray()
        val files = uris.mapIndexed { i, u ->
            Outgoing(u, names.getOrNull(i) ?: "shared", sizes.getOrElse(i) { -1L }, mimes.getOrNull(i)?.ifEmpty { null })
        }
        val text = intent.getStringExtra(EXTRA_TEXT)
        val to = intent.getStringExtra(EXTRA_TO) ?: return null
        if (files.isEmpty() && text == null) return null
        return Share(files, text, to, intent.getStringExtra(EXTRA_TO_NAME) ?: to)
    }

    /** The mesh peer a share goes to: a "mesh:<fp>" target, or a hub device the mesh also knows. */
    private suspend fun meshTarget(to: String): String? {
        if (to == "hub") return null
        val direct = to.startsWith(PeersActivity.MESH_PREFIX)
        if (!direct && !Prefs.meshEnabled) return null
        // the node may still be starting
        for (i in 0 until 100) {
            val n = Mesh.node
            if (n != null) return if (direct) to.removePrefix(PeersActivity.MESH_PREFIX) else Mesh.peerById(to)?.fp
            if (Mesh.state.value.status == Mesh.Status.FAILED || Mesh.state.value.status == Mesh.Status.OFF && i > 5) break
            kotlinx.coroutines.delay(100)
        }
        if (direct) throw HubException(getString(R.string.mesh_off_now))
        return null
    }

    /**
     * Through the mesh's routes (docs/mesh.md §5): directly if the peer is
     * reachable, else the hub, else kept in the outbox until one of them is.
     */
    private suspend fun runMesh(share: Share, fp: String, files: List<Outgoing>) {
        val n = Mesh.node ?: throw HubException(getString(R.string.mesh_off_now))
        val where = share.toName
        val routes = mutableListOf<String>()
        var kept = false
        val jobs = mutableListOf<Pair<String, Outgoing?>>()
        try {
            for (f in files) jobs += n.sendFile(fp, if (f.uri.scheme == "file") f.uri.path!! else f.uri.toString(), f.name, f.mime ?: "application/octet-stream", f.size).getString("id") to f
            share.text?.let { jobs += n.sendText(fp, it).getString("id") to null }
            val title = getString(R.string.up_sending, where)
            for ((id, f) in jobs) {
                while (true) {
                    val j = withContext(Dispatchers.IO) { n.awaitJob(id, 500) } ?: throw HubException("The transfer was lost")
                    if (f != null && f.size > 0) {
                        val done = n.jobSent(id).coerceAtMost(f.size)
                        val pct = (done * 100 / f.size).toInt()
                        notifyProgress(title, getString(R.string.up_progress, pct, Formatter.formatShortFileSize(this, done),
                            Formatter.formatShortFileSize(this, f.size)), pct, 100)
                    } else notifyProgress(title, getString(R.string.up_text), 0, 0)
                    when (j.optString("state")) {
                        dev.droplet.app.mesh.Outbox.DONE -> { routes += j.optString("route"); break }
                        dev.droplet.app.mesh.Outbox.FAILED -> throw HubException(j.optString("error").ifEmpty { "failed" })
                        dev.droplet.app.mesh.Outbox.QUEUED -> if (j.optInt("attempts") > 0 && !j.optBoolean("retry")) { kept = true; break }
                    }
                }
                // a job that has to wait holds the ones after it: they wait too
                if (kept) break
            }
        } catch (e: CancellationException) {
            for ((id, _) in jobs) n.outbox.update(id, "state" to dev.droplet.app.mesh.Outbox.FAILED, "error" to "cancelled")
            throw e
        }
        val what = when {
            files.isEmpty() -> getString(R.string.up_what_text)
            files.size == 1 -> files[0].name
            else -> resources.getQuantityString(R.plurals.up_files, files.size, files.size)
        }
        if (kept) done(getString(R.string.up_kept, where), getString(R.string.up_kept_body, where), ok = true)
        else done(getString(R.string.up_done_route, where, routes.distinct().joinToString(", ") { Mesh.describeRoute(this, it) }), what, ok = true)
    }

    private suspend fun run(share: Share) {
        Mesh.hold(MESH_TAG)
        try {
            runShare(share)
        } finally {
            Mesh.release(MESH_TAG)
        }
    }

    private suspend fun runShare(share: Share) {
        val where = if (share.to == "hub") getString(R.string.up_the_hub) else share.toName
        val temp = mutableListOf<File>()
        try {
            val fp = try {
                meshTarget(share.to)
            } catch (e: HubException) {
                done(getString(R.string.up_failed, where), e.message.orEmpty(), ok = false)
                return
            }
            if (fp != null) {
                val files = share.files.map { f -> if (f.size >= 0) f else spool(f).also { temp += File(it.uri.path!!) } }
                // a spooled copy is only this service's: the mesh keeps its own if it has to wait
                runMesh(share, fp, files)
                return
            }
            var sent = emptyList<String>()
            if (share.files.isNotEmpty()) {
                val title = getString(R.string.up_sending, where)
                notifyProgress(title, getString(R.string.up_preparing), 0, 0)
                // the multipart body needs every size up front; the odd app that
                // won't say gets its file copied here first
                val files = share.files.map { f -> if (f.size >= 0) f else spool(f).also { temp += File(it.uri.path!!) } }
                var lastUpdate = 0L
                sent = Hub.upload(this, files, share.to) { done, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 400 || done == total) {
                        lastUpdate = now
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        notifyProgress(title, getString(R.string.up_progress, pct,
                            Formatter.formatShortFileSize(this, done), Formatter.formatShortFileSize(this, total)), pct, 100)
                    }
                }
            }
            share.text?.let {
                notifyProgress(getString(R.string.up_sending, where), getString(R.string.up_text), 0, 0)
                Hub.sendText(it, share.to)
            }
            val what = when {
                share.files.isEmpty() -> getString(R.string.up_what_text)
                share.files.size == 1 -> share.files[0].name
                else -> resources.getQuantityString(R.plurals.up_files, share.files.size, share.files.size)
            }
            if (share.files.isNotEmpty() && sent.isEmpty()) throw HubException("The hub didn't save anything")
            done(getString(R.string.up_done, where), what, ok = true)
        } catch (e: CancellationException) {
            done(getString(R.string.up_cancelled), where, ok = false)
            throw e
        } catch (e: Exception) {
            done(getString(R.string.up_failed, where), e.message ?: e.javaClass.simpleName, ok = false)
        } finally {
            temp.forEach { it.delete() }
        }
    }

    private fun spool(f: Outgoing): Outgoing {
        val dir = File(cacheDir, "spool").apply { mkdirs() }
        val out = File.createTempFile("up", null, dir)
        val input = contentResolver.openInputStream(f.uri) ?: throw IOException("Can't read ${f.name}")
        input.use { i -> out.outputStream().use { i.copyTo(it) } }
        return f.copy(uri = Uri.fromFile(out), size = out.length())
    }

    private fun progress(title: String, text: String?, value: Int, max: Int): Notification =
        NotificationCompat.Builder(this, Notifs.CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(max, value, max == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.cancel), PendingIntent.getService(this, 0,
                Intent(this, UploadService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE))
            .build()

    private fun notifyProgress(title: String, text: String, value: Int, max: Int) {
        if (Notifs.allowed(this)) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(this).notify(Notifs.ID_UPLOAD, progress(title, text, value, max))
        }
    }

    private suspend fun done(title: String, text: String, ok: Boolean) {
        if (Notifs.allowed(this)) {
            val n = NotificationCompat.Builder(this, Notifs.CH_TRANSFERS)
                .setSmallIcon(R.drawable.ic_drop)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(Notifs.openApp(this))
                .setPriority(if (ok) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_DEFAULT)
                .build()
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(this).notify(Notifs.newId(), n)
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@UploadService, "$title: $text", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val ACTION_CANCEL = "dev.droplet.app.CANCEL_UPLOAD"
        private const val MESH_TAG = "upload"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_SIZES = "sizes"
        private const val EXTRA_MIMES = "mimes"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TO = "to"
        private const val EXTRA_TO_NAME = "to_name"

        fun start(context: Context, files: List<Outgoing>, text: String?, to: String, toName: String) {
            val intent = Intent(context, UploadService::class.java).apply {
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(files.map { it.uri }))
                putExtra(EXTRA_NAMES, files.map { it.name }.toTypedArray())
                putExtra(EXTRA_SIZES, files.map { it.size }.toLongArray())
                putExtra(EXTRA_MIMES, files.map { it.mime.orEmpty() }.toTypedArray())
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_TO, to)
                putExtra(EXTRA_TO_NAME, toName)
                // hand the sharing app's read permission on to the service,
                // so the upload outlives the share sheet
                if (files.isNotEmpty()) {
                    val clip = ClipData.newRawUri(null, files[0].uri)
                    files.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
                    clipData = clip
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

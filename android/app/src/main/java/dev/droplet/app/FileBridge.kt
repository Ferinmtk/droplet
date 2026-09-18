package dev.droplet.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The phone's files for other devices (docs/remote.md §3.4, `files.*`).
 *
 * Plain paths under shared storage, which Android 11+ allows once the owner
 * grants "All files access". MediaStore was the alternative, but it only
 * shows other apps' photos, videos and audio: a PDF in Downloads or a file
 * in Documents that another app saved stays invisible to it. With direct
 * paths the listing is exactly what a file manager shows.
 *
 * Only these folders are reachable, and nothing outside them: paths are
 * relative to the roots, `..` is refused, and every path is checked again
 * after resolving symlinks.
 */
object FileBridge {
    private class Root(val path: String, val name: String, val dir: File)

    private const val MAX_ENTRIES = 3_000   // a Camera folder can hold tens of thousands
    private const val LIST_BUDGET = 400_000 // characters, under the hub's 512 KB frame cap
    private const val UPLOAD_WAIT_MS = 20_000L

    private fun roots(): List<Root> {
        val top = Environment.getExternalStorageDirectory()
        return listOf(
            Triple("/Download", "Downloads", Environment.DIRECTORY_DOWNLOADS),
            Triple("/DCIM/Camera", "Camera", "${Environment.DIRECTORY_DCIM}/Camera"),
            Triple("/Pictures", "Pictures", Environment.DIRECTORY_PICTURES),
            Triple("/Documents", "Documents", Environment.DIRECTORY_DOCUMENTS),
            Triple("/Music", "Music", Environment.DIRECTORY_MUSIC),
            Triple("/Movies", "Movies", Environment.DIRECTORY_MOVIES),
        ).map { (path, name, rel) -> Root(path, name, File(top, rel)) }
    }

    suspend fun call(context: Context, method: String, params: JSONObject, from: JSONObject?): JSONObject = when (method) {
        "files.roots" -> JSONObject().put("roots", JSONArray(roots().filter { it.dir.isDirectory }.map {
            JSONObject().put("path", it.path).put("name", it.name)
        }))
        "files.list" -> list(params.optString("path"))
        "files.get" -> get(context, params.optString("path"), from)
        else -> throw RpcError("The phone doesn't know $method")
    }

    /** The file for a protocol path, or an error if it's outside the roots. */
    private fun resolve(path: String): Pair<String, File> {
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (!path.startsWith("/") || parts.any { it == ".." || it == "." || '\u0000' in it || '\\' in it })
            throw RpcError("That path isn't allowed")
        val clean = "/" + parts.joinToString("/")
        val root = roots().filter { r -> clean == r.path || clean.startsWith(r.path + "/") }.maxByOrNull { it.path.length }
            ?: throw RpcError("That path isn't allowed")
        val file = File(root.dir, clean.removePrefix(root.path).trimStart('/'))
        // symlinks can't lead out either
        val base = root.dir.canonicalPath
        val real = file.canonicalPath
        if (real != base && !real.startsWith("$base/")) throw RpcError("That path isn't allowed")
        return clean to file
    }

    private fun list(path: String): JSONObject {
        val (clean, dir) = resolve(path)
        if (!dir.isDirectory) throw RpcError("No such folder")
        val all = (dir.listFiles() ?: throw RpcError("Can't read that folder. Is All files access still allowed?"))
            .filter { !it.name.startsWith(".") }  // .thumbnails, .trashed-…, .pending-…
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }, { it.name }))
        val entries = JSONArray()
        var budget = LIST_BUDGET
        var shown = 0
        for (f in all) {
            if (shown >= MAX_ENTRIES) break
            val dirEntry = f.isDirectory
            val e = JSONObject()
                .put("name", f.name)
                .put("dir", dirEntry)
                .put("size", if (dirEntry) 0 else f.length())
                .put("mtime", f.lastModified() / 1000)
            budget -= e.toString().length + 1
            if (budget < 0) break
            entries.put(e)
            shown++
        }
        return JSONObject().put("path", clean).put("entries", entries).apply {
            if (shown < all.size) put("truncated", true).put("total", all.size)
        }
    }

    /**
     * Uploads the file to the device that asked, into its "For this device"
     * list. Waits up to 20 s so small files report real success or failure;
     * a big one keeps going and the answer says it's on its way.
     */
    private suspend fun get(context: Context, path: String, from: JSONObject?): JSONObject {
        val to = from?.optString("id")?.takeIf { it.isNotEmpty() } ?: throw RpcError("No one to send it to")
        val (_, file) = resolve(path)
        if (!file.isFile) throw RpcError("No such file")
        if (!file.canRead()) throw RpcError("Can't read that file. Is All files access still allowed?")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
        val item = Outgoing(Uri.fromFile(file), file.name, file.length(), mime)
        val upload = Live.scope.async {
            val wake = context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "droplet:files-get").apply { acquire(30 * 60_000L) }
            val note = Notifs.newId()
            try {
                progress(context, note, file.name, from.optString("name", "another device"), 0)
                var last = 0L
                Hub.upload(context, listOf(item), to) { sent, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - last > 1_000 && total > 0) {
                        last = now
                        progress(context, note, file.name, from.optString("name"), (sent * 100 / total).toInt())
                    }
                }.firstOrNull() ?: throw RpcError("The hub didn't save it")
            } finally {
                NotificationManagerCompat.from(context).cancel(note)
                if (wake.isHeld) wake.release()
            }
        }
        val finished = withTimeoutOrNull(UPLOAD_WAIT_MS) { upload.join(); true } ?: false
        if (!finished) return JSONObject().put("ok", true).put("name", file.name).put("pending", true)
        val done = runCatching { upload.await() }  // already complete: returns or throws at once
        return done.fold(
            onSuccess = { JSONObject().put("ok", true).put("name", it) },
            onFailure = { throw RpcError(it.message ?: "The upload failed") },
        )
    }

    /** Shows that another device is taking a file off the phone. */
    @SuppressLint("MissingPermission")
    private fun progress(context: Context, id: Int, name: String, who: String, pct: Int) {
        if (!Notifs.allowed(context)) return
        val n = NotificationCompat.Builder(context, Notifs.CH_TRANSFERS)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(context.getString(R.string.files_sending, who))
            .setContentText(name)
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

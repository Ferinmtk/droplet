package dev.droplet.app.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * Chat messages and files waiting for a route (docs/mesh.md §5, route 5),
 * kept in app storage so a send survives the app being killed. Each peer's
 * jobs go out in order.
 *
 * A file job refers to the file where it is: a content URI the share sheet
 * granted, or (once it has had to wait) a copy in the app's own storage,
 * since a URI grant doesn't outlive the process.
 */
class Outbox(private val file: File) {
    private val jobs = LinkedHashMap<String, JSONObject>()
    private val finished = LinkedHashMap<String, JSONObject>()
    private val lock = Object()

    init {
        runCatching { JSONArray(file.readText()) }.getOrNull()?.let { a ->
            for (i in 0 until a.length()) {
                val j = a.optJSONObject(i) ?: continue
                if (j.optString("id").isEmpty() || j.optString("kind") !in setOf("text", "file")) continue
                j.put("state", QUEUED)   // whatever it was doing when the app stopped, it's waiting now
                jobs[j.getString("id")] = j
            }
        }
    }

    private fun save() {
        file.parentFile?.mkdirs()
        MeshIdentity.writePrivate(file, JSONArray(jobs.values.sortedBy { it.optDouble("created") }).toString().toByteArray())
    }

    fun addText(fp: String, peer: String, body: String): JSONObject =
        add(JSONObject().put("kind", "text").put("body", body), fp, peer)

    /** [source] is a content URI or a file path; [spooled] when it's the app's own copy. */
    fun addFile(fp: String, peer: String, source: String, name: String, mime: String, size: Long, spooled: Boolean,
                id: String? = null): JSONObject =
        add(JSONObject().put("kind", "file").put("source", source).put("name", name).put("mime", mime).put("size", size)
            .put("spooled", spooled), fp, peer, id)

    private fun add(fields: JSONObject, fp: String, peer: String, id: String? = null): JSONObject {
        val job = JSONObject(fields.toString()).put("id", id ?: newId()).put("fp", fp).put("peer", peer)
            .put("created", System.currentTimeMillis() / 1000.0).put("state", QUEUED).put("attempts", 0)
        synchronized(lock) {
            jobs[job.getString("id")] = job
            save()
            lock.notifyAll()
        }
        return JSONObject(job.toString())
    }

    fun get(id: String): JSONObject? = synchronized(lock) { (jobs[id] ?: finished[id])?.let { JSONObject(it.toString()) } }

    fun queued(): List<JSONObject> = synchronized(lock) {
        jobs.values.sortedBy { it.optDouble("created") }.map { JSONObject(it.toString()) }
    }

    fun forPeer(fp: String): List<JSONObject> = queued().filter { it.optString("fp") == fp }

    fun update(id: String, vararg fields: Pair<String, Any?>) {
        synchronized(lock) {
            val j = jobs[id] ?: return
            fields.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) }
            if (j.optString("state") == DONE || j.optString("state") == FAILED) {
                finished[id] = jobs.remove(id)!!
                while (finished.size > 200) finished.remove(finished.keys.first())
            }
            save()
            lock.notifyAll()
        }
    }

    /** Waits until [until] holds for the job, or [timeoutMs] passes. Returns the job. */
    fun await(id: String, timeoutMs: Long, until: (JSONObject) -> Boolean): JSONObject? {
        val end = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                val j = jobs[id] ?: finished[id] ?: return null
                if (until(j)) return JSONObject(j.toString())
                val left = end - System.currentTimeMillis()
                if (left <= 0) return JSONObject(j.toString())
                lock.wait(left)
            }
        }
    }

    companion object {
        const val QUEUED = "queued"
        const val SENDING = "sending"
        const val DONE = "done"
        const val FAILED = "failed"
        fun newId(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

/** Direct chat, one JSON line a message. Ids make repeats harmless. */
class Chat(private val file: File) {
    private val ids = HashSet<String>()
    @Volatile var onAdded: ((JSONObject) -> Unit)? = null

    init {
        runCatching {
            file.forEachLine { line -> runCatching { JSONObject(line).optString("id") }.getOrNull()?.let { ids.add(it) } }
        }
    }

    /** Stores it; false if a message with that id is already there. */
    fun add(entry: JSONObject): Boolean {
        synchronized(this) {
            val id = entry.getString("id")
            val key = "${entry.optString("dir")}:$id"
            if (key in ids || id in ids) return false
            ids.add(key)
            ids.add(id)
            file.parentFile?.mkdirs()
            file.appendText(entry.toString() + "\n")
        }
        onAdded?.invoke(entry)
        return true
    }

    fun recent(fp: String? = null, n: Int = 200): List<JSONObject> = synchronized(this) {
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { fp == null || it.optString("fp") == fp }
            .takeLast(n)
    }
}

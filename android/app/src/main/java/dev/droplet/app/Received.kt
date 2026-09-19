package dev.droplet.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Files your devices sent this phone directly, newest first, for the home
 * screen's "Received" list: where each went and how to open it. Kept next
 * to the mesh's own files; only the last [KEEP].
 */
object Received {
    data class Item(val name: String, val from: String, val fp: String, val uri: String?, val mime: String,
                    val where: String, val ts: Long)

    private const val KEEP = 30

    private fun file() = File(Mesh.dir(), "received-files.json")

    @Synchronized
    fun add(item: Item) {
        val list = listOf(item) + load()
        save(list.take(KEEP))
    }

    @Synchronized
    fun recent(n: Int = KEEP): List<Item> = load().take(n)

    @Synchronized
    fun clear() = save(emptyList())

    private fun load(): List<Item> {
        val a = runCatching { JSONArray(file().readText()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Item(o.optString("name"), o.optString("from"), o.optString("fp"), o.optString("uri").ifEmpty { null },
                o.optString("mime").ifEmpty { "application/octet-stream" }, o.optString("where"), o.optLong("ts"))
        }
    }

    private fun save(list: List<Item>) {
        val f = file()
        f.parentFile?.mkdirs()
        val a = JSONArray()
        for (it in list) a.put(JSONObject().put("name", it.name).put("from", it.from).put("fp", it.fp)
            .put("uri", it.uri ?: "").put("mime", it.mime).put("where", it.where).put("ts", it.ts))
        val tmp = File(f.path + ".tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(f)
    }
}

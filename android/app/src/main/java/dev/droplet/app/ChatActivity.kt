package dev.droplet.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.droplet.app.databinding.ActivityChatBinding
import dev.droplet.app.mesh.Outbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Messages with one device, sent directly (docs/mesh.md §6): what arrived
 * and what went, plus anything still waiting in the outbox. A message to a
 * device that's away goes through the hub, or waits here until it's back.
 */
class ChatActivity : AppCompatActivity() {
    private lateinit var b: ActivityChatBinding
    private lateinit var fp: String

    override fun onCreate(savedInstanceState: Bundle?) {
        edgeToEdge()
        super.onCreate(savedInstanceState)
        fp = intent.getStringExtra(EXTRA_FP) ?: run { finish(); return }
        b = ActivityChatBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()
        b.toolbar.setNavigationOnClickListener { finish() }
        b.send.setOnClickListener { send() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Mesh.changes.collect { render() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Mesh.hold(TAG)
        showing = fp
        NotificationManagerCompat.from(this).cancel("chat-${fp.take(16)}", 0)
    }

    override fun onStop() {
        if (showing == fp) showing = null
        Mesh.release(TAG)
        super.onStop()
    }

    private fun render() {
        val n = Mesh.node
        val peer = n?.trust?.get(fp)
        b.toolbar.title = peer?.name ?: getString(R.string.mesh_act_text)
        b.route.text = peer?.let { Mesh.describeRoute(this, n.route(it)) } ?: ""
        b.send.isEnabled = peer != null
        val msgs = n?.chat?.recent(fp).orEmpty()
        val waiting = n?.outbox?.forPeer(fp).orEmpty().filter { it.optString("kind") == "text" }
        b.messages.removeAllViews()
        if (msgs.isEmpty() && waiting.isEmpty()) {
            b.messages.addView(bubble(getString(R.string.mesh_chat_empty, peer?.name ?: "?"), null, mine = false, dim = true))
        }
        for (m in msgs) {
            val mine = m.optString("dir") == "out"
            val meta = DateUtils.getRelativeTimeSpanString((m.optDouble("ts") * 1000).toLong()).toString() +
                (m.optString("route").takeIf { mine && it.isNotEmpty() && it != "null" }?.let { " · " + Mesh.describeRoute(this, it) } ?: "")
            b.messages.addView(bubble(m.optString("body"), meta, mine))
        }
        for (j in waiting) {
            val meta = when (j.optString("state")) {
                Outbox.SENDING -> getString(R.string.mesh_chat_sending)
                else -> getString(R.string.mesh_chat_waiting, j.optString("error").takeIf { it != "null" }.orEmpty())
            }
            b.messages.addView(bubble(j.optString("body"), meta, mine = true, dim = true))
        }
        b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun bubble(text: String, meta: String?, mine: Boolean, dim: Boolean = false): LinearLayout {
        val pad = (10 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (mine) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = pad / 2 }
        }
        box.addView(TextView(this).apply {
            this.text = text
            setTextIsSelectable(true)
            setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
            setBackgroundResource(R.drawable.card_bg)
            setTextColor(ContextCompat.getColor(this@ChatActivity, if (dim) R.color.dim else R.color.text))
            textSize = 15f
        })
        if (meta != null) box.addView(TextView(this).apply {
            this.text = meta
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@ChatActivity, R.color.dim))
        })
        return box
    }

    private fun send() {
        val text = b.input.text.toString().trim()
        if (text.isEmpty()) return
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Mesh.node?.sendText(fp, text) ?: error(getString(R.string.mesh_off_now)) } }
            r.onSuccess { b.input.text.clear() }
                .onFailure { Toast.makeText(this@ChatActivity, getString(R.string.mesh_failed, it.message), Toast.LENGTH_LONG).show() }
            render()
        }
    }

    companion object {
        private const val EXTRA_FP = "fp"
        private const val TAG = "chat"

        /** The peer whose chat is on screen: its messages don't notify. */
        @Volatile var showing: String? = null

        fun intent(context: Context, fp: String): Intent =
            Intent(context, ChatActivity::class.java).putExtra(EXTRA_FP, fp)
    }
}

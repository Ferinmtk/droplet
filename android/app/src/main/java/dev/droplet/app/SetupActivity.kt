package dev.droplet.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.droplet.app.databinding.ActivitySetupBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** First launch (and "Change" in settings): where is the hub? */
class SetupActivity : AppCompatActivity() {
    private lateinit var b: ActivitySetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        edgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars()

        b.url.setText(Prefs.hubUrl ?: DEFAULT_HUB)
        b.url.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO) { connect(); true } else false
        }
        b.connect.setOnClickListener { connect() }
        b.anyway.setOnClickListener { Hub.normalize(b.url.text.toString())?.let { done(it) } }
    }

    private fun connect() {
        val url = Hub.normalize(b.url.text.toString())
        if (url == null) {
            showError(getString(R.string.setup_bad_url), allowAnyway = false)
            return
        }
        b.url.setText(url)
        b.error.visibility = View.GONE
        b.anyway.visibility = View.GONE
        b.connect.isEnabled = false
        b.connect.setText(R.string.setup_checking)
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) { Hub.probe(url) }
            b.connect.isEnabled = true
            b.connect.setText(R.string.setup_connect)
            when (problem) {
                null -> done(url)
                "not-droplet" -> showError(getString(R.string.setup_not_droplet, url), allowAnyway = true)
                else -> showError(getString(R.string.setup_unreachable, url, problem), allowAnyway = true)
            }
        }
    }

    private fun showError(msg: String, allowAnyway: Boolean) {
        b.error.text = msg
        b.error.visibility = View.VISIBLE
        b.anyway.visibility = if (allowAnyway) View.VISIBLE else View.GONE
    }

    private fun done(url: String) {
        val changed = url != Prefs.hubUrl
        Prefs.hubUrl = url
        if (changed) {
            // a different hub has different devices and chats
            Prefs.seenInbox = emptySet()
            Prefs.seenUnread = emptyMap()
            Prefs.inboxPrimed = false
            if (Prefs.stayConnected) ConnectionService.start(this)
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        const val DEFAULT_HUB = "https://t15.tail7375fe.ts.net"
    }
}

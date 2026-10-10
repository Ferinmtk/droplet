package dev.droplet.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import dev.droplet.app.databinding.ActivityPermissionsBinding
import dev.droplet.app.mesh.Perms
import kotlinx.coroutines.launch

/**
 * What one device may do with this phone (docs/mesh.md §9.9): whether it's
 * the owner's device or someone else's (which sets the defaults), Pause, and
 * a switch for each capability, each with a line saying what it covers.
 * Opened from a device's ⋮ menu on the home screen.
 */
class PermissionsActivity : AppCompatActivity() {
    private lateinit var b: ActivityPermissionsBinding
    private lateinit var fp: String
    /** Set while the screen draws, so its own changes to the switches aren't taken as the owner's. */
    private var drawing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        fp = intent.getStringExtra(EXTRA_FP) ?: return finish()
        b = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = false)
        b.back.setOnClickListener { finish() }
        b.own.text = twoLines(getString(R.string.perm_own), getString(R.string.perm_own_sub))
        b.other.text = twoLines(getString(R.string.perm_other), getString(R.string.perm_other_sub))
        b.relation.setOnCheckedChangeListener { _, id ->
            if (drawing) return@setOnCheckedChangeListener
            change(relation = if (id == R.id.other) Perms.OTHER_DEVICE else Perms.OWN_DEVICE)
        }
        b.paused.setOnCheckedChangeListener { _, on -> if (!drawing) change(paused = on) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Mesh.changes.collect { render() } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::b.isInitialized) Mesh.hold(TAG)
    }

    override fun onStop() {
        if (::b.isInitialized) Mesh.release(TAG)
        super.onStop()
    }

    private fun change(relation: String? = null, allow: Map<String, Boolean>? = null, paused: Boolean? = null) {
        runCatching { Mesh.setPerms(fp, relation, allow, paused) }
            .onFailure { Snackbar.make(b.root, getString(R.string.mesh_failed, it.message ?: "?"), Snackbar.LENGTH_LONG).show() }
        render()
    }

    private fun render() {
        if (!::b.isInitialized || isFinishing) return
        val p = Mesh.peerView(fp)
        if (p == null) {
            // unpaired meanwhile, or the mesh isn't up yet: wait for it (Mesh.changes redraws)
            if (Mesh.node != null && Mesh.peer(fp) == null) finish()
            return
        }
        val e = p.entry
        drawing = true
        try {
            b.title.text = e.name
            b.relation.check(if (e.isOther) R.id.other else R.id.own)
            b.paused.text = getString(R.string.perm_pause, e.name)
            b.paused.isChecked = e.paused
            b.capsTitle.text = getString(R.string.perm_caps, e.name)
            b.caps.removeAllViews()
            for ((i, cap) in Perms.CAPABILITIES.withIndex()) b.caps.addView(capRow(cap, e.allow[cap] ?: true, i > 0))
            val note = when {
                Mesh.pausedAll -> getString(R.string.perm_paused_all_note)
                p.pausedThere -> getString(R.string.perm_remote_paused, e.name)
                else -> p.remote?.allow?.filterValues { !it }?.keys?.takeIf { it.isNotEmpty() }?.let { off ->
                    getString(R.string.perm_remote_denied, e.name,
                        off.joinToString(", ") { Perms.NOUNS[it] ?: it })
                }
            }
            b.note.visibility = if (note != null) View.VISIBLE else View.GONE
            b.note.text = note
        } finally {
            drawing = false
        }
    }

    private fun capRow(cap: String, on: Boolean, divider: Boolean): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            tag = cap
        }
        if (divider) box.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(context, R.color.r_line))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(10) })
        val sw = MaterialSwitch(this).apply {
            text = PermsUi.label(context, cap)
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.r_text))
            isChecked = on
            contentDescription = PermsUi.label(context, cap)
            setOnCheckedChangeListener { _, now -> if (!drawing) change(allow = mapOf(cap to now)) }
        }
        box.addView(sw, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(TextView(this).apply {
            text = PermsUi.explain(context, cap)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.r_dim))
        })
        return box
    }

    private fun twoLines(title: String, sub: String): CharSequence = SpannableStringBuilder(title).apply {
        append("\n")
        val start = length
        append(sub)
        setSpan(ForegroundColorSpan(ContextCompat.getColor(this@PermissionsActivity, R.color.r_dim)), start, length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "permissions"
        const val EXTRA_FP = "fp"

        fun intent(context: Context, fp: String): Intent =
            Intent(context, PermissionsActivity::class.java).putExtra(EXTRA_FP, fp)
    }
}

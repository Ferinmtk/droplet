package dev.droplet.app

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.droplet.app.databinding.ActivityExcludedBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tick the apps whose notifications should stay on the phone. */
class ExcludedAppsActivity : AppCompatActivity() {
    private data class App(val pkg: String, val label: String, val icon: Drawable?)

    private lateinit var b: ActivityExcludedBinding
    private var apps: List<App> = emptyList()
    private val excluded = Prefs.excluded.toMutableSet()

    override fun onCreate(savedInstanceState: Bundle?) {
        edgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivityExcludedBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = false)
        b.toolbar.setNavigationOnClickListener { finish() }

        val adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(position: Int) = apps[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = convertView ?: parent.inflate(R.layout.item_app)
                val app = apps[position]
                v.findViewById<ImageView>(R.id.icon).setImageDrawable(app.icon)
                v.findViewById<TextView>(R.id.label).text = app.label
                v.findViewById<TextView>(R.id.pkg).text = app.pkg
                v.findViewById<CheckBox>(R.id.check).isChecked = app.pkg in excluded
                return v
            }
        }
        b.list.adapter = adapter
        b.list.setOnItemClickListener { _, _, position, _ ->
            val pkg = apps[position].pkg
            if (!excluded.remove(pkg)) excluded += pkg
            Prefs.excluded = excluded.toSet()
            adapter.notifyDataSetChanged()
        }

        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) { load() }
            b.loading.visibility = View.GONE
            adapter.notifyDataSetChanged()
        }
    }

    /** Apps with a launcher icon, plus any that have posted a notification. */
    private fun load(): List<App> {
        val pm = packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
        val pkgs = (launchable + Prefs.seenPackages + excluded).toSet() - packageName
        return pkgs.mapNotNull { pkg ->
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
            if (info == null && pkg !in excluded) return@mapNotNull null
            App(pkg, info?.let { pm.getApplicationLabel(it).toString() } ?: pkg, info?.let { pm.getApplicationIcon(it) })
        }.sortedWith(compareBy({ it.pkg !in excluded }, { it.label.lowercase() }))
    }
}

package dev.droplet.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.droplet.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private val xiaomi = Build.MANUFACTURER.equals("Xiaomi", true) || Build.BRAND.equals("Redmi", true) ||
        Build.BRAND.equals("POCO", true)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) openAppNotificationSettings()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        edgeToEdge()
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.root.padForSystemBars(keyboard = false)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.changeHub.setOnClickListener { startActivity(Intent(this, SetupActivity::class.java)) }
        b.open.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }

        b.stay.setOnCheckedChangeListener { _, on ->
            if (on == Prefs.stayConnected) return@setOnCheckedChangeListener
            Prefs.stayConnected = on
            if (on) {
                ConnectionService.start(this)
                askForNotifications()
            } else {
                ConnectionService.stop(this)
            }
            refresh()
        }
        b.inbox.setOnCheckedChangeListener { _, on -> Prefs.notifyInbox = on }
        b.allowNotifs.setOnClickListener { askForNotifications(force = true) }

        b.mirrorButton.setOnClickListener {
            try {
                startActivity(MirrorService.accessSettingsIntent(this))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        b.excludedRow.setOnClickListener { startActivity(Intent(this, ExcludedAppsActivity::class.java)) }

        b.batterySteps.text = getString(if (xiaomi) R.string.s_battery_miui else R.string.s_battery_other)
        b.batteryButton.setOnClickListener { openBatterySettings() }
        b.autostartButton.visibility = if (xiaomi) View.VISIBLE else View.GONE
        b.autostartButton.setOnClickListener { openAutostart() }

        b.about.text = getString(R.string.s_about, BuildConfig.VERSION_NAME)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        loadDevice()
    }

    private fun refresh() {
        b.hubUrl.text = Prefs.hubUrl ?: "—"
        b.stay.isChecked = Prefs.stayConnected
        b.inbox.isChecked = Prefs.notifyInbox
        b.inbox.isEnabled = Prefs.stayConnected
        b.permRow.visibility = if (Notifs.allowed(this)) View.GONE else View.VISIBLE

        val mirroring = MirrorService.isEnabled(this)
        b.mirrorState.setText(if (mirroring) R.string.s_mirror_on else R.string.s_mirror_off)
        b.mirrorDot.setBackgroundResource(if (mirroring) R.drawable.dot_online else R.drawable.dot)
        val n = Prefs.excluded.size
        b.excludedCount.text = if (n == 0) getString(R.string.s_excluded_none) else getString(R.string.s_excluded_some, n)

        val pm = getSystemService(PowerManager::class.java)
        b.batteryState.setText(if (pm.isIgnoringBatteryOptimizations(packageName)) R.string.s_battery_ok else R.string.s_battery_on)
    }

    private fun loadDevice() {
        b.device.setText(R.string.s_checking)
        lifecycleScope.launch {
            val me = withContext(Dispatchers.IO) { runCatching { Hub.me() } }
            b.device.text = me.fold(
                onSuccess = { json ->
                    json.optJSONObject("device")?.optString("name")?.let { getString(R.string.s_named, it) }
                        ?: getString(R.string.s_unnamed)
                },
                onFailure = { getString(R.string.s_hub_unreachable) + (it.message?.let { m -> "\n$m" } ?: "") },
            )
        }
    }

    private fun askForNotifications(force: Boolean = false) {
        if (Notifs.allowed(this)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (force) {
            openAppNotificationSettings()
        }
    }

    private fun openAppNotificationSettings() {
        tryStart(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            || tryStart(appDetails())
    }

    private fun openBatterySettings() {
        val pm = getSystemService(PowerManager::class.java)
        // MIUI's own per-app battery saver ("No restrictions") comes first there
        if (xiaomi && tryStart(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", packageName).putExtra("package_label", getString(R.string.app_name)))) return
        if (!pm.isIgnoringBatteryOptimizations(packageName) &&
            tryStart(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))) return
        tryStart(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) || tryStart(appDetails())
    }

    private fun openAutostart() {
        val ok = tryStart(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            || tryStart(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
            || tryStart(appDetails())
        if (!ok) Toast.makeText(this, "Open Security → Permissions → Autostart", Toast.LENGTH_LONG).show()
    }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

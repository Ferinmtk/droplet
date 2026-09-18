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
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    private val askSms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // denied for good (or MIUI's own permission manager said no): the app's page is the way on
        if (!Caps.smsAllowed(this) && !shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS)) {
            Toast.makeText(this, R.string.s_sms_denied, Toast.LENGTH_LONG).show()
            tryStart(appDetails())
        }
        capsChanged()
    }

    private val askStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { capsChanged() }

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

        setUpRemote()
    }

    // --- remote control ----------------------------------------------------------

    private fun setUpRemote() {
        b.openRemote.setOnClickListener { startActivity(Intent(this, RemoteActivity::class.java)) }
        b.linkRow.setOnClickListener { askLinkCode() }

        b.capMedia.setOnCheckedChangeListener { _, on -> if (on != Prefs.capMedia) { Prefs.capMedia = on; capsChanged() } }
        b.capSms.setOnCheckedChangeListener { _, on -> if (on != Prefs.capSms) { Prefs.capSms = on; capsChanged() } }
        b.capFiles.setOnCheckedChangeListener { _, on -> if (on != Prefs.capFiles) { Prefs.capFiles = on; capsChanged() } }
        b.capClipboard.setOnCheckedChangeListener { _, on -> if (on != Prefs.capClipboard) { Prefs.capClipboard = on; capsChanged() } }

        b.mediaGrant.setOnClickListener { b.mirrorButton.performClick() }
        b.smsGrant.setOnClickListener { askSms.launch(Caps.SMS_PERMISSIONS) }
        b.filesGrant.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 30) {
                tryStart(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                    || tryStart(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } else {
                askStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Live.state.collect { s ->
                    b.liveState.text = s.describe(this@SettingsActivity)
                    b.liveDot.setBackgroundResource(if (s.connected) R.drawable.dot_online else R.drawable.dot)
                }
            }
        }
    }

    private fun capsChanged() {
        refresh()
        Live.refresh()
    }

    private fun refreshCaps() {
        b.capMedia.isChecked = Prefs.capMedia
        b.capSms.isChecked = Prefs.capSms
        b.capFiles.isChecked = Prefs.capFiles
        b.capClipboard.isChecked = Prefs.capClipboard

        val media = Caps.mediaAllowed(this)
        showPerm(b.mediaDot, b.mediaPerm, b.mediaGrant, media,
            getString(if (media) R.string.s_perm_notif_ok else R.string.s_perm_notif_no))

        val phone = Caps.hasTelephony(this)
        val sms = Caps.smsAllowed(this)
        showPerm(b.smsDot, b.smsPerm, b.smsGrant, sms, getString(when {
            !phone -> R.string.s_perm_sms_none
            sms && !Caps.contactsAllowed(this) -> R.string.s_perm_sms_no_names
            sms -> R.string.s_perm_sms_ok
            else -> R.string.s_perm_sms_no
        }))
        if (!phone) b.smsGrant.visibility = View.GONE
        b.capSms.isEnabled = phone

        val files = Caps.filesAllowed(this)
        showPerm(b.filesDot, b.filesPerm, b.filesGrant, files,
            getString(if (files) R.string.s_perm_files_ok else R.string.s_perm_files_no))
    }

    private fun showPerm(dot: View, label: android.widget.TextView, grant: View, ok: Boolean, text: String) {
        label.text = text
        dot.setBackgroundResource(if (ok) R.drawable.dot_online else R.drawable.dot)
        grant.visibility = if (ok) View.GONE else View.VISIBLE
    }

    /** Settings → Link with code: become the device another browser named. */
    private fun askLinkCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            hint = getString(R.string.s_link_hint)
            textSize = 22f
            letterSpacing = 0.3f
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s_link)
            .setMessage(R.string.s_link_help)
            .setView(box)
            .setPositiveButton(R.string.s_link_go) { _, _ ->
                val code = input.text.toString().filter { it.isDigit() }
                if (code.length != 6) {
                    Toast.makeText(this, R.string.s_link_six, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) { runCatching { Hub.link(code) } }
                    res.onSuccess { name ->
                        Toast.makeText(this@SettingsActivity, getString(R.string.s_linked, name), Toast.LENGTH_LONG).show()
                        Live.refresh()
                        loadDevice()
                    }.onFailure {
                        Toast.makeText(this@SettingsActivity, it.message ?: getString(R.string.s_hub_unreachable), Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        loadDevice()
        // back from a system permission screen: announce what changed
        Live.refresh()
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
        refreshCaps()
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

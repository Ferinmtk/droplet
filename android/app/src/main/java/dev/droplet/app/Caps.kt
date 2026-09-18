package dev.droplet.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * What this phone can do for other devices right now: the owner's switch in
 * Settings, and the Android permission behind it. The hello lists only what
 * passes both, so other devices never offer something that would fail.
 */
object Caps {
    val SMS_PERMISSIONS = arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Media sessions are read through the notification listener, so it needs notification access. */
    fun mediaAllowed(context: Context) = MirrorService.isEnabled(context)

    /** The messaging feature is only declared from Android 13; before that, any telephony means SMS. */
    fun hasTelephony(context: Context) = context.packageManager.hasSystemFeature(
        if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY)

    fun smsAllowed(context: Context) =
        granted(context, Manifest.permission.READ_SMS) && granted(context, Manifest.permission.SEND_SMS)

    fun contactsAllowed(context: Context) = granted(context, Manifest.permission.READ_CONTACTS)

    /** Android 11+: "All files access". Before that, the old storage permission. */
    fun filesAllowed(context: Context) = runCatching {
        if (Build.VERSION.SDK_INT >= 30) isStorageManager()
        else granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }.getOrDefault(false)

    /** Android 11+'s All files access check; replaceable because Robolectric can't answer it. */
    @androidx.annotation.RequiresApi(30)
    internal var isStorageManager: () -> Boolean = { Environment.isExternalStorageManager() }

    fun current(context: Context): Set<String> = buildSet {
        if (Prefs.capMedia && mediaAllowed(context)) add("media")
        if (Prefs.capSms && hasTelephony(context) && smsAllowed(context)) add("sms")
        if (Prefs.capFiles && filesAllowed(context)) add("files")
        if (Prefs.capClipboard) add("clipboard")
    }
}

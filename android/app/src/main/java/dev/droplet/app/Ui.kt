package dev.droplet.app

import android.app.Activity
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

fun Activity.isNight(): Boolean =
    resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

/** Draw behind the system bars, with bar icons that suit the current light/dark theme. */
fun ComponentActivity.edgeToEdge() {
    val style = if (isNight()) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
    enableEdgeToEdge(style, style)
}

/** Keeps a view's content clear of the status bar, navigation bar, cutout and keyboard. */
fun View.padForSystemBars(keyboard: Boolean = true) {
    val start = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
            (if (keyboard) WindowInsetsCompat.Type.ime() else 0)
        val i = insets.getInsets(types)
        v.updatePadding(start[0] + i.left, start[1] + i.top, start[2] + i.right, start[3] + i.bottom)
        WindowInsetsCompat.CONSUMED
    }
}

val View.dp: Float get() = resources.displayMetrics.density

fun ViewGroup.inflate(layout: Int): View =
    android.view.LayoutInflater.from(context).inflate(layout, this, false)

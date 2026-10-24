package com.uyatame.ucrt

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes

/** Pre-packaged launcher icons selectable from Settings. */
data class LauncherIconOption(val id: String, val title: String, @DrawableRes val preview: Int)

object LauncherIconManager {
    private const val ALIAS_PREFIX = "com.uyatame.ucrt.LauncherAlias_"
    private const val PREFS = "launcher_icon_switch"
    private const val PENDING = "pending_icon"

    val options = listOf(
        LauncherIconOption("default", "Standard", R.drawable.ucrt_standard_preview),
        LauncherIconOption("ucrt_light", "UCRT Light", R.drawable.ucrt_light),
        LauncherIconOption("ucrt_dark", "UCRT Dark", R.drawable.ucrt_dark),
        LauncherIconOption("ucrt2_light", "UCRT 2 Light", R.drawable.ucrt2_light),
        LauncherIconOption("ucrt2_dark", "UCRT 2 Dark", R.drawable.ucrt2_dark),
        LauncherIconOption("ucrt3_light", "UCRT 3 Light", R.drawable.ucrt3_light),
        LauncherIconOption("ucrt3_dark", "UCRT 3 Dark", R.drawable.ucrt3_dark),
        LauncherIconOption("ucrt4_light", "UCRT 4 Light", R.drawable.ucrt4_light),
        LauncherIconOption("ucrt4_dark", "UCRT 4 Dark", R.drawable.ucrt4_dark),
        LauncherIconOption("ucrt5_light", "UCRT 5 Light", R.drawable.ucrt5_light),
        LauncherIconOption("ucrt5_dark", "UCRT 5 Dark", R.drawable.ucrt5_dark),
        LauncherIconOption("ucrt6_light", "UCRT 6 Light", R.drawable.ucrt6_light),
        LauncherIconOption("ucrt6_dark", "UCRT 6 Dark", R.drawable.ucrt6_dark),
        LauncherIconOption("ucrt7_light", "UCRT 7 Light", R.drawable.ucrt7_light),
        LauncherIconOption("ucrt7_dark", "UCRT 7 Dark", R.drawable.ucrt7_dark),
        LauncherIconOption("rainbow_ucrt", "Rainbow UCRT", R.drawable.rainbow_ucrt),
        LauncherIconOption("rainbow_unknown", "Rainbow Unknown", R.drawable.rainbow_unknown),
    )

    /**
     * Store a requested icon change. Changing aliases while MainActivity is foreground can cause
     * Android or a launcher to close the activity even with DONT_KILL_APP, so the actual switch is
     * deferred until MainActivity.onStop().
     */
    fun request(context: Context, selectedId: String) {
        val validId = options.firstOrNull { it.id == selectedId }?.id ?: "default"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(PENDING, validId).apply()
    }

    /** Apply a pending switch after the activity has left the foreground. */
    fun applyPending(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val selectedId = prefs.getString(PENDING, null) ?: return
        if (applyNow(context.applicationContext, selectedId)) {
            prefs.edit().remove(PENDING).apply()
        }
    }

    private fun applyNow(context: Context, selectedId: String): Boolean = runCatching {
        val validId = options.firstOrNull { it.id == selectedId }?.id ?: "default"
        val pm = context.packageManager
        // Enable first to guarantee that at least one launcher entry remains available.
        pm.setComponentEnabledSetting(
            ComponentName(context.packageName, ALIAS_PREFIX + validId),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        options.asSequence().filter { it.id != validId }.forEach { option ->
            pm.setComponentEnabledSetting(
                ComponentName(context.packageName, ALIAS_PREFIX + option.id),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }
    }.isSuccess
}

package app.olauncher.helper

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi

/**
 * Themed icons for the home bottom bar. Icons come from [LauncherApps] so the
 * work profile badge survives; the result is tinted with the home text color.
 */
object BottomBarIcons {

    /**
     * Loads the slot's icon already tinted with [tintColor], or null when the
     * app is gone. API 33+ with a monochrome layer uses it, otherwise the
     * full-color icon is desaturated first (see [app.olauncher.data.BottomBar.decideIconSource]).
     */
    fun loadThemedIcon(
        context: Context,
        packageName: String,
        activityClassName: String?,
        userString: String,
        tintColor: Int,
    ): Drawable? {
        if (packageName.isBlank()) return null
        return try {
            val user = getUserHandleFromString(context, userString)
            val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val activities = launcher.getActivityList(packageName, user)
            if (activities.isEmpty()) return null
            val info = activities.firstOrNull { it.componentName.className == activityClassName }
                ?: activities.first()
            val fullIcon = info.getBadgedIcon(0)
            // The SDK_INT guard keeps the AdaptiveIconDrawable reference in
            // monochromeOrNull off pre-33 devices (lint NewApi tracks this)
            val base = if (Build.VERSION.SDK_INT >= 33) {
                monochromeOrNull(fullIcon)?.mutate()?.also {
                    // A color filter paints every opaque pixel; setTint is ignored by layers that carry their own colors
                    it.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
                } ?: grayTinted(fullIcon, tintColor)
            } else {
                grayTinted(fullIcon, tintColor)
            }
            // Unwrapping the monochrome layer drops the badge LauncherActivityInfo applied
            if (user != Process.myUserHandle())
                context.packageManager.getUserBadgedIcon(base, user)
            else
                base
        } catch (_: Exception) {
            null
        }
    }

    @RequiresApi(33)
    private fun monochromeOrNull(icon: Drawable): Drawable? =
        try {
            (icon as? android.graphics.drawable.AdaptiveIconDrawable)?.monochrome
        } catch (_: Exception) {
            null
        }

    // Luminance of the full-color icon scaled by the tint per channel, alpha kept: one filter, so a
    // second color filter can not override the tint
    private fun grayTinted(icon: Drawable, tintColor: Int): Drawable {
        val matrix = ColorMatrix(
            floatArrayOf(
                Color.red(tintColor) / 255f, 0f, 0f, 0f, 0f,
                0f, Color.green(tintColor) / 255f, 0f, 0f, 0f,
                0f, 0f, Color.blue(tintColor) / 255f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        matrix.preConcat(ColorMatrix().apply { setSaturation(0f) })
        return icon.mutate().also { it.colorFilter = ColorMatrixColorFilter(matrix) }
    }
}

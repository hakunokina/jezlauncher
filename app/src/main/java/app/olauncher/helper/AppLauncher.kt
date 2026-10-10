package app.olauncher.helper

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.UserHandle
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Prefs

/**
 * The single point every launcher-initiated app open goes through
 * (drawer, home apps, search, gestures and the interrupt screen alike).
 */
object AppLauncher {

    fun start(context: Context, appModel: AppModel) {
        when (appModel) {
            is AppModel.PinnedShortcut ->
                startShortcut(context, appModel.appPackage, appModel.shortcutId, appModel.user)

            is AppModel.App ->
                startApp(context, appModel.appPackage, appModel.activityClassName, appModel.user)

            else -> {}
        }
    }

    fun startApp(context: Context, packageName: String, activityClassName: String?, userHandle: UserHandle) {
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val activityInfo = launcher.getActivityList(packageName, userHandle)

        val isActivityValid = activityClassName.isNullOrBlank().not()
                && activityInfo.any { it.componentName.className == activityClassName }

        val component = if (isActivityValid)
            ComponentName(packageName, activityClassName)
        else {
            when (activityInfo.size) {
                0 -> {
                    context.showToast(context.getString(R.string.app_not_found))
                    return
                }

                1 -> ComponentName(packageName, activityInfo[0].name)
                else -> ComponentName(packageName, activityInfo[activityInfo.size - 1].name)
            }.also { Prefs(context).updateAppActivityClassName(packageName, it.className) }
        }

        try {
            launcher.startMainActivity(component, userHandle, null, null)
        } catch (e: SecurityException) {
            try {
                launcher.startMainActivity(component, android.os.Process.myUserHandle(), null, null)
            } catch (e: Exception) {
                context.showToast(context.getString(R.string.unable_to_open_app))
            }
        } catch (e: Exception) {
            context.showToast(context.getString(R.string.unable_to_open_app))
        }
    }

    fun startShortcut(context: Context, packageName: String, shortcutId: String, userHandle: UserHandle) {
        val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val query = LauncherApps.ShortcutQuery().apply {
            setPackage(packageName)
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        }
        try {
            val shortcut = launcher.getShortcuts(query, userHandle)
                ?.find { it.id == shortcutId }
            if (shortcut == null) {
                context.showToast(context.getString(R.string.shortcut_not_found))
                return
            }
            launcher.startShortcut(shortcut, null, null)
        } catch (_: Exception) {
            context.showToast(context.getString(R.string.unable_to_open_shortcut))
        }
    }
}

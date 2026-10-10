package app.olauncher.helper

import android.annotation.SuppressLint
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Point
import android.net.Uri
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.createBitmap
import androidx.core.view.children
import app.olauncher.BuildConfig
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Box
import app.olauncher.data.Constants
import app.olauncher.data.GRID_COLUMNS
import app.olauncher.data.Prefs
import app.olauncher.data.TONE_POINTS
import app.olauncher.data.TextChoice
import app.olauncher.data.WallpaperFeed
import app.olauncher.data.WallpaperGrid
import app.olauncher.data.MAX_WALLPAPER_BYTES
import app.olauncher.data.coverSampleSize
import app.olauncher.data.averageGrid
import app.olauncher.data.backgroundLuminances
import app.olauncher.data.decodeWallpaperColors
import app.olauncher.data.decodeWallpaperGrid
import app.olauncher.data.encodeWallpaperColors
import app.olauncher.data.encodeWallpaperGrid
import app.olauncher.data.gridRows
import app.olauncher.data.isLightRgb
import app.olauncher.data.oneUiWallpaperDims
import app.olauncher.data.parseWallpaperFeed
import app.olauncher.data.pickTextColors
import app.olauncher.data.screenToImage
import app.olauncher.data.shortcutIdentity
import app.olauncher.data.textSeed
import app.olauncher.data.wallpaperTextColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.Collator
import kotlin.math.pow
import kotlin.math.sqrt

fun Context.showToast(message: String?, duration: Int = Toast.LENGTH_SHORT) {
    if (message.isNullOrBlank()) return
    Toast.makeText(this, message, duration).show()
}

fun Context.showToast(stringResource: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, getString(stringResource), duration).show()
}

suspend fun getAppsList(
    context: Context,
    prefs: Prefs,
    includeRegularApps: Boolean = true,
    includeHiddenApps: Boolean = false,
): MutableList<AppModel> {
    return withContext(Dispatchers.IO) {
        val appList: MutableList<AppModel> = mutableListOf()

        try {
            if (!Prefs(context).hiddenAppsUpdated) upgradeHiddenApps(Prefs(context))
            val hiddenApps = Prefs(context).hiddenApps

            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val collator = Collator.getInstance()

            for (profile in userManager.userProfiles) {
                if (isPrivateSpaceProfile(context, profile)) continue
                for (app in launcherApps.getActivityList(null, profile)) {
                    val appLabelShown = prefs.getAppRenameLabel(app.applicationInfo.packageName)
                        .ifBlank { app.label.toString() }
                    val appModel = AppModel.App(
                        appLabel = appLabelShown,
                        key = collator.getCollationKey(app.label.toString()),
                        appPackage = app.applicationInfo.packageName,
                        activityClassName = app.componentName.className,
                        isNew = (System.currentTimeMillis() - app.firstInstallTime) < Constants.ONE_HOUR_IN_MILLIS,
                        user = profile
                    )

                    // if the current app is not OLauncher
                    if (app.applicationInfo.packageName != BuildConfig.APPLICATION_ID) {
                        // is this a hidden app?
                        if (hiddenApps.contains(app.applicationInfo.packageName + "|" + profile.toString())) {
                            if (includeHiddenApps) {
                                appList.add(appModel)
                            }
                        } else {
                            // this is a regular app
                            if (includeRegularApps) {
                                appList.add(appModel)
                            }
                        }
                    }
                }
            }

            // Add shortcuts if we're getting regular apps
            if (includeRegularApps && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pinned = try {
                    getPinnedShortcuts(context, prefs, collator)
                } catch (e: Exception) {
                    emptyList()
                }
                appList.addAll(pinned)
            }

            appList.sortWith(compareBy(collator) { it.appLabel })
        } catch (e: Exception) {
            e.printStackTrace()
        }
        appList
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun getPinnedShortcuts(
    context: Context,
    prefs: Prefs,
    collator: Collator,
): List<AppModel.PinnedShortcut> =
    withContext(Dispatchers.IO) {
        val pinnedShortcuts = mutableListOf<AppModel.PinnedShortcut>()
        val shortcuts = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (shortcuts?.hasShortcutHostPermission() == true) {
            val query = LauncherApps.ShortcutQuery().apply {
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }
            shortcuts.profiles.forEach { profile ->
                if (isPrivateSpaceProfile(context, profile)) return@forEach
                try {
                    shortcuts.getShortcuts(query, profile)?.forEach { shortcut ->
                        val identity = shortcutIdentity(
                            shortcut.`package`,
                            shortcut.id,
                            profile.toString()
                        )
                        if (shortcut.isPinned && pinnedShortcuts.none { it.identity == identity }) {
                            val label = prefs.getAppRenameLabel(identity)
                                .ifBlank { prefs.getAppRenameLabel(shortcut.id) }
                                .takeIf { it.isNotBlank() }
                                ?: shortcut.shortLabel?.toString()
                                ?: shortcut.longLabel?.toString().orEmpty()
                            pinnedShortcuts.add(
                                AppModel.PinnedShortcut(
                                    appLabel = label,
                                    key = collator.getCollationKey(label),
                                    appPackage = shortcut.`package`,
                                    shortcutId = shortcut.id,
                                    isNew = false,
                                    user = profile
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        pinnedShortcuts
    }

// This is to ensure backward compatibility with older app versions
// which did not support multiple user profiles
private fun upgradeHiddenApps(prefs: Prefs) {
    val hiddenAppsSet = prefs.hiddenApps
    val newHiddenAppsSet = mutableSetOf<String>()
    for (hiddenPackage in hiddenAppsSet) {
        if (hiddenPackage.contains("|")) newHiddenAppsSet.add(hiddenPackage)
        else newHiddenAppsSet.add(hiddenPackage + android.os.Process.myUserHandle().toString())
    }
    prefs.hiddenApps = newHiddenAppsSet
    prefs.hiddenAppsUpdated = true
}

fun isPackageInstalled(context: Context, packageName: String, userString: String): Boolean {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val activityInfo = launcher.getActivityList(packageName, getUserHandleFromString(context, userString))
    if (activityInfo.isNotEmpty()) return true
    return false
}

fun isPrivateSpaceProfile(context: Context, userHandle: UserHandle): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
    return try {
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        launcherApps.getLauncherUserInfo(userHandle)?.userType == "android.os.usertype.profile.PRIVATE"
    } catch (e: Exception) {
        false
    }
}

fun isPrivateSpaceLocked(context: Context, userHandle: UserHandle): Boolean {
    return try {
        val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
        userManager.isQuietModeEnabled(userHandle)
    } catch (e: Exception) {
        true
    }
}

fun getPrivateSpaceUserHandle(context: Context): UserHandle? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    for (profile in userManager.userProfiles) {
        if (isPrivateSpaceProfile(context, profile)) return profile
    }
    return null
}

suspend fun getPrivateSpaceApps(
    context: Context,
    prefs: Prefs,
): MutableList<AppModel> {
    return withContext(Dispatchers.IO) {
        val appList: MutableList<AppModel> = mutableListOf()
        try {
            val privateSpaceHandle = getPrivateSpaceUserHandle(context) ?: return@withContext appList
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val collator = Collator.getInstance()

            for (app in launcherApps.getActivityList(null, privateSpaceHandle)) {
                if (app.applicationInfo.packageName == BuildConfig.APPLICATION_ID) continue
                val appLabelShown = prefs.getAppRenameLabel(app.applicationInfo.packageName)
                    .ifBlank { app.label.toString() }
                appList.add(
                    AppModel.App(
                        appLabel = appLabelShown,
                        key = collator.getCollationKey(app.label.toString()),
                        appPackage = app.applicationInfo.packageName,
                        activityClassName = app.componentName.className,
                        isNew = false,
                        user = privateSpaceHandle
                    )
                )
            }
            appList.sortWith(compareBy(collator) { it.appLabel })
        } catch (e: Exception) {
            e.printStackTrace()
        }
        appList
    }
}

fun getUserHandleFromString(context: Context, userHandleString: String): UserHandle {
    val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
    for (userHandle in userManager.userProfiles) {
        if (userHandle.toString() == userHandleString) {
            return userHandle
        }
    }
    return android.os.Process.myUserHandle()
}

// The HOME role is the stored choice. The preferred HOME activity is briefly cleared whenever the role
// controller re-grants default roles (One UI does it on package changes), so resolveActivity can say "not default"
fun isOlauncherDefault(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME))
            return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    }
    val launcherPackageName = getDefaultLauncherPackage(context)
    return BuildConfig.APPLICATION_ID == launcherPackageName
}

fun getDefaultLauncherPackage(context: Context): String {
    val intent = Intent()
    intent.action = Intent.ACTION_MAIN
    intent.addCategory(Intent.CATEGORY_HOME)
    val packageManager = context.packageManager
    val result = packageManager.resolveActivity(intent, 0)
    return if (result?.activityInfo != null) {
        result.activityInfo.packageName
    } else "android"
}

fun setPlainWallpaper(context: Context, color: Int) {
    // The feed image is gone, so the daily job must apply it again
    Prefs(context).wallpaperFeedUrl = ""
    try {
        val bitmap = createBitmap(1000, 2000)
        bitmap.eraseColor(context.getColor(color))
        val manager = WallpaperManager.getInstance(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_SYSTEM)
            manager.setBitmap(bitmap, null, false, WallpaperManager.FLAG_LOCK)
        } else
            manager.setBitmap(bitmap)
        bitmap.recycle()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Whether the system wallpaper wants dark text (a light wallpaper), or null when unknown: API < 27, or the
 * system has not analysed the wallpaper yet. API 31+ has the dark-text hint; API 27-30 only the primary color.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
fun wallpaperWantsDarkText(colors: WallpaperColors?): Boolean? {
    colors ?: return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        colors.colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0
    else
        isLightRgb(colors.primaryColor.toArgb())
}

/**
 * Colors of the current system wallpaper. One UI returns null from getWallpaperColors, so for an image the app set
 * (same wallpaper id) the colors computed from its bitmap are used; other wallpapers fall back to the system's.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
fun currentWallpaperColors(context: Context): WallpaperColors? {
    val manager = WallpaperManager.getInstance(context)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val stored = decodeWallpaperColors(Prefs(context).wallpaperColors)
        if (stored != null && stored.id == manager.getWallpaperId(WallpaperManager.FLAG_SYSTEM)) {
            val colors = stored.colors.map { Color.valueOf(it) }
            return WallpaperColors(colors[0], colors.getOrNull(1), colors.getOrNull(2), stored.hints)
        }
    }
    return manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
}

/** API 31+: a tone of the wallpaper's own colors; black/white before or when the wallpaper is grey. */
@RequiresApi(Build.VERSION_CODES.O_MR1)
fun homeTextColor(colors: WallpaperColors, darkText: Boolean): Int {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        wallpaperTextColor(
            darkText,
            listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor).map { it.toArgb() }
        )?.let { return it }
    return if (darkText) Color.BLACK else Color.WHITE
}

/**
 * Text colors for the home screen's blocks of texts, [blocks] holding the screen boxes of each block's texts (see
 * textBoxes), measured on the wallpaper image the app set: the background behind each box is looked up in the image's
 * grid as the system shows it (zoomed or dimmed, see wallpaperDisplay), the hue comes from [colors] (API 31+, as in
 * homeTextColor). Null when the current wallpaper is not such an image.
 */
@RequiresApi(Build.VERSION_CODES.O_MR1)
fun homeTextChoices(context: Context, blocks: List<List<Box>>, colors: WallpaperColors?): List<List<TextChoice?>>? {
    val grid = decodeWallpaperGrid(Prefs(context).wallpaperGrid) ?: return null
    if (grid.id != WallpaperManager.getInstance(context).getWallpaperId(WallpaperManager.FLAG_SYSTEM)) return null
    val (screenWidth, screenHeight) = getScreenDimensions(context)
    val seed = if (colors != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        textSeed(listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor).map { it.toArgb() })
    else null
    val (zoom, dims) = wallpaperDisplay(context)
    val backgrounds = blocks.map { boxes ->
        boxes.map { screenBox ->
            val box = screenToImage(screenBox, screenWidth, screenHeight, grid.width, grid.height, zoom)
            dims.flatMap { grid.backgroundLuminances(box, it).orEmpty() }
        }
    }
    return pickTextColors(seed, backgrounds)
}

// How the system shows the home wallpaper: zoom and possible dimming factors. AOSP zooms it in by
// config_wallpaperMaxScale (1.1; measured on the emulator) unless the launcher zooms it out, which needs a hidden API.
// One UI reports 1.1 too but shows the image as set, and dims it in dark mode (both measured on the SM-F776B)
@SuppressLint("DiscouragedApi") // a framework resource has no R field to use
private fun wallpaperDisplay(context: Context): Pair<Double, List<Double>> {
    if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
        // The system's own mode; the app's theme is forced separately
        val night = Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
        val setting = runCatching { Settings.System.getInt(context.contentResolver, "display_night_theme_wallpaper") }
        return 1.0 to oneUiWallpaperDims(night, setting.getOrNull())
    }
    val resources = Resources.getSystem()
    val id = resources.getIdentifier("config_wallpaperMaxScale", "dimen", "android")
    val zoom = if (id == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) 1.0
    else runCatching { resources.getFloat(id).toDouble() }.getOrDefault(1.0).coerceAtLeast(1.0)
    return zoom to listOf(1.0)
}

/** The visible texts and icons in [view] with their screen boxes (a text's laid-out lines, not the whole view). */
fun textBoxes(view: View): List<Pair<View, Box>> {
    val boxes = mutableListOf<Pair<View, Box>>()
    val location = IntArray(2)
    fun collect(v: View) {
        if (!v.isShown || v.width == 0 || v.height == 0) return
        if (v is ViewGroup) {
            v.children.forEach { collect(it) }
            return
        }
        if (v !is TextView && (v !is ImageView || v.drawable == null)) return
        v.getLocationOnScreen(location)
        var left = v.paddingLeft.toDouble()
        var right = (v.width - v.paddingRight).toDouble()
        var top = v.paddingTop.toDouble()
        var bottom = (v.height - v.paddingBottom).toDouble()
        if (v is TextView) {
            left = v.totalPaddingLeft.toDouble()
            right = (v.width - v.totalPaddingRight).toDouble()
            top = v.totalPaddingTop.toDouble()
            bottom = (v.height - v.totalPaddingBottom).toDouble()
            val layout = v.layout
            // A hint has no laid-out lines here; it keeps the whole text area
            if (layout != null && layout.lineCount > 0 && v.text.isNotEmpty()) {
                val lines = 0 until layout.lineCount
                right = left + lines.maxOf { layout.getLineRight(it) } - v.scrollX
                left += lines.minOf { layout.getLineLeft(it) } - v.scrollX
            }
        }
        val box = Box(location[0] + left, location[1] + top, location[0] + right, location[1] + bottom)
        if (!box.isEmpty) boxes.add(v to box)
    }
    collect(view)
    return boxes
}

fun getChangedAppTheme(context: Context, currentAppTheme: Int): Int {
    return when (currentAppTheme) {
        AppCompatDelegate.MODE_NIGHT_YES -> AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.MODE_NIGHT_NO -> AppCompatDelegate.MODE_NIGHT_YES
        else -> {
            if (context.isDarkThemeOn())
                AppCompatDelegate.MODE_NIGHT_NO
            else AppCompatDelegate.MODE_NIGHT_YES
        }
    }
}

fun openAppInfo(context: Context, userHandle: UserHandle, packageName: String) {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val component = launcher.getActivityList(packageName, userHandle).firstOrNull()?.componentName
    if (component != null)
        launcher.startAppDetailsActivity(component, userHandle, null, null)
    else
        context.showToast(context.getString(R.string.unable_to_open_app_info))
}

// Decoded at about [reqWidth] x [reqHeight] (coverSampleSize), so a full size image does not run out of memory;
// images over MAX_WALLPAPER_BYTES are not downloaded
suspend fun getBitmapFromURL(src: String?, reqWidth: Int, reqHeight: Int): Bitmap? {
    return withContext(Dispatchers.IO) {
        var bitmap: Bitmap? = null
        try {
            val url = URL(src)
            val connection: HttpURLConnection = url
                .openConnection() as HttpURLConnection
            connection.doInput = true
            connection.connect()
            if (connection.contentLengthLong > MAX_WALLPAPER_BYTES) return@withContext null
            val bytes = connection.inputStream.use { input ->
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_WALLPAPER_BYTES) return@withContext null
                }
                buffer.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = coverSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            }
            bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        bitmap
    }
}

suspend fun getWallpaperBitmap(originalImage: Bitmap, width: Int, height: Int): Bitmap {
    return withContext(Dispatchers.IO) {

        val background = createBitmap(width, height)

        val originalWidth: Float = originalImage.width.toFloat()
        val originalHeight: Float = originalImage.height.toFloat()

        val canvas = Canvas(background)
        val heightScale: Float = height / originalHeight
        val widthScale: Float = width / originalWidth
        val scale = maxOf(heightScale, widthScale)

        val (xTranslation, yTranslation) = if (heightScale > widthScale)
            Pair((width - originalWidth * heightScale) / 2.0f, 0f)
        else
            Pair(0f, (height - originalHeight * widthScale) / 2.0f)

        val transformation = Matrix()
        transformation.postTranslate(xTranslation, yTranslation)
        transformation.preScale(scale, scale)

        val paint = Paint()
        paint.isFilterBitmap = true
        canvas.drawBitmap(originalImage, transformation, paint)

        background
    }
}

suspend fun setWallpaper(appContext: Context, url: String): Boolean {
    return withContext(Dispatchers.IO) {
        if (appContext.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && isTablet(appContext).not())
            return@withContext false
        val (width, height) = getScreenDimensions(appContext)
        val originalImageBitmap = getBitmapFromURL(url, width, height) ?: return@withContext false

        val wallpaperManager = WallpaperManager.getInstance(appContext)
        val scaledBitmap = getWallpaperBitmap(originalImageBitmap, width, height)

        // Computed before the bitmap is set, so the stored colors are in place when the new id is saved
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) WallpaperColors.fromBitmap(scaledBitmap) else null
        val grid = wallpaperGridOf(scaledBitmap)
        try {
            val id = wallpaperManager.setBitmap(scaledBitmap, null, false, WallpaperManager.FLAG_SYSTEM)
            wallpaperManager.setBitmap(scaledBitmap, null, false, WallpaperManager.FLAG_LOCK)
            // Before the colors: their change listener re-colors the home texts
            Prefs(appContext).wallpaperGrid = encodeWallpaperGrid(grid(id))
            if (colors != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                Prefs(appContext).wallpaperColors = encodeWallpaperColors(
                    id,
                    colors.colorHints,
                    listOfNotNull(colors.primaryColor, colors.secondaryColor, colors.tertiaryColor).map { it.toArgb() }
                )
        } catch (e: Exception) {
            return@withContext false
        }

        try {
            originalImageBitmap.recycle()
            scaledBitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        true
    }
}

// Average colors of the image as it is shown (the app crops it to the screen); the id is known once it is set
private fun wallpaperGridOf(bitmap: Bitmap): (id: Int) -> WallpaperGrid {
    val width = bitmap.width
    val height = bitmap.height
    val rows = gridRows(width, height)
    val tones = ByteArray(GRID_COLUMNS * rows * TONE_POINTS)
    val cells = averageGrid(width, height, GRID_COLUMNS, rows, tones) { y, pixels ->
        bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
    }
    return { id -> WallpaperGrid(id, width, height, GRID_COLUMNS, rows, cells, tones) }
}

fun getScreenDimensions(context: Context): Pair<Int, Int> {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val point = Point()
    windowManager.defaultDisplay.getRealSize(point)
    return Pair(point.x, point.y)
}

/** Reads the user's wallpaper source JSON for [wallType], or null on any failure. */
suspend fun fetchWallpaperFeed(sourceUrl: String, wallType: String, timeoutMs: Int = 15_000): WallpaperFeed? =
    fetchText(sourceUrl, timeoutMs)?.let { parseWallpaperFeed(it, wallType) }

/** Body of a GET answered with 200, or null on any failure (no network, timeout, an error answer). */
suspend fun fetchText(url: String, timeoutMs: Int = 15_000): String? {
    return withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }
}

@SuppressLint("WrongConstant", "PrivateApi")
fun expandNotificationDrawer(context: Context) {
    // Source: https://stackoverflow.com/a/51132142
    try {
        val statusBarService = context.getSystemService("statusbar")
        val statusBarManager = Class.forName("android.app.StatusBarManager")
        val method = statusBarManager.getMethod("expandNotificationsPanel")
        method.invoke(statusBarService)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun openAlarmApp(context: Context) {
    try {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.d("TAG", e.toString())
    }
}

fun openCalendar(context: Context) {
    try {
        val calendarUri = CalendarContract.CONTENT_URI
            .buildUpon()
            .appendPath("time")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, calendarUri))
    } catch (e: Exception) {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_APP_CALENDAR)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun isAccessServiceEnabled(context: Context): Boolean {
    val enabled = try {
        Settings.Secure.getInt(context.applicationContext.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED)
    } catch (e: Exception) {
        0
    }
    if (enabled == 1) {
        val enabledServicesString: String? = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabledServicesString?.contains(context.packageName + "/" + MyAccessibilityService::class.java.name) ?: false
    }
    return false
}

fun isTablet(context: Context): Boolean {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    windowManager.defaultDisplay.getMetrics(metrics)
    val widthInches = metrics.widthPixels / metrics.xdpi
    val heightInches = metrics.heightPixels / metrics.ydpi
    val diagonalInches = sqrt(widthInches.toDouble().pow(2.0) + heightInches.toDouble().pow(2.0))
    if (diagonalInches >= 7.0) return true
    return false
}

fun Context.isDarkThemeOn(): Boolean {
    return resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
}

fun Context.copyToClipboard(text: String) {
    val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText(getString(R.string.app_name), text)
    clipboardManager.setPrimaryClip(clipData)
    showToast("")
}

fun Context.openUrl(url: String) {
    if (url.isEmpty()) return
    val intent = Intent(Intent.ACTION_VIEW)
    intent.data = Uri.parse(url)
    startActivity(intent)
}

fun Context.isSystemApp(packageName: String, user: UserHandle? = null): Boolean {
    if (packageName.isBlank()) return true
    return try {
        val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val targetUser = user ?: android.os.Process.myUserHandle()
        val activityList = launcherApps.getActivityList(packageName, targetUser)
        if (activityList.isNotEmpty()) {
            val applicationInfo = activityList.first().applicationInfo
            ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    || (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))
        } else {
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                    || (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun Context.uninstall(packageName: String) {
    val intent = Intent(Intent.ACTION_DELETE)
    intent.data = Uri.parse("package:$packageName")
    startActivity(intent)
}

@ColorInt
fun Context.getColorFromAttr(
    @AttrRes attrColor: Int,
    typedValue: TypedValue = TypedValue(),
    resolveRefs: Boolean = true,
): Int {
    theme.resolveAttribute(attrColor, typedValue, resolveRefs)
    return typedValue.data
}

@RequiresApi(Build.VERSION_CODES.N_MR1)
fun Context.deletePinnedShortcut(packageName: String, shortcutIdToDelete: String, user: UserHandle) {
    val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    // 1. Query for existing pinned shortcuts for the package
    val query = LauncherApps.ShortcutQuery().apply {
        setPackage(packageName)
        // Query only for pinned shortcuts
        setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
    }

    try {
        val pinnedShortcuts = launcherApps.getShortcuts(query, user)

        if (pinnedShortcuts != null) {
            // 2. Filter out the shortcut to be deleted
            val updatedPinnedIds = pinnedShortcuts
                .filter { it.id != shortcutIdToDelete }
                .map { it.id }

            // 3. Re-pin the remaining shortcuts
            // This replaces the existing set of pinned shortcuts for this package
            launcherApps.pinShortcuts(packageName, updatedPinnedIds, user)
        }
    } catch (e: SecurityException) {
        // Handle cases where the app doesn't have permission
        // (e.g., not the default launcher or active voice interaction service)
        Log.e("ShortcutHelper", "Permission denied to modify pinned shortcuts for $packageName", e)
    } catch (e: IllegalStateException) {
        // Handle cases where the user profile is locked or not running
        Log.e("ShortcutHelper", "User profile unavailable for modifying pinned shortcuts for $packageName", e)
    } catch (e: Exception) {
        // Handle other potential exceptions (like RemoteException wrapped)
        Log.e("ShortcutHelper", "Failed to modify pinned shortcuts for $packageName", e)
    }
}

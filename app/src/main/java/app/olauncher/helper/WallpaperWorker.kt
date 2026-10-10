package app.olauncher.helper

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.APOD_POSTS_URL
import app.olauncher.data.apodDayKey
import app.olauncher.data.isNewFeedDay
import app.olauncher.data.millisToNextWallTypeSwitch
import app.olauncher.data.nextWallpaperUrl
import app.olauncher.data.pickApodImage
import app.olauncher.data.wallTypeAt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.concurrent.TimeUnit

// The daily and the manual job must not interleave: one decides from state the other is about to change
private val applyLock = Mutex()

/**
 * Custom feed: a one-off job at the next 05:00/19:00 that applies the new pool's image; each run schedules the
 * next one from the current time, so a run at the wrong time after a clock or time zone change corrects itself.
 * The job itself uses APPEND_OR_REPLACE, as REPLACE would cancel the running job.
 */
fun scheduleWallpaperSwitch(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
    val request = OneTimeWorkRequestBuilder<WallpaperWorker>()
        .setInitialDelay(millisToNextWallTypeSwitch(Calendar.getInstance()), TimeUnit.MILLISECONDS)
        .setInputData(workDataOf(Constants.KEY_WALLPAPER_SWITCH to true))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(Constants.WALLPAPER_SWITCH_WORKER_NAME, policy, request)
}

class WallpaperWorker(appContext: Context, workerParams: WorkerParameters) : CoroutineWorker(appContext, workerParams) {

    private val prefs = Prefs(applicationContext)

    override suspend fun doWork(): Result = coroutineScope {
        val success =
            if (prefs.appTheme == AppCompatDelegate.MODE_NIGHT_YES && isOlauncherDefault(applicationContext).not())
                true
            else if (prefs.dailyWallpaper) {
                val sourceUrl = prefs.wallpaperSourceUrl
                if (sourceUrl.isNotBlank()) {
                    // Custom feed: the local time picks the pool
                    val wallType = wallTypeAt(Calendar.getInstance())
                    if (inputData.getBoolean(Constants.KEY_WALLPAPER_NEXT, false))
                        applyNextWallpaper(sourceUrl, wallType)
                    else
                        applyCustomWallpaper(sourceUrl, wallType)
                } else if (inputData.getBoolean(Constants.KEY_WALLPAPER_NEXT, false))
                    true
                else
                    applyApodWallpaper()
            } else
                true

        if (success && inputData.getBoolean(Constants.KEY_WALLPAPER_SWITCH, false) &&
            prefs.dailyWallpaper && prefs.wallpaperSourceUrl.isNotBlank()
        )
            scheduleWallpaperSwitch(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)

        if (success)
            Result.success()
        else
            Result.retry()
    }

    // Daily: applies only when the pool's day image changed or the pool switched, so a manual pick survives until then.
    // State is stored only after the image was applied, so a failed attempt is retried
    private suspend fun applyCustomWallpaper(sourceUrl: String, wallType: String): Boolean = applyLock.withLock {
        val feed = fetchWallpaperFeed(sourceUrl, wallType) ?: return false
        if (!isNewFeedDay(feed.dayKey, prefs.wallpaperFeedUrl)) return true
        if (!setWallpaper(applicationContext, feed.wallpaperUrl)) return false
        prefs.dailyWallpaperUrl = feed.wallpaperUrl
        prefs.wallpaperFeedUrl = feed.dayKey
        true
    }

    // Manual: the next item of the feed pool; also marks today's day key as seen so the daily job leaves it alone
    private suspend fun applyNextWallpaper(sourceUrl: String, wallType: String): Boolean = applyLock.withLock {
        val feed = fetchWallpaperFeed(sourceUrl, wallType) ?: return false
        val next = nextWallpaperUrl(feed.items, prefs.dailyWallpaperUrl) ?: return true
        if (!setWallpaper(applicationContext, next)) return false
        prefs.dailyWallpaperUrl = next
        prefs.wallpaperFeedUrl = feed.dayKey
        true
    }

    // Default source, NASA APOD: one image a day for light and dark alike, at most one answered request a day.
    // No answer or an error answer keeps the wallpaper and leaves the next try to the next periodic run instead of
    // a retry; so does an image that fails to download
    private suspend fun applyApodWallpaper(): Boolean = applyLock.withLock {
        val dayKey = apodDayKey(Calendar.getInstance())
        if (prefs.wallpaperFeedUrl == dayKey) return true
        val body = fetchText(APOD_POSTS_URL) ?: return true
        val apod = pickApodImage(body)
        if (apod != null) {
            if (apod.imageUrl != prefs.dailyWallpaperUrl && !setWallpaper(applicationContext, apod.imageUrl)) return true
            prefs.dailyWallpaperUrl = apod.imageUrl
            prefs.apodCredit = apod.title + " — " + apod.credit
            prefs.apodCreditImageUrl = apod.imageUrl
            prefs.apodPageUrl = apod.pageUrl
        }
        // Also when none of the posts is usable: the wallpaper stays, the posts are asked again tomorrow
        prefs.wallpaperFeedUrl = dayKey
        true
    }
}

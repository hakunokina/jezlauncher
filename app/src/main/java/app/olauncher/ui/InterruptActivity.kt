package app.olauncher.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Interrupt
import app.olauncher.data.Prefs
import app.olauncher.databinding.ActivityInterruptBinding
import app.olauncher.helper.AppLauncher
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.hideStatusBar
import app.olauncher.helper.usageStats.EventLogWrapper
import app.olauncher.helper.getUserHandleFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

/**
 * A calm full-screen pause before a selected app opens. "Open" only becomes
 * tappable after the countdown; opening the app once skips the pause for
 * [Interrupt.BYPASS_MILLIS]. Back or "Don't open" returns to the launcher.
 */
class InterruptActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private var _binding: ActivityInterruptBinding? = null
    private val binding get() = _binding!!

    private var countdownTimer: CountDownTimer? = null
    private var countdownEndAt = 0L // SystemClock.elapsedRealtime() based

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        _binding = ActivityInterruptBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (!prefs.showStatusBar) window.hideStatusBar()

        val appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty()
        binding.interruptAppName.text = appLabel
        binding.interruptOpen.isEnabled = false
        binding.interruptDontOpen.setOnClickListener { finish() }
        binding.interruptOpen.setOnClickListener { openApp() }

        countdownEndAt = savedInstanceState?.getLong(STATE_COUNTDOWN_END_AT)
            ?: (SystemClock.elapsedRealtime() + Interrupt.COUNTDOWN_MILLIS)
        if (countdownEndAt <= SystemClock.elapsedRealtime()) enableOpen()

        loadTimeToday(appLabel)
    }

    override fun onStart() {
        super.onStart()
        startCountdown()
    }

    override fun onStop() {
        countdownTimer?.cancel()
        countdownTimer = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong(STATE_COUNTDOWN_END_AT, countdownEndAt)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }

    private fun startCountdown() {
        val remaining = countdownEndAt - SystemClock.elapsedRealtime()
        if (remaining <= 0) {
            enableOpen()
            return
        }
        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(remaining, 250) {
            override fun onTick(millisUntilFinished: Long) {
                binding.interruptCountdown.text = String.format(
                    Locale.getDefault(), "%d", (millisUntilFinished + 999) / 1000
                )
            }

            override fun onFinish() {
                enableOpen()
            }
        }.start()
    }

    private fun enableOpen() {
        binding.interruptCountdown.isVisible = false
        binding.interruptOpen.isEnabled = true
        // The selector lives in drawable/ (existing pattern); safe for getColorStateList
        @android.annotation.SuppressLint("ResourceType")
        val colors = AppCompatResources.getColorStateList(this, R.drawable.text_colors_default)
        binding.interruptOpen.setTextColor(colors)
    }

    private fun openApp() {
        val packageName = intent.getStringExtra(EXTRA_APP_PACKAGE).orEmpty()
        val userString = intent.getStringExtra(EXTRA_APP_USER).orEmpty()
        val key = Interrupt.key(packageName, userString)
        val now = System.currentTimeMillis()
        val bypasses = Interrupt.recordBypass(
            Interrupt.parseBypasses(prefs.interruptBypasses), key, now
        )
        prefs.interruptBypasses = Interrupt.serializeBypasses(bypasses).toMutableSet()

        val user = getUserHandleFromString(this, userString)
        val appModel: AppModel = if (intent.getBooleanExtra(EXTRA_IS_SHORTCUT, false)) {
            AppModel.PinnedShortcut(
                appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty(),
                key = null,
                appPackage = packageName,
                shortcutId = intent.getStringExtra(EXTRA_SHORTCUT_ID).orEmpty(),
                isNew = false,
                user = user
            )
        } else {
            AppModel.App(
                appLabel = intent.getStringExtra(EXTRA_APP_LABEL).orEmpty(),
                key = null,
                appPackage = packageName,
                activityClassName = intent.getStringExtra(EXTRA_ACTIVITY_CLASS_NAME),
                isNew = false,
                user = user
            )
        }
        AppLauncher.start(this, appModel)
        finish()
    }

    // Shown only with usage access; the row stays hidden otherwise
    private fun loadTimeToday(appLabel: String) {
        val packageName = intent.getStringExtra(EXTRA_APP_PACKAGE).orEmpty()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !appUsagePermissionGranted()) return

        lifecycleScope.launch(Dispatchers.IO) {
            val timeToday = try {
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val wrapper = EventLogWrapper(this@InterruptActivity)
                val stats = wrapper.aggregateForegroundStats(
                    wrapper.getForegroundStatsByTimestamps(calendar.timeInMillis, System.currentTimeMillis())
                )
                stats.filter { it.applicationId == packageName }.sumOf { it.timeUsed }
            } catch (_: Exception) {
                -1L
            }
            withContext(Dispatchers.Main) {
                if (timeToday >= 0 && _binding != null) {
                    binding.interruptTimeToday.text = getString(
                        R.string.interrupt_time_today, appLabel, formattedTimeSpent(timeToday)
                    )
                    binding.interruptTimeToday.isVisible = true
                }
            }
        }
    }

    companion object {
        private const val EXTRA_APP_LABEL = "app.olauncher.interrupt.APP_LABEL"
        private const val EXTRA_APP_PACKAGE = "app.olauncher.interrupt.APP_PACKAGE"
        private const val EXTRA_ACTIVITY_CLASS_NAME = "app.olauncher.interrupt.ACTIVITY_CLASS_NAME"
        private const val EXTRA_SHORTCUT_ID = "app.olauncher.interrupt.SHORTCUT_ID"
        private const val EXTRA_IS_SHORTCUT = "app.olauncher.interrupt.IS_SHORTCUT"
        private const val EXTRA_APP_USER = "app.olauncher.interrupt.APP_USER"
        private const val STATE_COUNTDOWN_END_AT = "app.olauncher.interrupt.COUNTDOWN_END_AT"

        fun newIntent(context: Context, appModel: AppModel): Intent {
            val intent = Intent(context, InterruptActivity::class.java)
            intent.putExtra(EXTRA_APP_LABEL, appModel.appLabel)
            intent.putExtra(EXTRA_APP_PACKAGE, appModel.appPackage)
            intent.putExtra(EXTRA_APP_USER, appModel.user.toString())
            intent.putExtra(EXTRA_IS_SHORTCUT, appModel is AppModel.PinnedShortcut)
            when (appModel) {
                is AppModel.App ->
                    intent.putExtra(EXTRA_ACTIVITY_CLASS_NAME, appModel.activityClassName)

                is AppModel.PinnedShortcut ->
                    intent.putExtra(EXTRA_SHORTCUT_ID, appModel.shortcutId)

                else -> {}
            }
            return intent
        }
    }
}

package app.olauncher.ui

import android.app.WallpaperManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.BottomBar
import app.olauncher.data.Box
import app.olauncher.data.Constants
import app.olauncher.data.NotesDatabase
import app.olauncher.data.Prefs
import app.olauncher.data.TextChoice
import app.olauncher.data.homeNoteColumns
import app.olauncher.data.homeNoteRowCount
import app.olauncher.data.homeNotes
import app.olauncher.data.nextDayStart
import app.olauncher.data.staleTextChoices
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.BottomBarIcons
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.getChangedAppTheme
import app.olauncher.helper.getUserHandleFromString
import app.olauncher.helper.isPackageInstalled
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.showToast
import app.olauncher.helper.currentWallpaperColors
import app.olauncher.helper.homeTextChoices
import app.olauncher.helper.homeTextColor
import app.olauncher.helper.textBoxes
import app.olauncher.helper.wallpaperWantsDarkText
import app.olauncher.listener.OnSwipeTouchListener
import app.olauncher.listener.ViewSwipeTouchListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : BaseFragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    // API 27+: re-colors the home texts whenever the system re-analyses the wallpaper
    private var wallpaperColorsListener: Any? = null
    private var storedColorsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var textBoxesKey: List<List<Box>>? = null
    // The last pick by text box, for texts rebuilt in place (the home notes) without a new pick
    private var textChoices: Map<Box, TextChoice> = emptyMap()
    private var bottomBarColor: Int? = null
    private val textLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1 || _binding == null) return@OnGlobalLayoutListener
        val texts = binding.mainLayout.children.map { textBoxes(it) }.toList()
        val stale = if (texts.map { block -> block.map { it.second } } == textBoxesKey)
            staleTextChoices(textChoices, texts.flatten().map { (view, box) -> (view as? TextView)?.currentTextColor to box })
        else null
        if (stale == null) applyWallpaperTextColor()
        else texts.flatten().zip(stale).forEach { (text, choice) -> if (choice != null) styleText(text.first as TextView, choice) }
    }

    /** Texts of the home notes, in section order. */
    private var homeNoteTexts: List<String> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initSwipeTouchListener()
        initClickListeners()
        initBottomBarInsets()
        observeHomeNotes()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) listenWallpaperColors()
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen(false)
        populateBottomBar(bottomBarTintColor())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) applyWallpaperTextColor()
        viewModel.isOlauncherDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            // Home button for recents feature disabled
            // R.id.recents -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()
            R.id.bottomSlot1 -> bottomSlotClicked(1)
            R.id.bottomSlot2 -> bottomSlotClicked(2)
            R.id.bottomSlot3 -> bottomSlotClicked(3)
            R.id.bottomSlot4 -> bottomSlotClicked(4)
            R.id.bottomSlot5 -> bottomSlotClicked(5)

            else -> {
                try { // Launch app
                    val appLocation = view.tag.toString().toInt()
                    homeAppClicked(appLocation)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun openClockApp() {
        if (prefs.clockAppPackage.isBlank())
            openAlarmApp(requireContext())
        else
            launchApp(
                "Clock",
                prefs.clockAppPackage,
                prefs.clockAppClassName,
                prefs.clockAppUser
            )
    }

    private fun openCalendarApp() {
        if (prefs.calendarAppPackage.isBlank())
            openCalendar(requireContext())
        else
            launchApp(
                "Calendar",
                prefs.calendarAppPackage,
                prefs.calendarAppClassName,
                prefs.calendarAppUser
            )
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.homeApp1 -> showAppList(Constants.FLAG_SET_HOME_APP_1, prefs.appName1.isNotEmpty(), true)
            R.id.homeApp2 -> showAppList(Constants.FLAG_SET_HOME_APP_2, prefs.appName2.isNotEmpty(), true)
            R.id.homeApp3 -> showAppList(Constants.FLAG_SET_HOME_APP_3, prefs.appName3.isNotEmpty(), true)
            R.id.homeApp4 -> showAppList(Constants.FLAG_SET_HOME_APP_4, prefs.appName4.isNotEmpty(), true)
            R.id.homeApp5 -> showAppList(Constants.FLAG_SET_HOME_APP_5, prefs.appName5.isNotEmpty(), true)
            R.id.homeApp6 -> showAppList(Constants.FLAG_SET_HOME_APP_6, prefs.appName6.isNotEmpty(), true)
            R.id.homeApp7 -> showAppList(Constants.FLAG_SET_HOME_APP_7, prefs.appName7.isNotEmpty(), true)
            R.id.homeApp8 -> showAppList(Constants.FLAG_SET_HOME_APP_8, prefs.appName8.isNotEmpty(), true)
            R.id.clock -> {
                showAppList(Constants.FLAG_SET_CLOCK_APP)
                prefs.clockAppPackage = ""
                prefs.clockAppClassName = ""
                prefs.clockAppUser = ""
            }

            R.id.date -> {
                showAppList(Constants.FLAG_SET_CALENDAR_APP)
                prefs.calendarAppPackage = ""
                prefs.calendarAppClassName = ""
                prefs.calendarAppUser = ""
            }

            R.id.tvScreenTime -> {
                showAppList(Constants.FLAG_SET_SCREEN_TIME_APP)
                prefs.screenTimeAppPackage = ""
                prefs.screenTimeAppClassName = ""
                prefs.screenTimeAppUser = ""
            }
            R.id.bottomSlot1 -> showAppList(Constants.FLAG_SET_BOTTOM_BAR_APP_1)
            R.id.bottomSlot2 -> showAppList(Constants.FLAG_SET_BOTTOM_BAR_APP_2)
            R.id.bottomSlot3 -> showAppList(Constants.FLAG_SET_BOTTOM_BAR_APP_3)
            R.id.bottomSlot4 -> showAppList(Constants.FLAG_SET_BOTTOM_BAR_APP_4)
            R.id.bottomSlot5 -> showAppList(Constants.FLAG_SET_BOTTOM_BAR_APP_5)

            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isOlauncherDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        if (prefs.firstSettingsOpen) {
            binding.firstRunTips.visibility = View.VISIBLE
            binding.setDefaultLauncher.visibility = View.GONE
        } else binding.firstRunTips.visibility = View.GONE

        viewModel.refreshHome.observe(viewLifecycleOwner) {
            populateHomeScreen(it)
        }
        viewModel.isOlauncherDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                prefs.homeBottomAlignment = false
                setHomeAlignment()
            }
            if (binding.firstRunTips.isVisible) return@Observer
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }
        // Home button for recents feature disabled
        // viewModel.showRecentApps.observe(viewLifecycleOwner) {
        //     binding.recents.performClick()
        // }
    }

    private fun initSwipeTouchListener() {
        val context = requireContext()
        binding.mainLayout.setOnTouchListener(getSwipeGestureListener(context))
        binding.homeApp1.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp1))
        binding.homeApp2.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp2))
        binding.homeApp3.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp3))
        binding.homeApp4.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp4))
        binding.homeApp5.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp5))
        binding.homeApp6.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp6))
        binding.homeApp7.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp7))
        binding.homeApp8.setOnTouchListener(getViewSwipeTouchListener(context, binding.homeApp8))
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        // Home button for recents feature disabled
        // binding.recents.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.clock.setOnLongClickListener(this)
        binding.date.setOnLongClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.tvScreenTime.setOnLongClickListener(this)

        // These fire only on d-pad/keyboard events; touch is consumed by ViewSwipeTouchListener
        binding.homeApp1.setOnClickListener(this)
        binding.homeApp2.setOnClickListener(this)
        binding.homeApp3.setOnClickListener(this)
        binding.homeApp4.setOnClickListener(this)
        binding.homeApp5.setOnClickListener(this)
        binding.homeApp6.setOnClickListener(this)
        binding.homeApp7.setOnClickListener(this)
        binding.homeApp8.setOnClickListener(this)
        binding.homeApp1.setOnLongClickListener(this)
        binding.homeApp2.setOnLongClickListener(this)
        binding.homeApp3.setOnLongClickListener(this)
        binding.homeApp4.setOnLongClickListener(this)
        binding.homeApp5.setOnLongClickListener(this)
        binding.homeApp6.setOnLongClickListener(this)
        binding.homeApp7.setOnLongClickListener(this)
        binding.homeApp8.setOnLongClickListener(this)
        binding.bottomSlot1.setOnClickListener(this)
        binding.bottomSlot2.setOnClickListener(this)
        binding.bottomSlot3.setOnClickListener(this)
        binding.bottomSlot4.setOnClickListener(this)
        binding.bottomSlot5.setOnClickListener(this)
        binding.bottomSlot1.setOnLongClickListener(this)
        binding.bottomSlot2.setOnLongClickListener(this)
        binding.bottomSlot3.setOnLongClickListener(this)
        binding.bottomSlot4.setOnLongClickListener(this)
        binding.bottomSlot5.setOnLongClickListener(this)
    }

    // The bar sits above the navigation bar / gesture area; its slots are
    // clickable so touches there never reach the home screen gesture listener
    private fun initBottomBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, bars.bottom + 8.dpToPx())
            insets
        }
    }

    private fun bottomSlotView(slot: Int) = when (slot) {
        1 -> binding.bottomSlot1
        2 -> binding.bottomSlot2
        3 -> binding.bottomSlot3
        4 -> binding.bottomSlot4
        else -> binding.bottomSlot5
    }

    private fun bottomBarSetFlag(slot: Int) = when (slot) {
        1 -> Constants.FLAG_SET_BOTTOM_BAR_APP_1
        2 -> Constants.FLAG_SET_BOTTOM_BAR_APP_2
        3 -> Constants.FLAG_SET_BOTTOM_BAR_APP_3
        4 -> Constants.FLAG_SET_BOTTOM_BAR_APP_4
        else -> Constants.FLAG_SET_BOTTOM_BAR_APP_5
    }

    // Icons follow the home text color (wallpaper-derived), per slot when the
    // wallpaper behind them differs; called on resume and whenever the
    // wallpaper colors change
    private fun populateBottomBar(tintColor: Int, slotTint: (View) -> Int = { tintColor }) {
        val count = prefs.bottomBarAppsNum
        binding.bottomBar.isVisible = count > 0
        if (count == 0) return
        for (slot in 1..BottomBar.MAX_SLOTS) {
            val view = bottomSlotView(slot)
            view.isVisible = BottomBar.isSlotVisible(count, slot)
            if (!view.isVisible) continue
            val tint = slotTint(view)
            val saved = prefs.getBottomBarSlot(slot)
            val icon = BottomBarIcons.loadThemedIcon(
                requireContext(),
                saved.packageName,
                saved.activityClassName,
                saved.userString,
                tint
            )
            if (icon != null) {
                view.imageTintList = null
                view.setImageDrawable(icon)
            } else {
                // Uninstalled apps must not linger as dead slots
                if (!saved.isEmpty()) prefs.setBottomBarSlot(slot, BottomBar.Slot())
                view.setImageResource(R.drawable.ic_add)
                view.imageTintList =
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(tint, 0x66))
            }
        }
    }

    // Same source as the home texts, without touching them; pre-27 falls back
    // to white (the dark theme default)
    private fun bottomBarTintColor(): Int {
        bottomBarColor?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val colors = currentWallpaperColors(requireContext())
            val darkText = wallpaperWantsDarkText(colors)
            if (colors != null && darkText != null) return homeTextColor(colors, darkText)
        }
        return Color.WHITE
    }

    // Every launch goes through the shared view-model entry point, so the
    // interrupt pause also applies to bar taps
    private fun bottomSlotClicked(slot: Int) {
        val saved = prefs.getBottomBarSlot(slot)
        if (saved.isEmpty()) {
            showAppList(bottomBarSetFlag(slot))
            return
        }
        viewModel.selectedApp(
            AppModel.App(
                appLabel = bottomSlotLabel(saved),
                key = null,
                appPackage = saved.packageName,
                activityClassName = saved.activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), saved.userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun bottomSlotLabel(slot: BottomBar.Slot): String {
        return try {
            val launcher = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val user = getUserHandleFromString(requireContext(), slot.userString)
            launcher.getActivityList(slot.packageName, user)
                .firstOrNull { it.componentName.className == slot.activityClassName }
                ?.label?.toString()
                ?: launcher.getActivityList(slot.packageName, user).firstOrNull()?.label?.toString()
                ?: slot.packageName
        } catch (_: Exception) {
            slot.packageName
        }
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        val verticalGravity = if (prefs.homeBottomAlignment) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        binding.homeAppsLayout.gravity = horizontalGravity or verticalGravity
        binding.dateTimeLayout.gravity = horizontalGravity
        binding.homeApp1.gravity = horizontalGravity
        binding.homeApp2.gravity = horizontalGravity
        binding.homeApp3.gravity = horizontalGravity
        binding.homeApp4.gravity = horizontalGravity
        binding.homeApp5.gravity = horizontalGravity
        binding.homeApp6.gravity = horizontalGravity
        binding.homeApp7.gravity = horizontalGravity
        binding.homeApp8.gravity = horizontalGravity
        binding.homeNotesStart.gravity = Gravity.START or verticalGravity
        binding.homeNotesEnd.gravity = Gravity.END or verticalGravity
        showHomeNotes()
    }

    /** Active notes of the routine section, kept current while home shows: on note changes, at midnight and on return. */
    private fun observeHomeNotes() {
        val dao = NotesDatabase.get(requireContext()).noteDao()
        // Done daily and weekly notes come back at local midnight
        val days = flow {
            while (true) {
                emit(Unit)
                val now = System.currentTimeMillis()
                delay(nextDayStart(now) - now + 1_000)
            }
        }
        // The notes keep off the app names, whose widths are known only after layout
        binding.homeAppsLayout.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> view.post { fitHomeNotes() } }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeAll().combine(days) { notes, _ -> homeNotes(notes, System.currentTimeMillis()).map { it.text } }
                    .collect {
                        homeNoteTexts = it
                        showHomeNotes()
                    }
            }
        }
    }

    /** Lays the notes out across from the apps, row for row with them; see [homeNoteColumns]. */
    private fun showHomeNotes() {
        if (_binding == null) return
        val columns = homeNoteColumns(homeNoteTexts, prefs.homeAlignment)
        val rows = homeNoteRowCount(prefs.homeAppsNum, maxOf(columns.start.size, columns.end.size), !prefs.homeBottomAlignment)
        fillHomeNotes(binding.homeNotesStart, columns.start, rows)
        fillHomeNotes(binding.homeNotesEnd, columns.end, rows)
        fitHomeNotes()
    }

    private fun fillHomeNotes(column: LinearLayout, lines: List<String>, rows: Int) {
        column.removeAllViews()
        column.isVisible = lines.isNotEmpty()
        if (lines.isEmpty()) return
        val app = binding.homeApp1
        repeat(rows) { row ->
            val view = layoutInflater.inflate(R.layout.item_home_note, column, false) as TextView
            view.setTextColor(app.textColors)
            view.setShadowLayer(app.shadowRadius, app.shadowDx, app.shadowDy, app.shadowColor)
            val line = lines.getOrNull(row)
            if (line == null) {
                // An empty row that keeps the notes level with the apps
                view.visibility = View.INVISIBLE
            } else {
                view.text = line
                view.setOnClickListener { showNotesScreen() }
                view.setOnTouchListener(getViewSwipeTouchListener(requireContext(), view, { showNotesScreen() }, {}))
            }
            column.addView(view)
        }
    }

    /** The widest app name keeps its room; the notes ellipsize in what is left beside it. */
    private fun fitHomeNotes() {
        if (_binding == null) return
        val apps = binding.homeAppsLayout
        if (apps.width == 0) return
        val widest = apps.children.filter { it.isVisible }.maxOfOrNull { it.width } ?: 0
        val room = apps.width - apps.paddingStart - apps.paddingEnd - widest
        val side = if (prefs.homeAlignment == Gravity.CENTER) room / 2 else room
        val maxWidth = (side - HOME_NOTES_GAP_DP.dpToPx()).coerceAtLeast(0)
        for (column in listOf(binding.homeNotesStart, binding.homeNotesEnd))
            column.children.forEach { if (it is TextView && it.maxWidth != maxWidth) it.maxWidth = maxWidth }
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

//        var dateText = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodaysScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen(appCountUpdated: Boolean) {
        if (appCountUpdated) hideHomeApps()
        populateDateTime()
        // The notes line up with as many rows as there are apps
        showHomeNotes()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()

        val homeAppsNum = prefs.homeAppsNum
        if (homeAppsNum == 0) return

        binding.homeApp1.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp1, prefs.appName1, prefs.appPackage1, prefs.appUser1, prefs.isShortcut1, prefs.shortcutId1)) {
            prefs.appName1 = ""
            prefs.appPackage1 = ""
        }
        if (homeAppsNum == 1) return

        binding.homeApp2.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp2, prefs.appName2, prefs.appPackage2, prefs.appUser2, prefs.isShortcut2, prefs.shortcutId2)) {
            prefs.appName2 = ""
            prefs.appPackage2 = ""
        }
        if (homeAppsNum == 2) return

        binding.homeApp3.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp3, prefs.appName3, prefs.appPackage3, prefs.appUser3, prefs.isShortcut3, prefs.shortcutId3)) {
            prefs.appName3 = ""
            prefs.appPackage3 = ""
        }
        if (homeAppsNum == 3) return

        binding.homeApp4.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp4, prefs.appName4, prefs.appPackage4, prefs.appUser4, prefs.isShortcut4, prefs.shortcutId4)) {
            prefs.appName4 = ""
            prefs.appPackage4 = ""
        }
        if (homeAppsNum == 4) return

        binding.homeApp5.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp5, prefs.appName5, prefs.appPackage5, prefs.appUser5, prefs.isShortcut5, prefs.shortcutId5)) {
            prefs.appName5 = ""
            prefs.appPackage5 = ""
        }
        if (homeAppsNum == 5) return

        binding.homeApp6.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp6, prefs.appName6, prefs.appPackage6, prefs.appUser6, prefs.isShortcut6, prefs.shortcutId6)) {
            prefs.appName6 = ""
            prefs.appPackage6 = ""
        }
        if (homeAppsNum == 6) return

        binding.homeApp7.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp7, prefs.appName7, prefs.appPackage7, prefs.appUser7, prefs.isShortcut7, prefs.shortcutId7)) {
            prefs.appName7 = ""
            prefs.appPackage7 = ""
        }
        if (homeAppsNum == 7) return

        binding.homeApp8.visibility = View.VISIBLE
        if (!setHomeAppText(binding.homeApp8, prefs.appName8, prefs.appPackage8, prefs.appUser8, prefs.isShortcut8, prefs.shortcutId8)) {
            prefs.appName8 = ""
            prefs.appPackage8 = ""
        }
    }

    private fun setHomeAppText(
        textView: TextView,
        appName: String,
        packageName: String,
        userString: String,
        isShortcut: Boolean,
        shortcutId: String?,
    ): Boolean {
        // Get user handle for the app/shortcut
        val userHandle = getUserHandleFromString(requireContext(), userString)

        // If it's a shortcut, verify it still exists
        if (isShortcut) {
            val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

            // Query for the specific shortcut
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(packageName)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }

            try {
                val shortcuts = launcherApps.getShortcuts(query, userHandle)
                // Check if our shortcut still exists
                if (shortcuts?.any { it.id == shortcutId } == true) {
                    textView.text = appName
                    return true
                }
                textView.text = ""
                return false
            } catch (e: Exception) {
                e.printStackTrace()
                textView.text = ""
                return false
            }
        }

        // Regular app check
        if (isPackageInstalled(requireContext(), packageName, userString)) {
            textView.text = appName
            return true
        }
        textView.text = ""
        return false
    }

    private fun hideHomeApps() {
        binding.homeApp1.visibility = View.GONE
        binding.homeApp2.visibility = View.GONE
        binding.homeApp3.visibility = View.GONE
        binding.homeApp4.visibility = View.GONE
        binding.homeApp5.visibility = View.GONE
        binding.homeApp6.visibility = View.GONE
        binding.homeApp7.visibility = View.GONE
        binding.homeApp8.visibility = View.GONE
    }

    private fun launchAppOrShortcut(
        appName: String,
        packageName: String,
        activityClassName: String?,
        shortcutId: String?,
        isShortcut: Boolean,
        userString: String,
        fallback: (() -> Unit)? = null,
    ) {
        if (appName.isEmpty()) {
            showLongPressToast()
            return
        }
        if (isShortcut && !shortcutId.isNullOrEmpty()) {
            launchShortcut(
                packageName = packageName,
                shortcutId = shortcutId,
                shortcutLabel = appName,
                userString = userString
            )
        } else if (packageName.isNotEmpty()) {
            launchApp(
                appName = appName,
                packageName = packageName,
                activityClassName = activityClassName,
                userString = userString
            )
        } else {
            fallback?.invoke()
        }
    }

    private fun launchShortcut(shortcutId: String, packageName: String, shortcutLabel: String, userString: String) {
        viewModel.selectedApp(
            AppModel.PinnedShortcut(
                shortcutId = shortcutId,
                appLabel = shortcutLabel,
                user = getUserHandleFromString(requireContext(), userString),
                key = null,
                appPackage = packageName,
                isNew = false,
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun launchApp(appName: String, packageName: String, activityClassName: String?, userString: String) {
        viewModel.selectedApp(
            AppModel.App(
                appLabel = appName,
                key = null,
                appPackage = packageName,
                activityClassName = activityClassName,
                isNew = false,
                user = getUserHandleFromString(requireContext(), userString)
            ),
            Constants.FLAG_LAUNCH_APP
        )
    }

    private fun homeAppClicked(location: Int) {
        launchAppOrShortcut(
            appName = prefs.getAppName(location),
            packageName = prefs.getAppPackage(location),
            activityClassName = prefs.getAppActivityClassName(location),
            shortcutId = prefs.getShortcutId(location),
            isShortcut = prefs.getIsShortcut(location),
            userString = prefs.getAppUser(location)
        )
    }

    private fun openWidgets() {
        if (!prefs.swipeRightEnabled) return
        try {
            findNavController().navigate(R.id.action_mainFragment_to_widgetsFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openNotes() {
        if (!prefs.swipeLeftEnabled) return
        showNotesScreen()
    }

    /** Also where a tap on a home note goes, whether or not the swipe is on. */
    private fun showNotesScreen() {
        try {
            findNavController().navigate(R.id.action_mainFragment_to_notesFragment)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showAppList(flag: Int, rename: Boolean = false, includeHiddenApps: Boolean = false) {
        viewModel.getAppList(includeHiddenApps)
        try {
            findNavController().navigate(
                R.id.action_mainFragment_to_appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
        } catch (e: Exception) {
            findNavController().navigate(
                R.id.appListFragment,
                bundleOf(
                    Constants.Key.FLAG to flag,
                    Constants.Key.RENAME to rename
                )
            )
            e.printStackTrace()
        }
    }

    private fun lockPhone() {
        requireActivity().runOnUiThread {
            try {
                deviceManager.lockNow()
            } catch (e: SecurityException) {
                requireContext().showToast(getString(R.string.please_turn_on_double_tap_to_unlock), Toast.LENGTH_LONG)
                findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
            } catch (e: Exception) {
                requireContext().showToast(getString(R.string.launcher_failed_to_lock_device), Toast.LENGTH_LONG)
                prefs.lockModeOn = false
            }
        }
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun listenWallpaperColors() {
        val manager = WallpaperManager.getInstance(requireContext())
        val listener = WallpaperManager.OnColorsChangedListener { _, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) applyWallpaperTextColor()
        }
        wallpaperColorsListener = listener
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        // The worker stores the colors of the image it set (One UI does not report them); apply them on the main thread
        storedColorsListener = prefs.onWallpaperColorsChanged {
            view?.post { applyWallpaperTextColor() }
        }
        // The colors depend on what is behind the texts, so re-color when they move or show up (apps, clock width...)
        binding.mainLayout.viewTreeObserver.addOnGlobalLayoutListener(textLayoutListener)
    }

    // Home texts follow the system wallpaper (dark text on a light one; API 31+ tinted from the wallpaper's own colors,
    // not the system palette which may be a fixed preset) with a soft shadow of the opposite color; unknown colors keep
    // the theme's. On an image the app set, the texts read the part of the image behind them (by block, or text by text
    // where a block runs over light and dark parts). Only the home screen: the drawer, settings and notes are not touched
    @RequiresApi(Build.VERSION_CODES.O_MR1)
    private fun applyWallpaperTextColor() {
        if (_binding == null) return
        val colors = currentWallpaperColors(requireContext())
        val texts = binding.mainLayout.children.map { textBoxes(it) }.toList()
        val boxes = texts.map { block -> block.map { it.second } }
        textBoxesKey = boxes
        val measured = homeTextChoices(requireContext(), boxes, colors)
        val choices: List<List<TextChoice?>> = measured?.takeIf { it.flatten().any { choice -> choice != null } }
            ?: run {
                colors ?: return
                val darkText = wallpaperWantsDarkText(colors) ?: return
                val choice = TextChoice(homeTextColor(colors, darkText), darkText)
                texts.map { block -> block.map { choice } }
            }
        val byView = texts.flatten().map { it.first }.zip(choices.flatten()).toMap()
        // Hidden texts take the most common color until they show up (the layout listener re-colors them then)
        val common = choices.flatten().filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return
        textChoices = texts.flatten().associate { (view, box) -> box to (byView[view] ?: common) }
        fun style(view: View) {
            if (view is TextView) styleText(view, byView[view] ?: common)
            else if (view is ViewGroup) view.children.forEach { style(it) }
        }
        style(binding.mainLayout)
        val barColor = (texts.flatten().firstOrNull { it.first.parent == binding.bottomBar }?.let { byView[it.first] } ?: common).color
        bottomBarColor = barColor
        populateBottomBar(barColor) { slot -> byView[slot]?.color ?: barColor }
    }

    private fun styleText(view: TextView, choice: TextChoice) {
        view.setTextColor(choice.color)
        view.setHintTextColor(ColorUtils.setAlphaComponent(choice.color, 0xCC))
        val shadow = ColorUtils.setAlphaComponent(if (choice.darkText) Color.WHITE else Color.BLACK, 0x80)
        view.setShadowLayer(3.dpToPx().toFloat(), 0f, 1.dpToPx().toFloat(), shadow)
    }

    private fun changeAppTheme() {
        if (prefs.dailyWallpaper.not()) return
        val changedAppTheme = getChangedAppTheme(requireContext(), prefs.appTheme)
        prefs.appTheme = changedAppTheme
        if (prefs.dailyWallpaper) viewModel.setWallpaperWorker()
        requireActivity().recreate()
    }

    private fun openScreenTimeDigitalWellbeing() {
        if (prefs.screenTimeAppPackage.isNotBlank()) {
            launchApp(
                "Screen Time",
                prefs.screenTimeAppPackage,
                prefs.screenTimeAppClassName,
                prefs.screenTimeAppUser
            )
            return
        }
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showLongPressToast() = requireContext().showToast(getString(R.string.long_press_to_select_app))

    private fun textOnClick(view: View) = onClick(view)

    private fun textOnLongClick(view: View) = onLongClick(view)

    private fun getSwipeGestureListener(context: Context): View.OnTouchListener {
        return object : OnSwipeTouchListener(context) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openNotes()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openWidgets()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick() {
                super.onLongClick()
                try {
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                    viewModel.firstOpen(false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onDoubleClick() {
                super.onDoubleClick()
                if (!prefs.lockModeOn) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    binding.lock.performClick()
                else
                    lockPhone()
            }

            override fun isTripleClickEnabled() = prefs.dailyWallpaper && prefs.wallpaperSourceUrl.isNotBlank()

            override fun onTripleClick() {
                super.onTripleClick()
                viewModel.changeToNextWallpaper()
            }

            override fun onClick() {
                super.onClick()
                viewModel.checkForMessages.call()
            }
        }
    }

    private fun getViewSwipeTouchListener(
        context: Context,
        view: View,
        click: (View) -> Unit = ::textOnClick,
        longClick: (View) -> Unit = ::textOnLongClick,
    ): View.OnTouchListener {
        return object : ViewSwipeTouchListener(context, view) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                openNotes()
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                openWidgets()
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                showAppList(Constants.FLAG_LAUNCH_APP)
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                expandNotificationDrawer(requireContext())
            }

            override fun onLongClick(view: View) {
                super.onLongClick(view)
                longClick(view)
            }

            override fun onClick(view: View) {
                super.onClick(view)
                click(view)
            }
        }
    }

    override fun onDestroyView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
            (wallpaperColorsListener as? WallpaperManager.OnColorsChangedListener)?.let {
                WallpaperManager.getInstance(requireContext()).removeOnColorsChangedListener(it)
            }
        wallpaperColorsListener = null
        storedColorsListener?.let { prefs.removeListener(it) }
        storedColorsListener = null
        binding.mainLayout.viewTreeObserver.removeOnGlobalLayoutListener(textLayoutListener)
        textBoxesKey = null
        textChoices = emptyMap()
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        /** Space between the home notes and the widest app name. */
        const val HOME_NOTES_GAP_DP = 16
    }
}
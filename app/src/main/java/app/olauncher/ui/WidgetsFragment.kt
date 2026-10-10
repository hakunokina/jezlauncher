package app.olauncher.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.WidgetGrid
import app.olauncher.data.WidgetWall
import app.olauncher.databinding.FragmentWidgetsBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showMessageDialog
import app.olauncher.helper.showToast
import kotlin.math.abs

/**
 * Wall of real Android widgets (swipe right on the home screen) on a home screen like grid. Widget ids and their
 * cells live in [Prefs.widgetLayout]; the AppWidgetHost allocates and owns the ids, so removing a widget also
 * releases its id there. Long press starts edit mode ([WidgetWallView]): drag to move, edge handles to resize,
 * Remove asks to remove the selected widget; Done, back or a tap on empty space ends it.
 */
class WidgetsFragment : BaseFragment() {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var host: AppWidgetHost
    private lateinit var manager: AppWidgetManager
    private lateinit var widgetContext: Context

    private var _binding: FragmentWidgetsBinding? = null
    private val binding get() = _binding!!

    private val hostViews = mutableMapOf<Int, AppWidgetHostView>()

    /** Id allocated for the widget being added, until it is committed or released. */
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID

    private val endEditOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            binding.widgetsGrid.stopEditing()
        }
    }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onPicked(result.resultCode == Activity.RESULT_OK)
    }
    private val configureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onConfigured(result.resultCode == Activity.RESULT_OK)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentWidgetsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        host = AppWidgetHost(requireContext().applicationContext, WidgetWall.HOST_ID)
        manager = AppWidgetManager.getInstance(requireContext())
        widgetContext = PlainInflaterContext(requireContext())

        binding.widgetsGrid.hostView = ::hostViewFor
        binding.widgetsGrid.onLayoutChanged = { prefs.widgetLayout = it }
        binding.widgetsGrid.onEditChanged = ::onEditChanged
        binding.widgetsAdd.setOnClickListener { startAdd() }
        binding.widgetsRemove.setOnClickListener {
            binding.widgetsGrid.selectedId.takeIf { it != AppWidgetManager.INVALID_APPWIDGET_ID }?.let(::confirmRemove)
        }
        binding.widgetsDone.setOnClickListener { binding.widgetsGrid.stopEditing() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, endEditOnBack)
        viewModel.widgetConfigureResult.observe(viewLifecycleOwner) { onConfigured(it) }

        // Swipe left (like the Notes screen's mirror gesture) or back returns to the home screen
        val flingDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null || binding.widgetsGrid.editing) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (dx < -100.dpToPx() && abs(dx) > 2 * abs(dy) && abs(velocityX) > 1000) {
                    findNavController().popBackStack()
                    return true
                }
                return false
            }
        })
        binding.widgetsScroll.onTouch = { flingDetector.onTouchEvent(it) }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        binding.widgetsGrid.updatePadding(top = 24.dpToPx(), bottom = 88.dpToPx())

        showWidgets()
    }

    override fun onStart() {
        super.onStart()
        host.startListening()
    }

    override fun onStop() {
        host.stopListening()
        super.onStop()
    }

    override fun onDestroyView() {
        viewModel.isPickingWidget = false
        // A new view starts outside edit mode; back must leave the wall again
        endEditOnBack.isEnabled = false
        hostViews.clear()
        _binding = null
        super.onDestroyView()
    }

    // Providers can disappear (uninstall); such ids are released instead of shown as broken cells.
    // A wall saved before the grid (only an id order) moves onto it once, in the same order
    private fun showWidgets() {
        val saved = prefs.widgetLayout
        val legacy = prefs.legacyWidgetIds
        val dead = (saved.map { it.id } + legacy).filter { manager.getAppWidgetInfo(it) == null }.toSet()
        dead.forEach { host.deleteAppWidgetId(it) }
        val layout = WidgetWall.migrate(saved.filterNot { it.id in dead }, legacy - dead) { spanFor(it) }
        if (layout != saved) prefs.widgetLayout = layout
        if (legacy.isNotEmpty()) prefs.clearLegacyWidgetIds()
        binding.widgetsGrid.submit(layout)
    }

    // Cells a widget gets when it comes onto the wall
    private fun spanFor(id: Int): WidgetGrid.Span {
        val info = manager.getAppWidgetInfo(id) ?: return WidgetGrid.Span(WidgetGrid.COLUMNS, 1)
        return WidgetGrid.span(binding.widgetsGrid.metrics(), info.gridProvider(resources.displayMetrics.density))
    }

    private fun hostViewFor(id: Int): AppWidgetHostView? {
        val info = manager.getAppWidgetInfo(id) ?: return null
        return hostViews.getOrPut(id) {
            // AppWidgetHostView adds an 8dp default padding that eats the grid cell; the grid's gaps space widgets instead
            host.createView(widgetContext, id, info).apply { setPadding(0, 0, 0, 0) }
        }
    }

    private fun onEditChanged(editing: Boolean) {
        endEditOnBack.isEnabled = editing
        binding.widgetsAdd.isVisible = !editing
        binding.widgetsEditBar.isVisible = editing
    }

    private fun confirmRemove(id: Int) {
        requireContext().showMessageDialog(
            title = R.string.widgets_remove_title,
            message = R.string.widgets_remove_message,
            action = R.string.widgets_remove,
            onAction = { removeWidget(id) },
        )
    }

    private fun removeWidget(id: Int) {
        host.deleteAppWidgetId(id)
        hostViews.remove(id)
        prefs.widgetLayout = WidgetWall.remove(prefs.widgetLayout, id)
        if (_binding == null) return
        binding.widgetsGrid.stopEditing()
        binding.widgetsGrid.submit(prefs.widgetLayout)
    }

    // Add flow: system picker -> optional configure activity -> commit. The id is released on every failure path

    private fun startAdd() {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) return
        pendingId = host.allocateAppWidgetId()
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
            .putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, ArrayList())
            .putParcelableArrayListExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, ArrayList())
        launchOutside(pickLauncher, intent)
    }

    // Leaving MainActivity must not send the launcher back to the home screen
    private fun launchOutside(launcher: androidx.activity.result.ActivityResultLauncher<Intent>, intent: Intent) {
        viewModel.isPickingWidget = true
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.isPickingWidget = false
            fail()
        } catch (_: SecurityException) {
            viewModel.isPickingWidget = false
            fail()
        }
    }

    private fun onPicked(ok: Boolean) {
        if (!ok || pendingId == AppWidgetManager.INVALID_APPWIDGET_ID) return releasePending()
        val info = manager.getAppWidgetInfo(pendingId) ?: return fail()
        val configure = info.configure ?: return commitPending()
        // The host API also starts configure activities that are not exported (most One UI providers);
        // its result arrives in MainActivity.onActivityResult. The explicit intent is the fallback.
        viewModel.isPickingWidget = true
        try {
            host.startAppWidgetConfigureActivityForResult(
                requireActivity(), pendingId, 0, Constants.REQUEST_CODE_WIDGET_CONFIGURE, null
            )
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(configure)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
        launchOutside(configureLauncher, intent)
    }

    private fun onConfigured(ok: Boolean) {
        if (ok) commitPending() else releasePending()
    }

    private fun commitPending() {
        viewModel.isPickingWidget = false
        val id = pendingId
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID || _binding == null) return
        // Its target cells (or the cells its minimum size needs) on the first free place from the top
        val span = spanFor(id)
        prefs.widgetLayout = WidgetWall.add(prefs.widgetLayout, id, span.x, span.y)
        binding.widgetsGrid.submit(prefs.widgetLayout)
        binding.widgetsGrid.doOnNextLayout {
            val top = _binding?.widgetsGrid?.topOf(id) ?: return@doOnNextLayout
            binding.widgetsScroll.smoothScrollTo(0, top)
        }
    }

    private fun releasePending() {
        viewModel.isPickingWidget = false
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun fail() {
        releasePending()
        requireContext().showToast(getString(R.string.widgets_cannot_add))
    }
}

/**
 * The activity's LayoutInflater carries AppCompat's factory, which turns ImageView/TextView into
 * AppCompat subclasses; RemoteViews rejects their overridden setters (setImageResource, setBackgroundResource)
 * and the whole widget fails with "can't use method with RemoteViews". Widgets inflate without that factory.
 */
private class PlainInflaterContext(base: Context) : ContextWrapper(base) {
    private val inflater: LayoutInflater by lazy { LayoutInflater.from(base.applicationContext).cloneInContext(this) }

    override fun getSystemService(name: String): Any? =
        if (name == LAYOUT_INFLATER_SERVICE) inflater else super.getSystemService(name)
}

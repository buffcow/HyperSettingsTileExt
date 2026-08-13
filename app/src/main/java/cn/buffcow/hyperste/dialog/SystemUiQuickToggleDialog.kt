package cn.buffcow.hyperste.dialog

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.AttrRes
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.resource.ModuleResources
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleAction
import cn.buffcow.hyperste.toggle.QuickToggleHost
import cn.buffcow.hyperste.toggle.QuickToggleState
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Renders system-backed quick toggles inside a SystemUI dialog.
 *
 * @author qingyu
 * <p>Create on 2026/08/12 16:05</p>
 */
@SuppressLint("PrivateApi")
internal class SystemUiQuickToggleDialog(
    classLoader: ClassLoader,
    quickToggles: List<QuickToggle>,
) {

    private val systemUiDialogConstructor: Constructor<*> = classLoader
        .loadClass(SYSTEM_UI_DIALOG_CLASS)
        .getConstructor(Context::class.java)
        .apply {
            isAccessible = true
        }
    private val activityStarterClass = classLoader.loadClass(ACTIVITY_STARTER_CLASS)
    private val postStartActivityMethod = activityStarterClass.getMethod(
        POST_START_ACTIVITY_METHOD,
        Intent::class.java,
        Int::class.javaPrimitiveType!!,
    ).apply {
        isAccessible = true
    }
    private val slidingButtonReflection: SlidingButtonReflection? = runCatching {
        classLoader.loadClass(MIUIX_SLIDING_BUTTON_CLASS)
            .asSubclass(CompoundButton::class.java)
            .run {
                SlidingButtonReflection(
                    constructor = getConstructor(Context::class.java).apply {
                        isAccessible = true
                    },
                    setOnPerformCheckedChangeListenerMethod = getMethod(
                        SET_ON_PERFORM_CHECKED_CHANGE_LISTENER_METHOD,
                        CompoundButton.OnCheckedChangeListener::class.java,
                    ).apply {
                        isAccessible = true
                    },
                )
            }
    }.onFailure {
        logDebug(
            "HyperOS SlidingButton is unavailable; using the platform Switch fallback: " +
                    it.javaClass.simpleName,
        )
    }.getOrNull()
    private val quickToggles = quickToggles.distinctBy(QuickToggle::id)
    private var activeDialogReference: WeakReference<AlertDialog>? = null

    /**
     * Shows the quick-toggle dialog on the main thread.
     *
     * A `false` result indicates that the caller should preserve the original system
     * long-press behavior because no toggle is available or the dialog could not be shown.
     */
    fun show(
        context: Context,
        activityStarter: Any,
        originalLongClickAction: () -> Unit,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            logError(
                "SettingsTile long press was invoked off the main thread; " +
                        "preserving the original system behavior",
                null,
            )
            return false
        }
        return showSafely(context, activityStarter, originalLongClickAction)
    }

    private fun showSafely(
        context: Context,
        activityStarter: Any,
        originalLongClickAction: () -> Unit,
    ): Boolean {
        return runCatching {
            if (activeDialogReference?.get()?.isShowing == true) {
                return@runCatching true
            }

            val moduleResources = ModuleResources.from(context)
            val host = createQuickToggleHost(context, activityStarter, moduleResources)
            val availableGroups = collectAvailableGroups(host)
            if (availableGroups.isEmpty()) {
                logDebug("No supported quick toggles are available; preserving system behavior")
                return@runCatching false
            }

            showNow(
                context,
                moduleResources,
                availableGroups,
                host,
                originalLongClickAction,
            )
            true
        }.getOrElse {
            logError("Failed to create or show the SystemUI quick toggle dialog", it)
            false
        }
    }

    private fun collectAvailableGroups(
        host: QuickToggleHost,
    ): List<QuickToggleGroupEntry> {
        val entries = quickToggles.mapNotNull { quickToggle ->
            runCatching {
                quickToggle.readState(host)
            }.onFailure {
                logError("Failed to read initial quick toggle state: id=${quickToggle.id}", it)
            }.getOrNull()
                ?.takeIf(QuickToggleState::isAvailable)
                ?.let { state ->
                    QuickToggleEntry(
                        quickToggle = quickToggle,
                        state = state,
                        title = host.moduleResources.resolveString(
                            quickToggle.titleRes,
                            quickToggle.fallbackTitle,
                        ),
                        description = quickToggle.descriptionRes?.let { resourceId ->
                            host.moduleResources.resolveString(
                                resourceId,
                                quickToggle.fallbackDescription.orEmpty(),
                            ).takeIf(String::isNotEmpty)
                        } ?: quickToggle.fallbackDescription,
                    )
                }
        }
        return entries.groupBy { it.quickToggle.category }
            .map { (category, categoryEntries) ->
                QuickToggleGroupEntry(
                    title = host.moduleResources.resolveString(
                        category.titleRes,
                        category.fallbackTitle,
                    ),
                    entries = categoryEntries,
                )
            }
    }

    private fun showNow(
        context: Context,
        moduleResources: ModuleResources?,
        groups: List<QuickToggleGroupEntry>,
        host: QuickToggleHost,
        originalLongClickAction: () -> Unit,
    ) {
        val dialog = systemUiDialogConstructor.newInstance(context) as AlertDialog
        val dialogContext = dialog.context
        dialog.apply {
            setTitle(
                moduleResources.resolveString(
                    R.string.quick_toggle_dialog_title,
                    FALLBACK_DIALOG_TITLE,
                ),
            )
            setView(createContentView(dialogContext, moduleResources, groups, host))
            setButton(
                DialogInterface.BUTTON_NEUTRAL,
                moduleResources.resolveString(
                    R.string.quick_toggle_settings,
                    FALLBACK_SETTINGS_BUTTON_LABEL,
                ),
            ) { target, _ ->
                target.dismiss()
                runCatching(originalLongClickAction).onFailure {
                    logError("Failed to invoke the original SettingsTile long-click action", it)
                }
            }
            setButton(
                DialogInterface.BUTTON_NEGATIVE,
                moduleResources.resolveString(
                    R.string.quick_toggle_close,
                    FALLBACK_CLOSE_BUTTON_LABEL,
                ),
            ) { target, _ -> target.dismiss() }
            show()
        }
        activeDialogReference = WeakReference(dialog)
        logDebug(
            "SystemUI quick toggle dialog shown: " +
                    "categoryCount=${groups.size}, " +
                    "toggleCount=${groups.sumOf { it.entries.size }}",
        )
    }

    private fun createContentView(
        context: Context,
        moduleResources: ModuleResources?,
        groups: List<QuickToggleGroupEntry>,
        host: QuickToggleHost,
    ): ScrollView {
        val horizontalPadding = context.dp(HORIZONTAL_PADDING_DP)
        val verticalPadding = context.dp(VERTICAL_PADDING_DP)
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(horizontalPadding, 0, horizontalPadding, verticalPadding)
            groups.forEachIndexed { index, group ->
                val groupContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                }
                val groupBinding = ToggleGroupBinding(container = groupContainer)
                groupContainer.addView(
                    createCategoryTextView(context, group.title),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (index > 0) {
                            topMargin = context.dp(CATEGORY_SPACING_DP)
                        }
                    },
                )
                group.entries.forEachIndexed { entryIndex, entry ->
                    val binding = createToggleBinding(
                        context,
                        moduleResources,
                        entry,
                        host,
                        groupBinding,
                    )
                    groupBinding.toggleBindings += binding
                    groupContainer.addView(
                        binding.container,
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            if (entryIndex > 0) {
                                topMargin = context.dp(TOGGLE_SPACING_DP)
                            }
                        },
                    )
                    schedulePeriodicStateRefresh(binding)
                }
                updateGroupVisibility(groupBinding)
                addView(
                    groupContainer,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        }
        return ScrollView(context).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createToggleBinding(
        context: Context,
        moduleResources: ModuleResources?,
        entry: QuickToggleEntry,
        host: QuickToggleHost,
        groupBinding: ToggleGroupBinding,
    ): ToggleBinding {
        val toggle = createToggle(context).apply {
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val secondaryTextView = TextView(context).apply {
            alpha = DESCRIPTION_ALPHA
            setTextSize(TypedValue.COMPLEX_UNIT_SP, DESCRIPTION_TEXT_SIZE_SP)
            setPadding(0, context.dp(DESCRIPTION_TOP_PADDING_DP), 0, 0)
            visibility = View.GONE
        }
        val titleTextView = TextView(context).apply {
            text = entry.title
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_TEXT_SIZE_SP)
            context.resolveColorStateList(android.R.attr.textColorPrimary)?.let {
                setTextColor(it)
            }
        }
        val textContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                titleTextView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                secondaryTextView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(TOGGLE_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            addView(
                textContainer,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(
                toggle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = context.dp(TOGGLE_START_MARGIN_DP)
                },
            )
        }
        return ToggleBinding(
            container = container,
            toggle = toggle,
            titleTextView = titleTextView,
            secondaryTextView = secondaryTextView,
            quickToggle = entry.quickToggle,
            host = host,
            groupBinding = groupBinding,
            title = entry.title,
            defaultSecondaryText = entry.description,
            disabledAlpha = context.resolveFloatAttribute(
                android.R.attr.disabledAlpha,
                FALLBACK_DISABLED_ALPHA,
            ),
            stateOnLabel = moduleResources.resolveString(
                R.string.quick_toggle_state_on,
                FALLBACK_STATE_ON_LABEL,
            ),
            stateOffLabel = moduleResources.resolveString(
                R.string.quick_toggle_state_off,
                FALLBACK_STATE_OFF_LABEL,
            ),
        ).also { binding ->
            applyState(binding, entry.state)
            container.setOnClickListener {
                if (toggle.isEnabled) {
                    requestStateChange(binding, !toggle.isChecked)
                }
            }
            bindToggleClickListener(binding)
            entry.quickToggle.longClickAction?.let { action ->
                textContainer.setOnLongClickListener {
                    performLongClickAction(binding, action, host)
                }
            }
        }
    }

    private fun createCategoryTextView(context: Context, title: CharSequence): TextView {
        return TextView(context).apply {
            text = title
            minimumHeight = context.dp(CATEGORY_HEIGHT_DP)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isLongClickable = false
            isFocusable = false
            setTextAppearance(android.R.style.TextAppearance_Material_Body2)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CATEGORY_TEXT_SIZE_SP)
            val textColors = context.resolveColorStateList(android.R.attr.colorAccent)
                ?: context.resolveColorStateList(android.R.attr.textColorSecondary)
            textColors?.let { setTextColor(it) }
        }
    }

    private fun bindToggleClickListener(binding: ToggleBinding) {
        val miuixListenerBound = slidingButtonReflection
            ?.takeIf { reflection ->
                reflection.constructor.declaringClass.isInstance(binding.toggle)
            }
            ?.let { reflection ->
                runCatching {
                    reflection.setOnPerformCheckedChangeListenerMethod.invoke(
                        binding.toggle,
                        CompoundButton.OnCheckedChangeListener { _, isChecked ->
                            logDebug(
                                "HyperOS SlidingButton state change performed: " +
                                        "id=${binding.quickToggle.id}, checked=$isChecked",
                            )
                            if (binding.toggle.isEnabled) {
                                requestStateChange(binding, isChecked)
                            }
                        },
                    )
                    logDebug(
                        "Bound HyperOS SlidingButton state change listener: " +
                                "id=${binding.quickToggle.id}",
                    )
                    true
                }.onFailure {
                    logError("Failed to bind the HyperOS SlidingButton change listener", it)
                }.getOrDefault(false)
            } == true
        if (!miuixListenerBound) {
            binding.toggle.setOnCheckedChangeListener { _, isChecked ->
                if (binding.toggle.isEnabled) {
                    requestStateChange(binding, !isChecked)
                }
            }
        }
    }

    private fun requestStateChange(binding: ToggleBinding, requestedState: Boolean) {
        logDebug(
            "Quick toggle state change requested: " +
                    "id=${binding.quickToggle.id}, checked=$requestedState",
        )
        binding.toggle.apply {
            isChecked = requestedState
            isEnabled = false
        }
        applyTextEnabledState(binding, false)
        runCatching {
            binding.quickToggle.setChecked(binding.host, requestedState)
        }.onFailure {
            logError(
                "Failed to change quick toggle state: " +
                        "id=${binding.quickToggle.id}, checked=$requestedState",
                it,
            )
            bindCurrentState(binding)
        }
        scheduleStateRefreshes(binding)
    }

    private fun performLongClickAction(
        binding: ToggleBinding,
        action: QuickToggleAction,
        host: QuickToggleHost,
    ): Boolean {
        runCatching {
            action.perform(host)
        }.onSuccess {
            activeDialogReference?.get()?.dismiss()
        }.onFailure {
            logError(
                "Failed to perform quick toggle long-click action: id=${binding.quickToggle.id}",
                it,
            )
        }
        return true
    }

    private fun createQuickToggleHost(
        hostContext: Context,
        activityStarter: Any,
        resources: ModuleResources?,
    ): QuickToggleHost {
        require(activityStarterClass.isInstance(activityStarter)) {
            "SettingsTile.mActivityStarter has an unexpected type: ${activityStarter.javaClass.name}"
        }
        return object : QuickToggleHost {
            override val context: Context = hostContext
            override val moduleResources: ModuleResources? = resources

            override fun startActivity(intent: Intent) {
                postStartActivityMethod.invoke(activityStarter, intent, NO_LAUNCH_DELAY_MS)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun createToggle(context: Context): CompoundButton {
        return slidingButtonReflection?.constructor?.let { constructor ->
            runCatching {
                constructor.newInstance(context)
            }.onFailure {
                logError("Failed to create the HyperOS SlidingButton", it)
            }.getOrNull()
        } ?: Switch(context)
    }

    private fun scheduleStateRefreshes(binding: ToggleBinding) {
        STATE_REFRESH_DELAYS_MS.forEach { refreshDelay ->
            binding.toggle.postDelayed(
                {
                    if (binding.toggle.isAttachedToWindow) {
                        bindCurrentState(binding)
                    }
                },
                refreshDelay,
            )
        }
    }

    private fun schedulePeriodicStateRefresh(binding: ToggleBinding) {
        binding.toggle.postDelayed(
            object : Runnable {
                override fun run() {
                    if (!binding.toggle.isAttachedToWindow) {
                        return
                    }
                    bindCurrentState(binding)
                    binding.toggle.postDelayed(this, STATE_POLL_INTERVAL_MS)
                }
            },
            STATE_POLL_INTERVAL_MS,
        )
    }

    private fun bindCurrentState(binding: ToggleBinding) {
        runCatching {
            binding.quickToggle.readState(binding.host)
        }.onSuccess { state ->
            binding.readFailed = false
            applyState(binding, state)
        }.onFailure {
            with(binding) {
                container.isEnabled = false
                toggle.isEnabled = false
            }
            applyTextEnabledState(binding, false)
            if (!binding.readFailed) {
                binding.readFailed = true
                logError(
                    "Failed to refresh quick toggle state: id=${binding.quickToggle.id}",
                    it,
                )
            }
        }
    }

    private fun applyState(binding: ToggleBinding, state: QuickToggleState) {
        binding.container.visibility = if (state.isAvailable) View.VISIBLE else View.GONE
        updateGroupVisibility(binding.groupBinding)
        if (!state.isAvailable) {
            return
        }
        val stateLabel = if (state.isChecked) binding.stateOnLabel else binding.stateOffLabel
        val secondaryText = state.secondaryText ?: binding.defaultSecondaryText
        binding.container.apply {
            minimumHeight = context.dp(
                if (secondaryText.isNullOrEmpty()) {
                    SINGLE_LINE_TOGGLE_HEIGHT_DP
                } else {
                    TOGGLE_HEIGHT_DP
                },
            )
            isEnabled = state.isEnabled || binding.quickToggle.longClickAction != null
            contentDescription = buildString {
                append(binding.title)
                append(", ")
                append(stateLabel)
                if (!secondaryText.isNullOrEmpty()) {
                    append(", ")
                    append(secondaryText)
                }
            }
        }
        binding.toggle.apply {
            isChecked = state.isChecked
            isEnabled = state.isEnabled
        }
        applyTextEnabledState(binding, state.isEnabled)
        binding.secondaryTextView.apply {
            text = secondaryText
            visibility = if (secondaryText.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun updateGroupVisibility(groupBinding: ToggleGroupBinding) {
        groupBinding.container.visibility = if (
            groupBinding.toggleBindings.any { it.container.visibility == View.VISIBLE }
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun applyTextEnabledState(binding: ToggleBinding, isEnabled: Boolean) {
        with(binding) {
            titleTextView.apply {
                this.isEnabled = isEnabled
                alpha = if (isEnabled) ENABLED_ALPHA else disabledAlpha
            }
            secondaryTextView.apply {
                this.isEnabled = isEnabled
                alpha = DESCRIPTION_ALPHA * (if (isEnabled) ENABLED_ALPHA else disabledAlpha)
            }
        }
    }

    private fun ModuleResources?.resolveString(resourceId: Int, fallback: String): String {
        return this?.getString(resourceId, fallback) ?: fallback
    }

    private fun Context.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun Context.resolveColorStateList(@AttrRes attribute: Int): ColorStateList? {
        return obtainStyledAttributes(intArrayOf(attribute)).run {
            try {
                getColorStateList(0)
            } finally {
                recycle()
            }
        }
    }

    private fun Context.resolveFloatAttribute(@AttrRes attribute: Int, fallback: Float): Float {
        return obtainStyledAttributes(intArrayOf(attribute)).run {
            try {
                getFloat(0, fallback).coerceIn(0f, 1f)
            } finally {
                recycle()
            }
        }
    }

    private data class QuickToggleEntry(
        val quickToggle: QuickToggle,
        val state: QuickToggleState,
        val title: String,
        val description: String?,
    )

    private data class QuickToggleGroupEntry(
        val title: String,
        val entries: List<QuickToggleEntry>,
    )

    private class ToggleGroupBinding(
        val container: LinearLayout,
        val toggleBindings: MutableList<ToggleBinding> = mutableListOf(),
    )

    private data class ToggleBinding(
        val container: LinearLayout,
        val toggle: CompoundButton,
        val titleTextView: TextView,
        val secondaryTextView: TextView,
        val quickToggle: QuickToggle,
        val host: QuickToggleHost,
        val groupBinding: ToggleGroupBinding,
        val title: String,
        val defaultSecondaryText: CharSequence?,
        val disabledAlpha: Float,
        val stateOnLabel: String,
        val stateOffLabel: String,
        var readFailed: Boolean = false,
    )

    private data class SlidingButtonReflection(
        val constructor: Constructor<out CompoundButton>,
        val setOnPerformCheckedChangeListenerMethod: Method,
    )

    companion object {
        private const val SYSTEM_UI_DIALOG_CLASS = "com.android.systemui.statusbar.phone.SystemUIDialog"
        private const val ACTIVITY_STARTER_CLASS = "com.android.systemui.plugins.ActivityStarter"
        private const val POST_START_ACTIVITY_METHOD = "postStartActivityDismissingKeyguard"
        private const val MIUIX_SLIDING_BUTTON_CLASS = "miuix.slidingwidget.widget.SlidingButton"
        private const val SET_ON_PERFORM_CHECKED_CHANGE_LISTENER_METHOD = "setOnPerformCheckedChangeListener"

        private const val FALLBACK_DIALOG_TITLE = "Quick controls"
        private const val FALLBACK_SETTINGS_BUTTON_LABEL = "Settings"
        private const val FALLBACK_CLOSE_BUTTON_LABEL = "Close"
        private const val FALLBACK_STATE_ON_LABEL = "On"
        private const val FALLBACK_STATE_OFF_LABEL = "Off"

        private const val HORIZONTAL_PADDING_DP = 24
        private const val VERTICAL_PADDING_DP = 8
        private const val TOGGLE_START_MARGIN_DP = 16
        private const val TOGGLE_SPACING_DP = 6
        private const val SINGLE_LINE_TOGGLE_HEIGHT_DP = 40
        private const val TOGGLE_HEIGHT_DP = 52
        private const val CATEGORY_HEIGHT_DP = 32
        private const val CATEGORY_SPACING_DP = 8
        private const val DESCRIPTION_TOP_PADDING_DP = 4
        private const val CATEGORY_TEXT_SIZE_SP = 14f
        private const val TITLE_TEXT_SIZE_SP = 18f
        private const val DESCRIPTION_TEXT_SIZE_SP = 13f
        private const val ENABLED_ALPHA = 1f
        private const val DESCRIPTION_ALPHA = 0.7f
        private const val FALLBACK_DISABLED_ALPHA = 0.38f
        private const val NO_LAUNCH_DELAY_MS = 0
        private const val STATE_POLL_INTERVAL_MS = 2_000L

        private val STATE_REFRESH_DELAYS_MS = longArrayOf(300L, 900L, 1_800L)
    }
}

package cn.buffcow.hyperste.dialog

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import cn.buffcow.hyperste.R
import cn.buffcow.hyperste.logDebug
import cn.buffcow.hyperste.logError
import cn.buffcow.hyperste.resource.ModuleResources
import cn.buffcow.hyperste.toggle.ModuleSettingsStore
import cn.buffcow.hyperste.toggle.QuickToggle
import cn.buffcow.hyperste.toggle.QuickToggleRegistry
import java.lang.ref.WeakReference

/**
 * Edits module behavior and the quick toggles displayed without changing their system state.
 *
 * @author qingyu
 * <p>Create on 2026/08/14 17:50</p>
 */
internal class SystemUiModuleSettingsDialog(
    private val dialogFactory: SystemUiDialogFactory,
    private val registry: QuickToggleRegistry,
    private val settingsStore: ModuleSettingsStore,
    private val toggleFactory: (Context) -> CompoundButton,
) {

    private var activeDialogReference: WeakReference<AlertDialog>? = null

    /** Shows the staged module-settings dialog and invokes [onReturn] when it closes. */
    fun show(context: Context, onReturn: () -> Unit): Boolean {
        if (activeDialogReference?.get()?.isShowing == true) {
            return true
        }
        return runCatching {
            showNow(context, onReturn)
            true
        }.getOrElse {
            logError("Failed to create or show the module settings dialog", it)
            false
        }
    }

    private fun showNow(context: Context, onReturn: () -> Unit) {
        val moduleResources = ModuleResources.from(context)
        val disabledIds = runCatching {
            settingsStore.readDisabledIds(context)
        }.onFailure {
            logError("Failed to read the saved quick-toggle selection", it)
        }.getOrDefault(emptySet())
        val collapseAfterSwitching = runCatching {
            settingsStore.readCollapseAfterSwitching(context)
        }.onFailure {
            logError("Failed to read the collapse-after-switching setting", it)
        }.getOrDefault(false)
        val bindings = mutableListOf<SelectionBinding>()
        val dialog = dialogFactory.create(context)
        val dialogContext = dialog.context
        val collapseAfterSwitchingToggle = toggleFactory(dialogContext).apply {
            isChecked = collapseAfterSwitching
            isFocusable = false
        }
        var returnInvoked = false
        val returnToQuickControls = {
            if (!returnInvoked) {
                returnInvoked = true
                onReturn()
            }
        }

        dialog.apply {
            setTitle(
                moduleResources.resolveString(
                    R.string.quick_toggle_management_title,
                    FALLBACK_TITLE,
                ),
            )
            setView(
                createContentView(
                    context = dialogContext,
                    moduleResources = moduleResources,
                    disabledIds = disabledIds,
                    collapseAfterSwitchingToggle = collapseAfterSwitchingToggle,
                    bindings = bindings,
                ),
            )
            val nullListener: DialogInterface.OnClickListener? = null
            setButton(
                DialogInterface.BUTTON_POSITIVE,
                moduleResources.resolveString(
                    R.string.quick_toggle_management_save,
                    FALLBACK_SAVE,
                ),
                nullListener,
            )
            setButton(
                DialogInterface.BUTTON_NEGATIVE,
                moduleResources.resolveString(
                    R.string.quick_toggle_management_cancel,
                    FALLBACK_CANCEL,
                ),
                nullListener,
            )
            setOnDismissListener {
                returnToQuickControls()
            }
            show()
            getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                saveSettings(context, bindings, collapseAfterSwitchingToggle.isChecked)
                    .onSuccess {
                        dismiss()
                    }
            }
            getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
                dismiss()
            }
        }
        activeDialogReference = WeakReference(dialog)
        logDebug("Module settings dialog shown: toggleCount=${bindings.size}")
    }

    private fun createContentView(
        context: Context,
        moduleResources: ModuleResources?,
        disabledIds: Set<String>,
        collapseAfterSwitchingToggle: CompoundButton,
        bindings: MutableList<SelectionBinding>,
    ): ScrollView {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                context.dp(HORIZONTAL_PADDING_DP),
                0,
                context.dp(HORIZONTAL_PADDING_DP),
                context.dp(VERTICAL_PADDING_DP),
            )
            addView(
                createCollapseAfterSwitchingBinding(
                    context = context,
                    title = moduleResources.resolveString(
                        R.string.quick_toggle_collapse_after_switching,
                        FALLBACK_COLLAPSE_AFTER_SWITCHING,
                    ),
                    toggle = collapseAfterSwitchingToggle,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            registry.entries.groupBy(QuickToggle::category)
                .entries
                .forEach { (category, toggles) ->
                    addView(
                        createCategoryTextView(
                            context,
                            moduleResources.resolveString(
                                category.titleRes,
                                category.fallbackTitle,
                            ),
                        ),
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = context.dp(CATEGORY_SPACING_DP)
                        },
                    )
                    toggles.forEach { toggle ->
                        val binding = createSelectionBinding(
                            context = context,
                            title = moduleResources.resolveString(
                                toggle.titleRes,
                                toggle.fallbackTitle,
                            ),
                            toggle = toggle,
                            checked = toggle.id !in disabledIds,
                        )
                        bindings += binding
                        addView(
                            binding.container,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    }
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

    private fun createCollapseAfterSwitchingBinding(
        context: Context,
        title: CharSequence,
        toggle: CompoundButton,
    ): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(ITEM_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            addView(
                createItemTextView(context, title),
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
                    marginStart = context.dp(CONTROL_START_MARGIN_DP)
                },
            )
            setOnClickListener {
                toggle.toggle()
            }
        }
    }

    private fun createSelectionBinding(
        context: Context,
        title: CharSequence,
        toggle: QuickToggle,
        checked: Boolean,
    ): SelectionBinding {
        val checkBox = CheckBox(context).apply {
            isChecked = checked
            isClickable = false
            isFocusable = false
        }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(ITEM_HEIGHT_DP)
            isClickable = true
            isFocusable = true
            addView(
                createItemTextView(context, title),
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )
            addView(
                checkBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = context.dp(CONTROL_START_MARGIN_DP)
                },
            )
            setOnClickListener {
                checkBox.toggle()
            }
        }
        return SelectionBinding(container, checkBox, toggle)
    }

    private fun createItemTextView(context: Context, title: CharSequence): TextView {
        return TextView(context).apply {
            text = title
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ITEM_TEXT_SIZE_SP)
            context.resolveColorStateList(android.R.attr.textColorPrimary)?.let {
                setTextColor(it)
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

    private fun saveSettings(
        context: Context,
        bindings: List<SelectionBinding>,
        collapseAfterSwitching: Boolean,
    ): Result<Unit> {
        val knownIds = bindings.mapTo(mutableSetOf()) { it.toggle.id }
        val disabledIds = bindings
            .asSequence()
            .filterNot { it.checkBox.isChecked }
            .mapTo(mutableSetOf()) { it.toggle.id }
        return runCatching {
            settingsStore.writeSettings(
                context = context,
                knownIds = knownIds,
                disabledKnownIds = disabledIds,
                collapseAfterSwitching = collapseAfterSwitching,
            )
        }.onSuccess {
            logDebug(
                "Module settings saved: " +
                        "disabledCount=${disabledIds.size}, " +
                        "collapseAfterSwitching=$collapseAfterSwitching",
            )
        }.onFailure {
            logError("Failed to save the module settings", it)
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

    private data class SelectionBinding(
        val container: LinearLayout,
        val checkBox: CheckBox,
        val toggle: QuickToggle,
    )

    companion object {
        private const val FALLBACK_TITLE = "Module settings"
        private const val FALLBACK_COLLAPSE_AFTER_SWITCHING = "Collapse shade after switching"
        private const val FALLBACK_SAVE = "Save"
        private const val FALLBACK_CANCEL = "Cancel"

        private const val HORIZONTAL_PADDING_DP = 24
        private const val VERTICAL_PADDING_DP = 8
        private const val CATEGORY_HEIGHT_DP = 32
        private const val CATEGORY_SPACING_DP = 8
        private const val ITEM_HEIGHT_DP = 45
        private const val CONTROL_START_MARGIN_DP = 16
        private const val CATEGORY_TEXT_SIZE_SP = 14f
        private const val ITEM_TEXT_SIZE_SP = 16f
    }
}
